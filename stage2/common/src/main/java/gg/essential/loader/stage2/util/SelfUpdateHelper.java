package gg.essential.loader.stage2.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import static gg.essential.loader.stage2.util.VersionComparison.compareVersions;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class SelfUpdateHelper {
    private static final Logger LOGGER = LogManager.getLogger(SelfUpdateHelper.class);

    public static final String STAGE1_RESOURCE = "gg/essential/loader/stage0/stage1.jar";
    public static final String STAGE2_RESOURCE = "gg/essential/loader/stage1/stage2.jar";

    public static final String STAGE1_PKG = "gg.essential.loader.stage1.";
    public static final String STAGE1_PKG_PATH = STAGE1_PKG.replace('.', '/');

    public static final String STAGE2_PKG = "gg.essential.loader.stage2.";
    public static final String STAGE2_PKG_PATH = STAGE2_PKG.replace('.', '/');

    public static final String LOADED_STAGE2_VERSION;

    static {
        // Note: Cannot just use `Loader.class.getPackage().getImplementationVersion()` because LaunchWrapper does not
        //       properly handle packages, we'll just get a dummy package with `null` version.
        Path loadedPath;
        try {
            loadedPath = Paths.get(SelfUpdateHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        LOADED_STAGE2_VERSION = readStage2Version(loadedPath);
    }

    /** Path to a more recent loader jar file if one was found during discovery. */
    private Path newerLoaderJar;
    private String latestLoaderJarVersion = LOADED_STAGE2_VERSION;

    public Path newerLoaderJar() {
        return newerLoaderJar;
    }

    private int latestStage1Version;
    public void checkForNewerLoaderInStage1(Path stage1Jar) throws IOException {
        int stage1Version = readStage1Version(stage1Jar);
        if (stage1Version <= latestStage1Version) {
            return; // stage1 jar is older than what we've previously tried, not even worth looking at embedded stage2
        }
        latestStage1Version = stage1Version;

        // ZipFileSystem doesn't support nested jars, so we need to extract it to a temporary file
        Path tmpStage1Jar = Files.createTempFile("essential-loader-stage1-", ".jar");
        try {
            Files.copy(stage1Jar, tmpStage1Jar, REPLACE_EXISTING);

            try (FileSystem fileSystem = FileSystems.newFileSystem(tmpStage1Jar, (ClassLoader) null)) {
                Path stage2Path = fileSystem.getPath(STAGE2_RESOURCE);
                if (!Files.exists(stage2Path)) return;
                checkForNewerLoader(stage2Path);
            }
        } finally {
            Files.delete(tmpStage1Jar);
        }
    }

    private void checkForNewerLoader(Path stage2Jar) throws IOException {
        String version = readStage2Version(stage2Jar);
        if (version == null) return;

        if (compareVersions(version, latestLoaderJarVersion) <= 0) {
            return; // given jar isn't an upgrade, nothing to do
        }

        // Copy to temporary file, because we don't know how long the given path will remain valid for
        Path copiedJar = Files.createTempFile("essential-loader-stage2-", ".jar");
        copiedJar.toFile().deleteOnExit();
        Files.copy(stage2Jar, copiedJar, REPLACE_EXISTING);

        newerLoaderJar = copiedJar;
        latestLoaderJarVersion = version;
    }

    private static int readStage1Version(Path path) {
        String str = readImplementationVersion(path, STAGE1_PKG_PATH);
        if (str == null) return -1;
        try {
            return Integer.parseInt(str);
        } catch (Exception e) {
            LOGGER.error("Failed to parse version from " + path, e);
            return -1;
        }
    }

    private static String readStage2Version(Path path) {
        return readImplementationVersion(path, STAGE2_PKG_PATH);
    }

    private static String readImplementationVersion(Path jar, String name) {
        try (InputStream rawIn = Files.newInputStream(jar);
             JarInputStream in = new JarInputStream(rawIn, false)) {
            Manifest manifest = in.getManifest();
            if (manifest == null) {
                return null;
            }
            Attributes attributes = manifest.getMainAttributes();
            if (!name.equals(attributes.getValue("Name"))) {
                return null;
            }
            return attributes.getValue("Implementation-Version");
        } catch (Exception e) {
            LOGGER.error("Failed to read implementation version from " + jar, e);
            return null;
        }
    }
}
