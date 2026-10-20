package gg.essential.loader.stage2.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.SecureJar;
import gg.essential.loader.stage2.DescriptorRewritingJarMetadata;
import gg.essential.loader.stage2.modlauncher.CompatibilityLayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * We need to inject our bundled Kotlin library files into the existing KotlinForForge jar instead of injecting
 * them like a regular library mod.
 * This is necessary because KFF cannot use JiJ itself (on older versions it didn't exist and on newer ones it's
 * still broken for language plugins: https://github.com/MinecraftForge/MinecraftForge/issues/8878) and therefore
 * explodes the Kotlin libraries into its jar, so we can't easily override them with another regular jar without
 * also overriding KFF itself.
 * So instead this method fake-explodes our usually more up-to-date Kotlin libs into the KFF UnionFileSystem.
 * Kind of like what we do for LaunchWrapper versions, just with a bunch of Unsafe and a moving target.
 */
public class KFFMerger {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Pattern JIJ_KOTLIN_FILES = Pattern.compile("kotlinx?-([a-z0-9-]+)-(\\d+\\.\\d+\\.\\d+)\\.jar");
    private static final byte[] EMPTY_ZIP = new byte[] {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    };

    private static class Libraries {
        private final String name;
        private final List<SecureJar> jars = new ArrayList<>();
        private int version;

        private Libraries(String name) {
            this.name = name;
        }

        public void maybeUpgrade(List<Path> injectedJars, int theirVersion) {
            if (theirVersion < this.version) {
                LOGGER.info("Found outdated {} libs {} (we ship {})",
                    name, versionStr(theirVersion), versionStr(this.version));
                for (SecureJar jar : jars) {
                    injectedJars.add(jar.getRootPath());
                }
            } else {
                LOGGER.info("Found up-to-date {} libs {} (we ship {})",
                    name, versionStr(theirVersion), versionStr(this.version));
            }
        }
    }

    private final CompatibilityLayer compatibilityLayer;
    private final Libraries ourCoreJars = new Libraries("Kotlin core");
    private final Libraries ourCoroutinesJars = new Libraries("Kotlin Coroutines");
    private final Libraries ourSerializationJars = new Libraries("Kotlin Serialization");

    public KFFMerger(CompatibilityLayer compatibilityLayer) {
        this.compatibilityLayer = compatibilityLayer;
    }

    public boolean addKotlinJar(Path sourceFile, SecureJar secureJar) {
        String fileName = sourceFile.getFileName().toString();
        Matcher matcher = JIJ_KOTLIN_FILES.matcher(fileName);
        if (!matcher.matches()) {
            return false;
        }

        Libraries libraries = switch (matcher.group(1)) {
            case "stdlib", "stdlib-common", "stdlib-jdk7", "stdlib-jdk8", "reflect" -> ourCoreJars;
            case "coroutines-core-jvm", "coroutines-jdk8" -> ourCoroutinesJars;
            case "serialization-core-jvm", "serialization-json-jvm" -> ourSerializationJars;
            default -> null;
        };
        if (libraries == null) {
            LOGGER.warn("Do not know how to classify {}, will inject it as a regular lib.", fileName);
            return false;
        }

        int version = version(matcher.group(2));
        if (libraries.version != 0 && libraries.version != version) {
            LOGGER.warn("Conflicting version for {}:\nExisting ({}): {}\nNew ({}): {}",
                libraries, versionStr(libraries.version), libraries.jars.get(0), versionStr(version), secureJar);
        }

        libraries.jars.add(secureJar);
        libraries.version = version;

        return true;
    }

    public void apply(List<Object> pathOrJarList) {
        // Nothing to merge (older Essential version), nothing to do
        if (ourCoreJars.jars.isEmpty()) {
            return;
        }

        boolean didMergeIntoExistingMod = false;
        ListIterator<Object> iter = pathOrJarList.listIterator();
        while (iter.hasNext()) {
            SecureJar jar = PathOrJarAccessor.getJar(iter.next());
            if (jar == null) continue;

            SecureJar newJar = maybeMergeInto(jar);
            if (newJar != null) {
                iter.set(PathOrJarAccessor.from(newJar));
                didMergeIntoExistingMod = true;
                // continue, in case there's multiple KFF versions
            }
        }

        if (didMergeIntoExistingMod) {
            return;
        }

        // No KFF (or KFF 5+ which is using JarJar), so we can simply add all our Kotlin libs to the classpath directly.
        addKotlinLibsToPathOrJarList(pathOrJarList);
    }

    public SecureJar maybeMergeInto(SecureJar secureJar) {
        // Only care about a jar if it contains a Kotlin we can overwrite
        if (!compatibilityLayer.getPackages(secureJar).contains("kotlin")) {
            return null;
        }

        LOGGER.info("Found Kotlin-containing mod {}, checking whether we need to upgrade it..", secureJar);

        Path rootPath = secureJar.getRootPath();
        int theirCoreVersion = detectKotlinCoreVersion(secureJar, rootPath);
        int theirCoroutinesVersion = detectKotlinCoroutinesVersion(secureJar, rootPath);
        // There doesn't seem to be any way to determine their kotlinx-serialization version, so we'll just
        // assume that it's outdated when either of the other two are. That means we won't be able to update
        // just the serialization lib by itself but that should hopefully not come up much.
        boolean updateCore = theirCoreVersion < ourCoreJars.version;
        boolean updateCoroutines = theirCoroutinesVersion < ourCoroutinesJars.version;
        int theirSerializationVersion = updateCore || updateCoroutines ? 0 : ourSerializationJars.version;

        // If the jar contains only core but not coroutine libs, then it's not the fat KFF jar but rather KFF is
        // using JarJar, and we should be able to find that one later.
        if (theirCoreVersion != 0 && theirCoroutinesVersion == 0) {
            LOGGER.info("Looks like a standalone Kotlin jar. Keeping as is.");
            return null;
        }

        List<Path> injectedJars = new ArrayList<>();
        ourCoreJars.maybeUpgrade(injectedJars, theirCoreVersion);
        ourCoroutinesJars.maybeUpgrade(injectedJars, theirCoroutinesVersion);
        ourSerializationJars.maybeUpgrade(injectedJars, theirSerializationVersion);

        if (injectedJars.isEmpty()) {
            LOGGER.info("All good, no update needed: {}", secureJar);
            return null; // all up-to-date, nothing to do
        }

        try {
            JarMetadata orgMeta = compatibilityLayer.getJarMetadata(secureJar, secureJar.getPrimaryPath());

            Path tmpFile = Files.createTempFile("kff-updated-kotlin-", "-" + orgMeta.version() + ".jar");
            Files.write(tmpFile, EMPTY_ZIP);

            LOGGER.info("Generating jar with updated Kotlin at {}", tmpFile);

            // We'll skip files we've already seen, so the original jar goes last
            injectedJars.add(secureJar.getRootPath());

            try (FileSystem destFileSystem = FileSystems.newFileSystem(tmpFile)) {
                Set<String> seen = new HashSet<>();
                seen.add("");
                for (Path sourceRoot : injectedJars) {
                    try (Stream<Path> stream = Files.walk(sourceRoot)) {
                        for (Path sourcePath : stream.toList()) {
                            String relativePath = sourceRoot.relativize(sourcePath).toString();
                            if (!seen.add(relativePath)) {
                                continue;
                            }
                            Path destinationPath = destFileSystem.getPath(relativePath);
                            if (Files.isDirectory(sourcePath)) {
                                Files.createDirectory(destinationPath);
                            } else {
                                Files.copy(sourcePath, destinationPath);
                            }
                        }
                    }
                }

                // Special case, need the manifest from the original for Forge to properly load the file
                Path sourceManifest = secureJar.getRootPath().resolve("META-INF").resolve("MANIFEST.MF");
                Path destinationManifest = destFileSystem.getPath("META-INF", "MANIFEST.MF");
                if (Files.exists(sourceManifest)) {
                    Files.copy(sourceManifest, destinationManifest, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            return compatibilityLayer.newSecureJarWithCustomMetadata((__, newMeta) -> new DescriptorRewritingJarMetadata(orgMeta, newMeta) {
                @Override
                public String name() {
                    // Call the original name from the original SecureJar to allow SelfRenamingJarMetadata to function
                    return secureJar.name();
                }
            }, tmpFile);
        } catch (Throwable t) {
            LOGGER.fatal("Failed to merge updated Kotlin into " + secureJar + ":", t);
            return null; // oh well, guess we'll give it a try as is
        }
    }

    private void addKotlinLibsToPathOrJarList(List<Object> pathOrJarList) {
        addToPathOrJarList(pathOrJarList, ourCoreJars.jars);
        addToPathOrJarList(pathOrJarList, ourCoroutinesJars.jars);
        addToPathOrJarList(pathOrJarList, ourSerializationJars.jars);
    }

    private void addToPathOrJarList(List<Object> pathOrJarList, List<SecureJar> jarsToBeAdded) {
        for (SecureJar jar : jarsToBeAdded) {
            Object pathOrJar = PathOrJarAccessor.from(jar);
            if (pathOrJar == null) continue;
            pathOrJarList.add(pathOrJar);
        }
    }

    public static boolean isJarJarKff(SecureJar jar) {
        try {
            Path jarjarPath = jar.getRootPath().resolve("META-INF").resolve("jarjar").resolve("metadata.json");
            if (!Files.exists(jarjarPath)) return false;
            JsonObject root = new Gson().fromJson(Files.readString(jarjarPath), JsonObject.class);
            JsonArray jars = root.getAsJsonArray("jars");
            Set<String> artifactIds = StreamSupport.stream(jars.spliterator(), false)
                .map(it -> it.getAsJsonObject().getAsJsonObject("identifier"))
                .map(it -> it.getAsJsonPrimitive("group").getAsString() + ":" + it.getAsJsonPrimitive("artifact").getAsString())
                .collect(Collectors.toSet());
            // A JarJar-using KotlinForForge jar can be recognized by the fact that it bundles both the KFF mod as
            // well as the Kotlin Standard Library
            return artifactIds.contains("thedarkcolour:kffmod") && artifactIds.contains("org.jetbrains.kotlin:kotlin-stdlib");
        } catch (Throwable t) {
            LOGGER.error("Failed to determine version of potential KFF jar at " + jar + ":", t);
            return false;
        }
    }

    private int detectKotlinCoreVersion(SecureJar jar, Path root) {
        try {
            if (Files.notExists(root.resolve("kotlin").resolve("KotlinVersion.class"))) {
                return 0; // this is one of our slim jars, always consider it outdated
            }
            URL url = root.toUri().toURL();
            if (url.getProtocol().equals("jar") && url.getPath().endsWith("!/")) {
                // Forge 1.20.4 SecureJars aren't wrapped in the "union" file system.
                // Their URLs take the form jar:file:/some/path/to/archive.jar!/
                url = new URL(url.getPath().substring(0, url.getPath().length() - 2));
                // Forge 26.1 uses a new custom `roimfs` ("Read-only in-memory file system") for nested jars.
                // Their URLs take the form jar:roimfs:jar-jar-1/kotlin-stdlib-2.4.0.jar!/
                // We've stripped the outer `jar` above, so we can detect them by their `roimfs` protocol.
                if (url.getProtocol().equals("roimfs")) {
                    // Their `roimfs` implementation is incompatible with URLClassPath, so we need to create a temporary
                    // real-file copy to use instead.
                    Path copy = Files.createTempFile(null, ".jar");
                    try {
                        Files.copy(Path.of(url.toURI()), copy, StandardCopyOption.REPLACE_EXISTING);
                        return detectKotlinCoreVersion(copy.toUri().toURL());
                    } finally {
                        Files.deleteIfExists(copy);
                    }
                }
            } else {
                // Otherwise, assume it's a union jar and use the installed protocol handler to read it
                url = new URL(url.getProtocol(), url.getHost(), url.getFile() + "/");
            }
            return detectKotlinCoreVersion(url);
        } catch (Throwable t) {
            LOGGER.error("Failed to determine Kotlin Core version in " + jar + ":", t);
            return 0;
        }
    }

    private int detectKotlinCoreVersion(URL url) throws Throwable {
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{url})) {
            Class<?> kotlinVersionClass = classLoader.loadClass("kotlin.KotlinVersion");
            Field currentField = kotlinVersionClass.getDeclaredField("CURRENT");
            Object kotlinVersion = currentField.get(null);
            int major = (int) kotlinVersionClass.getDeclaredMethod("getMajor").invoke(kotlinVersion);
            int minor = (int) kotlinVersionClass.getDeclaredMethod("getMinor").invoke(kotlinVersion);
            int patch = (int) kotlinVersionClass.getDeclaredMethod("getPatch").invoke(kotlinVersion);
            return version(major, minor, patch);
        }
    }

    private int detectKotlinCoroutinesVersion(SecureJar jar, Path root) {
        try {
            Path versionFile = root.resolve("META-INF").resolve("kotlinx_coroutines_core.version");
            if (Files.notExists(versionFile)) {
                // Coroutines prior to 1.6.0 (e.g. as in KFF 2.2.0) did not yet have the version file:
                // https://github.com/Kotlin/kotlinx.coroutines/commit/5c75e7ad58337dcce68b00f5f7e40bfc0108dce7
                // There's no way to know their exact version, but we don't really need to either because it'll always
                // be older than what we ship, so any old non-zero version will do.
                if (Files.exists(root.resolve("kotlinx").resolve("coroutines").resolve("Job.class"))) {
                    return version(1, 0, 0);
                }
                return 0; // this is one of our slim jars, always consider it outdated
            }
            return version(Files.readString(versionFile));
        } catch (Throwable t) {
            LOGGER.error("Failed to determine Kotlin Coroutines version in " + jar + ":", t);
            return 0;
        }
    }

    private static int version(String str) {
        String[] parts = str.trim().split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);
        return version(major, minor, patch);
    }

    private static int version(int major, int minor, int patch) {
        return (major << 16) | (minor << 8) | patch;
    }

    private static String versionStr(int version) {
        return versionStr(version >> 16, (version >> 8) & 0xff, version & 0xff);
    }

    private static String versionStr(int major, int minor, int patch) {
        return major + "." + minor + "." + patch;
    }
}
