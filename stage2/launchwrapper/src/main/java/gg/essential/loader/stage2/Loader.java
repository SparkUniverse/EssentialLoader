package gg.essential.loader.stage2;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import gg.essential.loader.stage2.relaunch.Relaunch;
import gg.essential.loader.stage2.util.MixinExtrasExtractor;
import gg.essential.loader.stage2.util.SelfUpdateHelper;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import static gg.essential.loader.stage2.util.SelfUpdateHelper.LOADED_STAGE2_VERSION;
import static gg.essential.loader.stage2.util.SelfUpdateHelper.STAGE1_RESOURCE;
import static gg.essential.loader.stage2.util.SelfUpdateHelper.STAGE2_PKG;
import static gg.essential.loader.stage2.util.VersionComparison.compareVersions;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class Loader {
    private static final Logger LOGGER = LogManager.getLogger(Loader.class);
    private static final Gson GSON = new Gson();

    private static final String ESSENTIAL_MOD_JSON = "essential.mod.json";

    private static final String STAGE2_CLS = STAGE2_PKG + "EssentialSetupTweaker";

    static {
        LOGGER.info("Running Essential Loader v{}", LOADED_STAGE2_VERSION);
        System.setProperty("essential.stage2.version", LOADED_STAGE2_VERSION);
    }

    private final Path minecraftHome = (Launch.minecraftHome != null ? Launch.minecraftHome : new File("."))
        .toPath()
        .toAbsolutePath();

    private final SelfUpdateHelper selfUpdateHelper = new SelfUpdateHelper();

    public void loadAndRelaunch() {
        List<JarInfo> jars = load(Launch.classLoader.getSources());

        Path newerLoaderJar = selfUpdateHelper.newerLoaderJar();;
        if (newerLoaderJar != null) {
            relaunchViaNewerLoader(newerLoaderJar);
        }

        relaunch(jars);
    }

    private void relaunchViaNewerLoader(Path loaderJar) {
        URL url;
        try {
            url = loaderJar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e); // should never happen because `path` is a simple temporary file
        }
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{url}, Launch.classLoader)) {
            classLoader.loadClass(STAGE2_CLS)
                .getConstructor(ITweaker.class)
                .newInstance((ITweaker) null);
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException(e);
        }
        throw new AssertionError("should relaunch and never return");
    }

    private void relaunch(List<JarInfo> jars) {
        List<URL> loadedByForge = Launch.classLoader.getSources();

        RelaunchInfo relaunchInfo = new RelaunchInfo();
        relaunchInfo.loadedIds = jars.stream()
            .map(it -> it.id)
            .collect(Collectors.toSet());
        relaunchInfo.extraMods = jars.stream()
            .filter(it -> !loadedByForge.contains(it.url()))
            .map(it -> it.path.toAbsolutePath().toString())
            .collect(Collectors.toList());
        RelaunchInfo.put(relaunchInfo);

        Set<URL> priorityClassPath = new LinkedHashSet<>();
        // Put ourselves on the classpath so we don't have to go re-discover ourselves.
        // In particular this also puts our stage1 EssentialSetupTweaker on there, which shortcuts all the discovery by
        // directly loading this stage2.
        priorityClassPath.add(Loader.class.getProtectionDomain().getCodeSource().getLocation());
        for (JarInfo jarInfo : jars) {
            priorityClassPath.add(jarInfo.url());
        }

        Relaunch.relaunch(priorityClassPath);
        throw new AssertionError("relaunch should not return");
    }

    private List<JarInfo> load(Collection<URL> sources) {
        List<JarInfo> topLevel = new ArrayList<>();
        Map<String, JarInfo> allVersions = new LinkedHashMap<>();
        for (URL url : sources) {
            JarInfo jarInfo = load(url, allVersions);
            if (jarInfo != null) {
                topLevel.add(jarInfo);
            }
        }

        Map<String, JarInfo> latestJars = new HashMap<>();
        for (JarInfo info : allVersions.values()) {
            JarInfo latestInfo = latestJars.get(info.id);
            if (latestInfo == null || compareVersions(info.version, latestInfo.version) > 0) {
                latestJars.put(info.id, info);
            }
        }

        List<JarInfo> jars = new ArrayList<>(latestJars.values());
        jars.sort(Comparator.comparing(it -> it.id));

        // Special case: Old Essential includes various things directly in its jar, so we'll put it last so other mods
        //               which properly use nested jars can overwrite that.
        JarInfo essentialJarInfo = latestJars.get("essential");
        if (essentialJarInfo != null && essentialJarInfo.children.isEmpty()) {
            jars.remove(essentialJarInfo);
            jars.add(essentialJarInfo);
        }

        if (selfUpdateHelper.newerLoaderJar() == null) {
            Set<String> visited = new HashSet<>();
            StringBuilder sb = new StringBuilder();
            for (JarInfo jar : topLevel) {
                sb.append("  - ");
                prettyPrint(sb, "     ", jar, visited, latestJars);
            }
            LOGGER.info("Essential Loader discovered {} jars ({} unique jars):\n{}", allVersions.size(), latestJars.size(), sb.toString());
        }

        return jars;
    }

    private JarInfo load(URL url, Map<String, JarInfo> allVersions) {
        Path path;
        try {
            URI uri = url.toURI();
            if (!"file".equals(uri.getScheme())) {
                return null;
            }
            File file = new File(uri);
            if (!file.exists() || !file.isFile()) {
                return null;
            }
            path = file.toPath().toAbsolutePath();
        } catch (Exception e) {
            LOGGER.error("Failed to find path of {}:", url, e);
            return null;
        }

        return load(null, path, null, allVersions);
    }

    private JarInfo load(JarInfo parent, Path jar, JsonObject descriptor, Map<String, JarInfo> allVersions) {
        assert(jar.getFileSystem() == FileSystems.getDefault());
        assert(jar.isAbsolute());

        boolean hasStage1 = false;
        String embeddedMixinExtrasVersion;

        try (FileSystem fileSystem = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            Path modJsonPath = fileSystem.getPath(ESSENTIAL_MOD_JSON);
            if (Files.exists(modJsonPath)) {
                try (BufferedReader in = Files.newBufferedReader(modJsonPath)) {
                    descriptor = GSON.fromJson(in, JsonObject.class);
                }
            }

            Path stage1Path = fileSystem.getPath(STAGE1_RESOURCE);
            if (Files.exists(stage1Path)) {
                hasStage1 = true;
                selfUpdateHelper.checkForNewerLoaderInStage1(stage1Path);
            }

            embeddedMixinExtrasVersion = MixinExtrasExtractor.readMixinExtrasVersion(jar, fileSystem);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // If this mod doesn't have a essential.mod.json but does have stage1 jar, then it's likely a mod which used
        // Essential Loader before explicit dependencies via essential.mod.json became a thing when it was only loading
        // the Essential mod, so we need to synthesize a essential.mod.json file for it.
        // Special case being the raw stage0 file as it will appear when you depend on EssentialLoader in your
        // development environment, that one we want to just ignore.
        if (descriptor == null && hasStage1 && !isRawStage0(jar)) {
            descriptor = new JsonObject();
            // We know neither its id nor version, so we use dummy values
            descriptor.addProperty("id", guessId(jar));
            descriptor.addProperty("version", "[unknown version]");

            // These old mods always implicitly depend on the Essential mod, so load that as a dependency
            JsonArray jars = new JsonArray();
            JsonObject lib = new JsonObject();
            lib.addProperty("builtin", "essential");
            jars.add(lib);
            descriptor.add("jars", jars);
        }

        if (descriptor == null) {
            // This mod doesn't appear to be using Essential Loader

            // If it ships a MixinExtras though, we'll extract that and add it to our mods so we don't overwrite newer
            // MixinExtras versions with an old version shipped by another Essential Loader-using mod.
            if (embeddedMixinExtrasVersion != null) {
                try {
                    // We're adding a suffix to the version so that
                    // 1. it is clearer where it came from
                    // 2. if we have the same version as a proper Jar-in-Jar, we'll prefer using that
                    String version = embeddedMixinExtrasVersion
                        + ".from."
                        + jar.getFileName().toString().replaceAll("[^A-Za-z0-9]", "_");

                    Path extractedPath = Files.createTempFile("mixinextras-" + version + "-", ".jar");
                    extractedPath.toFile().deleteOnExit();

                    LOGGER.debug("Extracting MixinExtras from {} to {}", jar, extractedPath);
                    MixinExtrasExtractor.extractMixinExtras(jar, extractedPath, embeddedMixinExtrasVersion);

                    JarInfo info = new JarInfo();
                    info.path = extractedPath;
                    info.id = "io.github.llamalad7:mixinextras-common";
                    info.version = version;
                    if (parent != null) parent.children.add(info);
                    allVersions.put(info.id + ":" + version, info);
                    return info;
                } catch (Exception e) {
                    LOGGER.error("Failed to extract MixinExtras from {}", jar, e);
                }
            }
            return null;
        }

        JsonPrimitive schemaRevisionJson = descriptor.getAsJsonPrimitive("schemaRevision");
        int schemaRevision = schemaRevisionJson != null ? schemaRevisionJson.getAsInt() : 0;
        if (schemaRevision > 0) {
            // Unsupported schema revision
            // If we have a newer loader jar queued, that likely supports the newer revision, so let's not complain now.
            // If we don't though, then let's print a warning about it.
            if (selfUpdateHelper.newerLoaderJar() == null) {
                LOGGER.warn("Unsupported schema revision `{}` in `{}`. ", schemaRevision, jar);
            }
            return null;
        }

        String id = descriptor.getAsJsonPrimitive("id").getAsString();
        String version = descriptor.getAsJsonPrimitive("version").getAsString();

        String key = id + ":" + version;
        JarInfo info = allVersions.get(key);
        if (info != null) {
            if (parent != null) parent.children.add(info);
            return info;
        }

        info = new JarInfo();
        info.path = jar;
        info.id = id;
        info.version = version;
        if (parent != null) parent.children.add(info);
        allVersions.put(key, info);

        JsonElement jarsElement = descriptor.get("jars");
        if (jarsElement != null && jarsElement.isJsonArray()) {
            for (JsonElement jarElement : jarsElement.getAsJsonArray()) {
                if (!jarElement.isJsonObject()) continue;
                loadDependency(info, jarElement.getAsJsonObject(), allVersions);
            }
        }

        return info;
    }

    private void loadDependency(JarInfo outerJar, JsonObject spec, Map<String, JarInfo> allVersions) {
        JsonElement builtIn = spec.get("builtin");
        if (builtIn != null && builtIn.isJsonPrimitive() && "essential".equals(builtIn.getAsJsonPrimitive().getAsString())) {
            spec.addProperty("class", EssentialModUpdater.class.getName());
        }

        if (spec.has("class")) {
            String clsName = spec.getAsJsonPrimitive("class").getAsString();
            LOGGER.trace("Loading {} from {}", clsName, outerJar.path);
            String producedJson;
            try (URLClassLoader classLoader = new URLClassLoader(new URL[]{outerJar.path.toUri().toURL()}, getClass().getClassLoader())) {
                Supplier<String> supplier;
                try {
                    //noinspection unchecked
                    supplier = classLoader
                        .loadClass(clsName)
                        .asSubclass(Supplier.class)
                        .getConstructor()
                        .newInstance();
                } catch (ReflectiveOperationException e) {
                    LOGGER.error("Failed to load class `{}` from `{}`:", clsName, outerJar, e);
                    return;
                }
                producedJson = supplier.get();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            for (JsonElement libElement : GSON.fromJson(producedJson, JsonArray.class)) {
                loadDependency(outerJar, libElement.getAsJsonObject(), allVersions);
            }
            return;
        }

        if (spec.has("file")) {
            String file = spec.getAsJsonPrimitive("file").getAsString();
            Path path;
            try {
                path = FileSystems.getDefault().getPath(file);
                if (!path.isAbsolute()) {
                    path = null;
                }
            } catch (InvalidPathException e) {
                path = null;
            }
            if (path == null) {
                try (FileSystem fileSystem = FileSystems.newFileSystem(outerJar.path, (ClassLoader) null)) {
                    Path innerJar = fileSystem.getPath(file);
                    String name = innerJar.getFileName().toString();
                    int extension = name.lastIndexOf('.');
                    if (extension == -1) extension = name.length();
                    path = Files.createTempFile(name.substring(0, extension) + "-", name.substring(extension));
                    path.toFile().deleteOnExit();
                    LOGGER.debug("Extracting {} to {}", innerJar, path);
                    Files.copy(innerJar, path, REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            load(outerJar, path, spec, allVersions);
            return;
        }

        // Spec doesn't have any recognized keys
        // If we have a newer loader jar queued, it might just be that this version of the loader doesn't yet support
        // the spec, so let's not complain.
        // If we don't though, then the spec is probably wrong, so let's print a warning about it.
        if (selfUpdateHelper.newerLoaderJar() == null) {
            LOGGER.warn("Unsupported jar specification `{}` containing neither `file` nor `class` found in `{}`. ", spec, outerJar.path);
        }
    }

    private static boolean isRawStage0(Path jar) {
        // If this flag is set, then install the Essential mod in dev too
        if (Boolean.getBoolean("essential.loader.installEssentialMod")) {
            return false;
        }
        try (InputStream rawIn = Files.newInputStream(jar);
             JarInputStream in = new JarInputStream(rawIn, false)) {
            Manifest manifest = in.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            return "false".equals(attributes.getValue("ImplicitlyDependsOnEssential"));
        } catch (Exception e) {
            LOGGER.error("Failed to read manifest from " + jar, e);
            return false;
        }
    }

    private String guessId(Path jar) {
        try (FileSystem fileSystem = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            if (Files.exists(fileSystem.getPath("essential_container_marker.txt"))) {
                return "essential-container";
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String relPath = minecraftHome.relativize(jar).toString();
        if (!relPath.startsWith("..")) {
            return relPath;
        }

        return jar.toString();
    }

    private static void prettyPrint(StringBuilder sb, String childIndent, JarInfo jar, Set<String> visited, Map<String, JarInfo> latestVersions) {
        sb.append(jar.id).append(' ').append(jar.version);
        JarInfo latestVersion = latestVersions.get(jar.id);
        if (latestVersion != null && !Objects.equals(latestVersion.version, jar.version)) {
            sb.append(" -> ").append(latestVersion.version);
            sb.append('\n');
            return;
        }
        if (!visited.add(jar.id)) {
            sb.append(" (*)");
            sb.append('\n');
            return;
        }
        sb.append('\n');

        if (jar.children.isEmpty()) return;

        String midIndent = childIndent + "|    ";
        String lastIndent = childIndent + "     ";
        int lastIndex = jar.children.size() - 1;
        for (int i = 0; i <= lastIndex; i++) {
            sb.append(childIndent).append(i < lastIndex ? "|-- " : "\\-- ");
            prettyPrint(sb, i < lastIndex ? midIndent : lastIndent, jar.children.get(i), visited, latestVersions);
        }
    }

    private static class JarInfo {
        List<JarInfo> children = new ArrayList<>();

        Path path;

        String id;
        String version;

        URL url() {
            try {
                return path.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
