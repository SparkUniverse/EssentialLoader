package gg.essential.loader.stage0;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static gg.essential.loader.stage0.EssentialLoader.STAGE1_PKG;

@SuppressWarnings("unused")
public class EssentialStage0MixinPlugin implements IMixinConfigPlugin {
    private static final String STAGE1_CLS = STAGE1_PKG + "EssentialMixinPluginLoader";

    public EssentialStage0MixinPlugin() throws Exception {
        loadStage1(this);
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    private static void loadStage1(Object stage0) throws Exception {
        Path gameDir = Launcher.INSTANCE.environment()
                .getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElseGet(() -> Paths.get("."));

        final EssentialLoader loader = new EssentialLoader("modlauncher"); // using same variant as the transformation service
        final Path stage1File = loader.loadStage1File(gameDir);
        final URL stage1Url = stage1File.toUri().toURL();

        // Create a class loader with which to load stage1
        URLClassLoader classLoader = new URLClassLoader(new URL[]{ stage1Url }, stage0.getClass().getClassLoader());

        Class.forName(STAGE1_CLS, true, classLoader)
                .getConstructor()
                .newInstance();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }
}
