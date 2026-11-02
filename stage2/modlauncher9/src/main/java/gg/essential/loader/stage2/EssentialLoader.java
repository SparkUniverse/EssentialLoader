package gg.essential.loader.stage2;

import cpw.mods.modlauncher.api.ITransformationService;
import gg.essential.loader.stage2.components.ForkedRestartUI;
import gg.essential.loader.stage2.components.RestartUI;
import gg.essential.loader.stage2.jvm.ForkedJvmLoaderSwingUI;
import net.minecraftforge.fml.loading.FMLLoader;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The initial entrypoint for stage2. With ModLauncher, we cannot yet know the MC version at this point, so this is a
 * dummy class, not the actual loader, which gets initialized later by the transformationService once the MC version is
 * available.
 */
public class EssentialLoader {
    private final EssentialTransformationService transformationService;

    @SuppressWarnings("unused") // called via reflection from stage1
    public EssentialLoader(Path gameDir, String fakeGameVersion) {
        this.transformationService = new EssentialTransformationService(gameDir);
    }

    @SuppressWarnings("unused") // called via reflection from stage1
    public ITransformationService getTransformationService() {
        return this.transformationService;
    }

    @SuppressWarnings("unused") // called via reflection from stage1
    public void load() {
        // delayed until ModLauncher exposes the MC version
    }

    @SuppressWarnings("unused") // called via reflection from stage1
    public void loadFromMixin(Path gameDir) throws Exception {
        final Path modsDir = gameDir.resolve("mods");
        LoaderUI ui = LoaderUI.all(
                new LoaderLoggingUI().updatesEveryMillis(1000),
                new ForkedJvmLoaderSwingUI().updatesEveryMillis(1000 / 60)
        );
        ui.start();
        try {
            DedicatedJarLoader.downloadDedicatedJar(ui, modsDir, "forge_" + FMLLoader.versionInfo().mcVersion());
        } finally {
            ui.complete();
        }
        List<URL> modNameMarkers = Collections.list(this.getClass().getClassLoader().getResources("META-INF/essential-loader-mod-name.txt"));
        List<String> modNames = new ArrayList<>();
        for (URL url : modNameMarkers) {
            String modName = Files.readString(Paths.get(url.toURI()));
            modNames.add(modName);
        }
        if (modNames.isEmpty()) {
            modNames = List.of("Unknown");
        }
        ForkedRestartUI restartUI = new ForkedRestartUI(modNames);
        restartUI.show();
        restartUI.waitForClose();
        System.exit(0);
    }
}
