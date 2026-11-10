package gg.essential.loader.stage2.jij;

import gg.essential.loader.stage2.data.FabricModJson;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

/**
 * Jar-in-Jar dependencies require special plumbing because fabric-loader has already resolved all mods to be loaded by
 * the time we get to run our updates.
 *
 * To deal with that, we will on-demand create/update a synthetic "Essential Dependencies" mod in the user's mods
 * directory and force them to restart if required so fabric-loader can load updated versions from there.
 *
 * To avoid having to restart after every update, we only extract dependencies into the synthetic mod when they are
 * already loaded and outdated (i.e. when the user or another mod ships them as well).
 */
public class JarInJarDependenciesHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String SYNTHETIC_MOD_ID = "essential-dependencies";
    private static final String SYNTHETIC_MOD_NAME = "Essential Dependencies";
    private static final String SYNTHETIC_MOD_FILE_NAME = SYNTHETIC_MOD_NAME + ".jar";

    /**
     * @see #modsToDisable
     */
    private static final boolean NEED_TO_DISABLE_USER_MODS;
    static {
        boolean isOldLoader;
        try {
            Class.forName("net.fabricmc.loader.discovery.ModCandidateSet");
            isOldLoader = true;
        } catch (ClassNotFoundException e) {
            isOldLoader = false;
        }
        NEED_TO_DISABLE_USER_MODS = isOldLoader;
    }

    /**
     * If we find one of our JiJ dependencies to already be loaded, and that loaded version to be out of date, then the
     * path to our more up-to-date version is added to this list (keyed by the mod id).
     */
    private final Map<String, Path> updates = new HashMap<>();

    /**
     * Contains the display names of all mods which have been updated.
     */
    private final List<String> updatedModNames = new ArrayList<>();

    /**
     * Fabric Loader prior to 0.12 does not consider Jar in Jar mods if the mod is installed directly. To work around
     * that, we'll disable (change extension to end with {@code .jar.disabled}) those.
     * However, we must delay these renames cause Windows doesn't allow us to delete or rename files which are currently
     * in use.
     */
    private final List<Path> modsToDisable = new ArrayList<>();

    /**
     * Folder into which nested jars are extracted.
     */
    private final Path extractedJarsRoot;

    public JarInJarDependenciesHandler(Path extractedJarsRoot) {
        this.extractedJarsRoot = extractedJarsRoot;
    }

    /**
     * Checks that the given mod is not already loaded.
     * If a mod is loaded and outdated, it is queued to be updated.
     *
     * @param path path to the jar-in-jar mod
     * @return list of mods which should be injected dynamically
     * @throws RuntimeException if the fabric.mod.json is missing or invalid
     */
    public List<Path> loadMod(Path path) {
        List<Path> jarsToLoad = new ArrayList<>();
        if (loadMod(path, false, jarsToLoad)) {
            return jarsToLoad;
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Checks that the given mod is not already loaded.
     * Recursively checks nested jars as well.
     * If any mod is loaded and outdated, the outermost (not deeply nested) jar is queued to be updated.
     *
     * @param path path to the jar-in-jar mod
     * @param isDeeplyNestedJar whether this is a jar-in-jar mod nested within another jar-in-jar mod
     * @param jarsToLoad list of all jars which should be injected dynamically
     * @return {@code false} if any mod is outdated, {@code true} if everything is good as is
     * @throws RuntimeException if the fabric.mod.json is missing or invalid
     */
    private boolean loadMod(Path path, boolean isDeeplyNestedJar, List<Path> jarsToLoad) {
        // Parse mod id, version and name from the given jar
        FabricModJson modJson;
        String modId;
        Version modVersion;
        String modName;
        try {
            modJson = FabricModJson.readFromJar(path);
            modId = modJson.getId();
            modVersion = Version.parse(modJson.getVersion());
            modName = modJson.getName();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read fabric.mod.json of " + path, e);
        }

        // Extract inner jars first (we need to look at all of them, regardless of the result for the outer jars)
        List<Path> innerJars;
        try {
            innerJars = extractInnerJars(path, modJson);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract inner jars from " + path, e);
        }
        for (Path innerJar : innerJars) {
            if (!loadMod(innerJar, true, jarsToLoad)) {
                LOGGER.debug("An inner mod of {} needs updating, skipping up-to-date checks for the outer mod", modId);
                // if this is the outer-most mod, queue it for the update
                if (!isDeeplyNestedJar) {
                    updates.put(modId, path);
                    updatedModNames.add(modName != null ? modName : modId);
                }
                return false; // cannot proceed with outdated inner mods
            }
        }

        // Some mods we cannot load in a development environment if they are already on the app classpath.
        if (FabricLoader.getInstance().isDevelopmentEnvironment() && isAlreadyOnClasspath(modId)) {
            LOGGER.debug("Detected Kotlin already on the classpath, cannot load bundled {}", modId);
            return true; // kotlin is already on the classpath, we're good to go; we must not try to load or update it
        }

        // Check if that mod is already loaded (by the user, by a third-party mod, or by our existing synthetic mod)
        ModContainer loadedMod = FabricLoader.getInstance().getModContainer(modId).orElse(null);
        if (loadedMod == null) {
            LOGGER.debug("Mod {} is not loaded, injecting directly", modId);
            jarsToLoad.add(path);
            return true; // will be loaded dynamically, we're good to go
        }

        // It is loaded, check if the loaded version is up-to-date
        Version loadedVersion = loadedMod.getMetadata().getVersion();
        boolean loadedIsUpToDate;
        if (loadedVersion instanceof SemanticVersion && modVersion instanceof SemanticVersion) {
            // loaded >= modVersion
            loadedIsUpToDate = ((SemanticVersion) loadedVersion).compareTo((SemanticVersion) modVersion) >= 0;
        } else {
            loadedIsUpToDate = false; // can only compare semantic version, let's just assume it's outdated
            LOGGER.debug("Mod {} is loaded but has non-semantic version, assuming outdated.", modId);
        }

        if (loadedIsUpToDate) {
            LOGGER.debug("A newer version ({}) of mod {} ({}) is already loaded, skipping.", loadedVersion, modId, modVersion);
            return true; // loaded but up-to-date, we're good to go; don't need to load it again
        }

        // loaded and outdated, need to update it
        LOGGER.info("An older version ({}) of mod {} ({}) is already loaded, updating..", loadedVersion, modId, modVersion);
        // if this is the outer-most mod, queue it for the update
        if (!isDeeplyNestedJar) {
            updates.put(modId, path);
            updatedModNames.add(modName != null ? modName : modId);
        }
        return false;
    }

    /**
     * Checks whether classes of a given mod id are already on the classpath.
     *
     * There's a special case for the Kotlin mod in a development environment.
     * If Kotlin is already set up in the app class loader (e.g. because a Gradle dependency for it exists),
     * then we must not load the Kotlin mod in the mod class loader because that will lead to two instances
     * of Kotlin coexisting, which will blow up the moment they need to interact (e.g. when a Kotlin-using lib
     * which is only present in the app class loader is accessed from the mod class loader).
     */
    private boolean isAlreadyOnClasspath(String modId) {
        String cls = null;
        switch (modId) {
            case "org_jetbrains_kotlin_kotlin-stdlib": cls = "kotlin/Unit.class"; break;
            case "org_jetbrains_kotlin_kotlin-stdlib-jdk7": cls = "kotlin/jdk7/AutoCloseableKt.class"; break;
            case "org_jetbrains_kotlin_kotlin-stdlib-jdk8": cls = "kotlin/jvm/jdk8/JvmRepeatableKt.class"; break;
            case "org_jetbrains_kotlin_kotlin-reflect": cls = "kotlin/reflect/jvm/KTypesJvm.class"; break;
            case "org_jetbrains_kotlinx_kotlinx-coroutines-core-jvm": cls = "kotlinx/coroutines/Job.class"; break;
            case "org_jetbrains_kotlinx_kotlinx-coroutines-jdk8": cls = "kotlinx/coroutines/future/FutureKt.class"; break;
            case "org_jetbrains_kotlinx_kotlinx-serialization-core-jvm": cls = "kotlinx/serialization/Serializer.class"; break;
            case "org_jetbrains_kotlinx_kotlinx-serialization-json-jvm": cls = "kotlinx/serialization/json/Json.class"; break;
            case "org_jetbrains_kotlinx_kotlinx-serialization-cbor-jvm": cls = "kotlinx/serialization/cbor/Cbor.class"; break;
        }
        return cls != null && getClass().getClassLoader().getResource(cls) != null;
    }

    /**
     * Extracts inner jars defined in the given fabric.mod.json file from the given outer jar.
     */
    private List<Path> extractInnerJars(Path outerJar, FabricModJson fabricModJson) throws IOException {
        if (fabricModJson.getJars().isEmpty()) {
            // there's nothing to load, don't even need to open the jar
            return Collections.emptyList();
        }

        final List<Path> extractedJars = new ArrayList<>();

        try (FileSystem fileSystem = FileSystems.newFileSystem(outerJar, (ClassLoader) null)) {
            for (FabricModJson.Jar jarInfo : fabricModJson.getJars()) {
                Path innerJar = fileSystem.getPath(jarInfo.getFile());
                // For now, we'll assume that the file name is sufficiently unique of an identifier
                final Path extractedJar = extractedJarsRoot.resolve(innerJar.getFileName().toString());
                if (Files.exists(extractedJar)) {
                    LOGGER.debug("Already extracted: {}", innerJar);
                } else {
                    LOGGER.debug("Extracting {} from {} to {}", innerJar, outerJar, extractedJar);
                    // Copy to tmp jar first, so we do not leave behind incomplete jars
                    final Path tmpJar = Files.createTempFile(extractedJarsRoot, "tmp", ".jar");
                    Files.copy(innerJar, tmpJar, StandardCopyOption.REPLACE_EXISTING);
                    // Then (if successful) perform an atomic rename
                    Files.move(tmpJar, extractedJar, StandardCopyOption.ATOMIC_MOVE);
                }
                // Store the extracted path for later
                extractedJars.add(extractedJar);
            }
        }

        return extractedJars;
    }

    /**
     * Applies updates to the synthetic "Essential Dependencies" mod (if required).
     * @return {@code true} if the game can continue booting,
     *         {@code false} if it needs to be restarted for updates to take effect
     */
    public boolean complete() {
        if (updates.isEmpty()) {
            // No updates required, good to go
            return true;
        }

        // Time to update the synthetic mod
        try {
            updateSyntheticMod();
        } catch (IOException e) {
            throw new RuntimeException("Error updating Essential Dependencies mod", e);
        }

        // We've updated the dependencies jar, a restart is required for changes to take effect
        return false;
    }

    /**
     * Returns a list of mods which should be disabled after the game has quit.
     * @see #modsToDisable
     */
    public List<Path> getModsToDisable() {
        return modsToDisable;
    }

    /**
     * Returns a list containing the display name for every mod which has been updated.
     */
    public List<String> getUpdatedModNames() {
        return updatedModNames;
    }

    private void updateSyntheticMod() throws IOException {
        FabricLoader fabricLoader = FabricLoader.getInstance();
        Path modsFolder = getModsFolder(fabricLoader).toRealPath();
        Path syntheticModPath = modsFolder.resolve(SYNTHETIC_MOD_FILE_NAME);
        LOGGER.debug("Updating synthetic essential-dependencies mod at {}", syntheticModPath);

        // if it doesn't yet exist, we need to create it first
        if (Files.notExists(syntheticModPath)) {
            createEmptyJar(syntheticModPath);
        }

        try (SyntheticModJar syntheticModJar = new SyntheticModJar(syntheticModPath, SYNTHETIC_MOD_ID, SYNTHETIC_MOD_NAME)) {
            // First, clean up bundled mods which aren't being used
            for (SyntheticModJar.InnerJar innerJar : syntheticModJar.getInnerJars()) {
                String modId = innerJar.getId();
                SemanticVersion modVersion;
                try {
                    modVersion = SemanticVersion.parse(innerJar.getVersion());
                } catch (VersionParsingException e) {
                    // This usually shouldn't happen cause we only package stuff which we already parsed
                    LOGGER.error("Failed to parse version of \"" + innerJar + "\" in " + syntheticModPath, e);
                    // but if it does anyway, let's just clean it up
                    syntheticModJar.removeInnerJar(innerJar);
                    continue;
                }

                // Check if we've got an update for this mod
                if (updates.containsKey(modId)) {
                    // if so, we can delete the old version
                    LOGGER.debug("Removing {}, update scheduled", innerJar);
                    syntheticModJar.removeInnerJar(innerJar);
                    continue;
                }

                // Check if that jar is the one which is currently loaded (in which case we need to keep it)
                ModContainer loadedMod = fabricLoader.getModContainer(modId).orElse(null);
                if (loadedMod == null) {
                    // not loaded at all? this shouldn't normally happen
                    LOGGER.warn("Found {} in synthetic dependencies jar but it was not loaded?", innerJar);
                    syntheticModJar.removeInnerJar(innerJar);
                    continue;
                }
                Version loadedVersion = loadedMod.getMetadata().getVersion();
                if (!loadedVersion.equals(modVersion)) {
                    // this version is not being used, we can remove it
                    LOGGER.debug("Removing {}, appears to be unused", innerJar);
                    continue;
                }

                // this version is in use, keep it
                LOGGER.debug("Keeping {}, currently in use", innerJar);
            }

            // Then add all the new stuff
            for (Map.Entry<String, Path> update : updates.entrySet()) {
                String modId = update.getKey();
                Path sourcePath = update.getValue();

                if (NEED_TO_DISABLE_USER_MODS) {
                    // We're on an old version of fabric-loader and need to disable the corresponding user-installed
                    // mod if there is one.
                    fabricLoader.getModContainer(modId).ifPresent(mod -> {
                        Path path = mod.getRootPath();
                        try {
                            if (path.isAbsolute() && path.getParent() == null && "zipfs".equals(Files.getFileStore(path).type())) {
                                // This is the root path in a ZIP file system.
                                // Could not find any nicer way to get the containing zip file :(
                                path = FileSystems.getDefault().getPath(path.getFileSystem().toString());
                            }
                        } catch (IOException e) {
                            LOGGER.error("Failed to resolve origin of " + mod.getMetadata().getId(), e);
                            return;
                        }
                        if (!Files.isRegularFile(path)) {
                            LOGGER.error("Origin of {} is not a regular file: {}", mod.getMetadata().getId(), path);
                            return;
                        }

                        LOGGER.info("Disabling outdated {} at {}", modId, path);
                        modsToDisable.add(path);
                    });
                }

                LOGGER.debug("Adding {} from {}", modId, sourcePath);
                syntheticModJar.addInnerJar(sourcePath);
            }
        }

        LOGGER.debug("Synthetic essential-dependencies jar updated.");
    }

    private static Path getModsFolder(FabricLoader fabricLoader) {
        String modsFolder = System.getProperty("fabric.modsFolder");
        return modsFolder != null ? Paths.get(modsFolder) : fabricLoader.getGameDir().resolve("mods");
    }

    private static void createEmptyJar(Path path) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            new ZipOutputStream(out).close();
        }
    }
}
