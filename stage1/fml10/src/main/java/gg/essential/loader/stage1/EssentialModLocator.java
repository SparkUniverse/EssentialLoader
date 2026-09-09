package gg.essential.loader.stage1;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@SuppressWarnings("unused")
public class EssentialModLocator implements IModFileCandidateLocator {
    private static final String STAGE2_CLS = "gg.essential.loader.stage2.EssentialModLocator";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        // Extract embedded stage2
        URL stage2Url;
        try {
            Path extracted = Files.createTempFile("essential-loader-stage2-", ".jar");
            extracted.toFile().deleteOnExit();
            try (InputStream in = EssentialModLocator.class.getResourceAsStream("stage2.jar")) {
                assert(in != null);
                Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
            }
            stage2Url = extracted.toUri().toURL();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Create a class loader with which to load stage2
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{ stage2Url }, getClass().getClassLoader())) {
            // Load stage2 class
            IModFileCandidateLocator stage2 = (IModFileCandidateLocator) Class.forName(STAGE2_CLS, true, classLoader)
                .getConstructor()
                .newInstance();

            // Finally, delegate to it
            stage2.findCandidates(context, pipeline);
        } catch (IOException | ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
