package gg.essential.loader.stage1;

import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.NamedPath;
import gg.essential.loader.stage1.util.FallbackTransformationService;
import gg.essential.loader.stage1.util.IDelegatingTransformationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class EssentialTransformationService extends EssentialTransformationServiceBase implements IDelegatingTransformationService {
    private static final Logger LOGGER = LogManager.getLogger(EssentialTransformationServiceBase.class);
    private static final String KEY_LOADED = "gg.essential.loader.stage1.loaded";

    // At this point modlauncher does not yet expose the Minecraft version to us. So instead we will just assume
    // the first one we support (stage2 will be the same for all MC versions on the same stage1 anyway) and once we are
    // in stage2, we can re-query at a later point as needed (but we always retain the ability to run an up-to-date
    // stage2 as early as possible).
    protected static final String MC_VERSION = "1.17.1";

    public EssentialTransformationService(final ITransformationService stage0) throws Exception {
        super(stage0, FallbackTransformationService::new, "modlauncher9", MC_VERSION);

        final List<Path> sourceFiles = getSourceFiles(stage0.getClass());
        if (sourceFiles.isEmpty()) {
            LOGGER.error("Not able to determine current file. Mod will NOT work");
            return;
        }
        for (Path sourceFile : sourceFiles) {
            setupSourceFile(sourceFile);
        }
    }

    @SuppressWarnings("unchecked")
    private void setupSourceFile(final Path sourceFile) throws Exception {
        final Path normalizedSourceFile = sourceFile.normalize();

        // Forge will by default ignore a mod file if it contains an implementation of ITransformationService
        // So we need to remove ourselves from that exclusion list
        Class<?> cls;
        try {
            cls = Class.forName("net.minecraftforge.fml.loading.ModDirTransformerDiscoverer");
        } catch (ClassNotFoundException e1) {
            try {
                cls = Class.forName("net.neoforged.fml.loading.ModDirTransformerDiscoverer");
            } catch (ClassNotFoundException e2) {
                e2.addSuppressed(e1);
                throw e2;
            }
        }
        Field foundField = cls.getDeclaredField("found");
        foundField.setAccessible(true);
        ((List<NamedPath>) foundField.get(null)).removeIf(namedPath ->
            Arrays.stream(namedPath.paths()).anyMatch(path -> path.normalize().equals(normalizedSourceFile)));
    }

    private List<Path> getSourceFiles(Class<?> stage0Class) {
        return stage0Class.getModule()
            .getLayer()
            .configuration()
            .findModule(stage0Class.getModule().getName())
            .flatMap(rm -> rm.reference().location())
            .map(Path::of)
            .stream()
            .collect(Collectors.toList());
    }
}
