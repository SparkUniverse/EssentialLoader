package gg.essential.loader.stage2;

import gg.essential.loader.fixtures.Installation;
import gg.essential.loader.fixtures.IsolatedLaunch;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static gg.essential.loader.fixtures.BaseInstallation.withBranch;
import static gg.essential.loader.util.Props.props;
import static gg.essential.loader.util.Props.readProps;
import static gg.essential.loader.util.Props.writeProps;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Stage2BundledTests {
    @Test
    public void testAutoUpdateWithBundledVersion(Installation installation) throws Exception {
        installation.addExampleMod("bundled-1");

        installation.launchFML();

        // Enable auto-update via config file (otherwise we'll default to update with-prompt when there's a pinned jar)
        Files.write(installation.stage2ConfigFile, "autoUpdate=true".getBytes(StandardCharsets.UTF_8));

        Files.delete(installation.stage3Meta);
        Files.copy(installation.stage3DummyMeta, installation.stage3Meta);

        IsolatedLaunch isolatedLaunch = installation.launchFML();

        installation.assertModLaunched(isolatedLaunch);
        assertTrue(isolatedLaunch.getEssentialLoadState("dummyTweaker"));
    }

    @Test
    public void testPromptUpdateAccepted(Installation installation) throws Exception {
        installation.addExampleMod("bundled-1");

        Files.copy(withBranch(installation.stage3Meta, "2"), installation.stage3Meta, REPLACE_EXISTING);

        IsolatedLaunch firstLaunch = installation.launchFML();
        assertEquals("1", firstLaunch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=2"), readProps(installation.stage2ConfigFile));

        writeProps(installation.stage2ConfigFile, props("pendingUpdateVersion=2", "pendingUpdateResolution=true"));

        IsolatedLaunch secondLaunch = installation.launchFML();
        assertEquals("2", secondLaunch.getProperty("essential.version"));
        assertEquals(props("overridePinnedVersion=2"), readProps(installation.stage2ConfigFile));
    }

    @Test
    public void testPromptUpdateRejected(Installation installation) throws Exception {
        installation.addExampleMod("bundled-1");

        Files.copy(withBranch(installation.stage3Meta, "2"), installation.stage3Meta, REPLACE_EXISTING);

        IsolatedLaunch firstLaunch = installation.launchFML();
        assertEquals("1", firstLaunch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=2"), readProps(installation.stage2ConfigFile));

        writeProps(installation.stage2ConfigFile, props("pendingUpdateVersion=2", "pendingUpdateResolution=false"));

        IsolatedLaunch secondLaunch = installation.launchFML();
        assertEquals("1", secondLaunch.getProperty("essential.version"));

        installation.assertModLaunched(secondLaunch);
        assertTrue(secondLaunch.isEssentialLoaded(), "Essential loaded");
        assertEquals(props("pendingUpdateVersion=2", "pendingUpdateResolution=false"), readProps(installation.stage2ConfigFile));
    }

    @Test
    public void testPromptUpdateAcceptedFallback(Installation installation) throws Exception {
        installation.addExampleMod("bundled-1");

        Files.copy(withBranch(installation.stage3Meta, "2"), installation.stage3Meta, REPLACE_EXISTING);

        IsolatedLaunch firstLaunch = installation.launchFML();
        assertEquals("1", firstLaunch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=2"), readProps(installation.stage2ConfigFile));

        IsolatedLaunch secondLaunch = installation.newLaunchFML();
        secondLaunch.setProperty("essential.stage2.fallback-prompt-auto-answer", "true");
        secondLaunch.launch();

        assertEquals("2", secondLaunch.getProperty("essential.version"));
        assertEquals(props("overridePinnedVersion=2"), readProps(installation.stage2ConfigFile));
    }

    @Test
    public void testPromptUpdateRejectedFallback(Installation installation) throws Exception {
        installation.addExampleMod("bundled-1");

        Files.copy(withBranch(installation.stage3Meta, "2"), installation.stage3Meta, REPLACE_EXISTING);

        IsolatedLaunch firstLaunch = installation.launchFML();
        assertEquals("1", firstLaunch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=2"), readProps(installation.stage2ConfigFile));

        IsolatedLaunch secondLaunch = installation.newLaunchFML();
        secondLaunch.setProperty("essential.stage2.fallback-prompt-auto-answer", "false");
        secondLaunch.launch();

        assertEquals("1", secondLaunch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=2", "pendingUpdateResolution=false"), readProps(installation.stage2ConfigFile));
    }

    @Test
    public void testBundledVersionUpgradeWithoutOverride(Installation installation) throws Exception {
        installation.addExampleMod("bundled-1");
        // Skip online update, we want to test local bundle update
        Files.copy(withBranch(installation.stage3Meta, "3"), installation.stage3Meta, REPLACE_EXISTING);
        writeProps(installation.stage2ConfigFile, props("pendingUpdateVersion=3", "pendingUpdateResolution=false"));

        IsolatedLaunch firstLaunch = installation.launchFML();
        assertEquals("1", firstLaunch.getProperty("essential.version"));
        assertEquals("1", firstLaunch.getProperty("essential.version"));

        installation.addExampleMod("bundled-2");

        IsolatedLaunch secondLaunch = installation.launchFML();
        assertEquals("2", secondLaunch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=3", "pendingUpdateResolution=false"), readProps(installation.stage2ConfigFile));
    }

    @Test
    public void testBundledVersionUpgradeToNewerVersion(Installation installation) throws Exception {
        IsolatedLaunch launch;

        // First install at version 1
        Files.copy(withBranch(installation.stage3Meta, "1"), installation.stage3Meta, REPLACE_EXISTING);
        installation.addExampleMod("bundled-1");
        launch = installation.launchFML();
        assertEquals("1", launch.getProperty("essential.version"));

        // Then click-to-update to version 2
        Files.copy(withBranch(installation.stage3Meta, "2"), installation.stage3Meta, REPLACE_EXISTING);
        launch = installation.launchFML();
        assertEquals("1", launch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=2"), readProps(installation.stage2ConfigFile));
        writeProps(installation.stage2ConfigFile, props("pendingUpdateVersion=2", "pendingUpdateResolution=true"));
        launch = installation.launchFML();
        assertEquals("2", launch.getProperty("essential.version"));
        assertEquals(props("overridePinnedVersion=2"), readProps(installation.stage2ConfigFile));

        // Finally upgrade the bundled mod to version 3
        installation.addExampleMod("bundled-3");
        launch = installation.launchFML();
        // it should use that version
        assertEquals("3", launch.getProperty("essential.version"));
        // and un-pin the existing one
        assertEquals(props(), readProps(installation.stage2ConfigFile));
    }

    @Test
    public void testBundledVersionUpgradeToOlderVersion(Installation installation) throws Exception {
        IsolatedLaunch launch;

        // First install at version 1
        installation.addExampleMod("bundled-1");
        launch = installation.launchFML();
        assertEquals("1", launch.getProperty("essential.version"));

        // Then click-to-update to version 3
        Files.copy(withBranch(installation.stage3Meta, "3"), installation.stage3Meta, REPLACE_EXISTING);
        launch = installation.launchFML();
        assertEquals("1", launch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=3"), readProps(installation.stage2ConfigFile));
        writeProps(installation.stage2ConfigFile, props("pendingUpdateVersion=3", "pendingUpdateResolution=true"));
        launch = installation.launchFML();
        assertEquals("3", launch.getProperty("essential.version"));
        assertEquals(props("overridePinnedVersion=3"), readProps(installation.stage2ConfigFile));

        // Finally upgrade the bundled mod to version 2
        installation.addExampleMod("bundled-2");
        launch = installation.launchFML();
        // it should stay at version 3 however
        assertEquals("3", launch.getProperty("essential.version"));
        assertEquals(props("overridePinnedVersion=3"), readProps(installation.stage2ConfigFile));
    }

    @Test
    public void testBundledVersionDowngrade(Installation installation) throws Exception {
        IsolatedLaunch launch;

        // First install at version 2
        installation.addExampleMod("bundled-2");
        Files.copy(withBranch(installation.stage3Meta, "2"), installation.stage3Meta, REPLACE_EXISTING);
        launch = installation.launchFML();
        assertEquals("2", launch.getProperty("essential.version"));

        // Then downgrade the bundled mod to version 1
        installation.addExampleMod("bundled-1");
        launch = installation.launchFML();
        // we should follow the downgrade, despite our local version being more up-to-date
        assertEquals("1", launch.getProperty("essential.version"));
        assertEquals(props("pendingUpdateVersion=2"), readProps(installation.stage2ConfigFile));
    }
}
