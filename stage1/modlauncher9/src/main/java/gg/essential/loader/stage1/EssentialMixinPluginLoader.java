package gg.essential.loader.stage1;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.TypesafeMap;

import java.nio.file.Path;
import java.nio.file.Paths;

import static gg.essential.loader.stage1.EssentialTransformationService.MC_VERSION;
import static gg.essential.loader.stage1.EssentialTransformationServiceBase.KEY_LOADED;

@SuppressWarnings("unused")
public class EssentialMixinPluginLoader {

    public EssentialMixinPluginLoader() throws Exception {
        // Check if stage 2 has already been loaded by a transformation service
        final TypesafeMap blackboard = Launcher.INSTANCE.blackboard();
        final TypesafeMap.Key<ITransformationService> LOADED =
                TypesafeMap.Key.getOrCreate(blackboard, KEY_LOADED, ITransformationService.class);
        if (blackboard.get(LOADED).isPresent()) {
            return;
        }

        final Path gameDir = Launcher.INSTANCE.environment()
                .getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElse(Paths.get("."));

        // variant and game version to match transformation service path
        EssentialLoader loader = EssentialLoader.getInstance("modlauncher9", "forge_" + MC_VERSION);
        loader.load(gameDir);

        loader.getStage2().getClass()
                .getMethod("loadFromMixin", Path.class)
                .invoke(loader.getStage2(), gameDir);
    }
}
