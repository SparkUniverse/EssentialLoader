package gg.essential.loader.stage2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

public class DedicatedJarLoader {
    private static final Logger LOGGER = LogManager.getLogger(DedicatedJarLoader.class);
    private static final String BASE_URL = System.getProperty(
            "essential.download.url",
            System.getenv().getOrDefault("ESSENTIAL_DOWNLOAD_URL", "https://api.essential.gg/mods")
    );
    private static final String VERSION_URL = BASE_URL + "/v1/essential:essential-pinned/versions/%s/platforms/%s";
    private static final String DOWNLOAD_URL = VERSION_URL + "/download";

    protected static void downloadDedicatedJar(LoaderUI ui, Path modsDir, String gameVersion) throws IOException {
        final String apiVersion = gameVersion.replace('.', '-');
        final String essentialVersion = getEssentialVersionMeta(apiVersion).get("version").getAsString();

        final JsonObject meta = getEssentialDownloadMeta(essentialVersion, apiVersion);
        final URL url = new URL(meta.get("url").getAsString());
        final URLConnection connection = url.openConnection();

        ui.setDownloadSize(connection.getContentLength());

        final Path target = modsDir.resolve(String.format("Essential %s (%s).jar", essentialVersion, gameVersion));
        final Path tempFile = Files.createFile(modsDir.resolve("Essential Jar Download.jar.tmp"));

        try (
                final InputStream in = connection.getInputStream();
                final OutputStream out = Files.newOutputStream(tempFile);
        ) {
            final byte[] buffer = new byte[1024];
            int totalRead = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                totalRead += read;
                ui.setDownloaded(totalRead);
            }
        } finally {
            try {
                Files.move(tempFile, target, ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private static JsonObject getEssentialMeta(URL url) throws IOException {
        String response;
        try (final InputStream in = url.openStream()) {
            response = new String(in.readAllBytes());
        }
        JsonElement json = new JsonParser().parse(response);
        return json.getAsJsonObject();
    }

    private static JsonObject getEssentialVersionMeta(String gameVersion) throws IOException {
        return getEssentialMeta(new URL(String.format(VERSION_URL, "stable", gameVersion)));
    }

    private static JsonObject getEssentialDownloadMeta(String essentialVersion, String gameVersion) throws IOException {
        return getEssentialMeta(new URL(String.format(DOWNLOAD_URL, essentialVersion, gameVersion)));
    }
}
