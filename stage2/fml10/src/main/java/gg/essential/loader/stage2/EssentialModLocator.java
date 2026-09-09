package gg.essential.loader.stage2;

import gg.essential.loader.stage2.data.ModJarMetadata;
import gg.essential.loader.stage2.util.SelfUpdateHelper;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static gg.essential.loader.stage2.util.SelfUpdateHelper.LOADED_STAGE2_VERSION;
import static gg.essential.loader.stage2.util.SelfUpdateHelper.STAGE1_RESOURCE;
import static gg.essential.loader.stage2.util.SelfUpdateHelper.STAGE2_PKG;

public class EssentialModLocator implements IModFileCandidateLocator {
    private static final Logger LOGGER = LogManager.getLogger(EssentialModLocator.class);

    private static final String STAGE2_CLS = STAGE2_PKG + "EssentialModLocator";

    static {
        LOGGER.info("Running Essential Loader v{}", LOADED_STAGE2_VERSION);
        System.setProperty("essential.stage2.version", LOADED_STAGE2_VERSION);
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        List<Path> jars = load(context.gameDirectory(), context.getVersions().mcVersion());

        Path newLoaderJar = checkForNewerLoader(jars);
        if (newLoaderJar != null) {
            delegateToNewerLoader(newLoaderJar, context, pipeline);
            return;
        }

        for (Path jar : jars) {
            pipeline.addPath(jar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
        }
    }

    private List<Path> load(Path gameDirectory, String mcVersion) {
        // Check if the mod jar we were loaded from is a full Essential jar (i.e. dev jar dropped in the `mods` folder)
        // in which case we skip regular loading and just use that jar directly.
        if (getClass().getClassLoader().getResource(EssentialLoaderBase.CLASS_NAME.replace('.', '/') + ".class") != null) {
            LOGGER.warn("Essential loaded as a regular mod. No automatic updates will be applied.");
            return List.of(loadDevModJar());
        }

        List<Path> result = new ArrayList<>();
        EssentialLoaderBase loader = new EssentialLoaderBase(gameDirectory, "neoforge_" + mcVersion) {
            @Override
            protected void loadPlatform() {
            }

            @Nullable
            @Override
            protected ClassLoader getModClassLoader() {
                return null;
            }

            @Override
            protected void addToClasspath(Mod mod, ModJarMetadata jarMeta, Path jar) {
                result.add(jar);
            }

            @Override
            protected void addToClasspath(Path path) {
                throw new AssertionError("Should never be called with above override.");
            }

            @Override
            protected String getRequiredStage2VersionIfOutdated(Path modFile) {
                // we support in-place loader upgrades, we never need to prompt the user for a restart
                // our stage1 also can't auto-update stage2, so simply restarting wouldn't have worked anyway
                return null;
            }
        };
        try {
            loader.load();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private Path checkForNewerLoader(List<Path> jars) {
        SelfUpdateHelper selfUpdateHelper = new SelfUpdateHelper();
        for (Path jar : jars) {
            try (FileSystem fileSystem = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
                Path stage1Path = fileSystem.getPath(STAGE1_RESOURCE);
                if (Files.exists(stage1Path)) {
                    selfUpdateHelper.checkForNewerLoaderInStage1(stage1Path);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return selfUpdateHelper.newerLoaderJar();
    }

    private void delegateToNewerLoader(Path newLoaderJar, ILaunchContext context, IDiscoveryPipeline pipeline) {
        URL url;
        try {
            url = newLoaderJar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e); // should never happen because `path` is a simple temporary file
        }
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{url}, getClass().getClassLoader().getParent())) {
            IModFileCandidateLocator newLoader = (IModFileCandidateLocator) Class.forName(STAGE2_CLS, true, classLoader)
                .getConstructor()
                .newInstance();

            newLoader.findCandidates(context, pipeline);
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path loadDevModJar() {
        // This is somewhat fragile, but this code path should only be used with dev Essential jars, so it's fine
        Path jar;
        try {
            Class<?> stage0Class = Class.forName("gg.essential.loader.stage0.EssentialModLocator", false, getClass().getClassLoader());
            jar = Path.of(stage0Class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        // Need to make a copy though, otherwise NeoForge will skip it because it's already loaded the ModLocator from it
        String fileName = jar.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        Path copy;
        try {
            copy = Files.createTempFile(baseName, ".jar");
            Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return copy;
    }
}
