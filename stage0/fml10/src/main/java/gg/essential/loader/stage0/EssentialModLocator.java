package gg.essential.loader.stage0;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import static gg.essential.loader.stage0.EssentialLoader.STAGE1_PKG;

public class EssentialModLocator implements IModFileCandidateLocator {
    private static final String STAGE1_CLS = STAGE1_PKG + "EssentialModLocator";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        // Extract/update stage1 from embedded jars
        EssentialLoader loader = new EssentialLoader("fml10");
        URL stage1Url;
        try {
            stage1Url = loader.loadStage1File(context.gameDirectory()).toUri().toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Create a class loader with which to load stage1
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{ stage1Url }, getClass().getClassLoader())) {
            // Load stage1 class
            IModFileCandidateLocator stage1 = (IModFileCandidateLocator) Class.forName(STAGE1_CLS, true, classLoader)
                .getConstructor()
                .newInstance();

            // Finally, delegate to it
            stage1.findCandidates(context, pipeline);
        } catch (IOException | ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
