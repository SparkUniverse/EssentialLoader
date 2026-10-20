package gg.essential.loader.stage1;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import gg.essential.loader.stage1.gui.ForkedUpdatePromptUI;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Properties;

import static gg.essential.loader.stage1.VersionComparison.compareVersions;

public abstract class EssentialLoaderBase {

    private static final Logger LOGGER = LogManager.getLogger(EssentialLoaderBase.class);
    protected static final String STAGE2_PKG = "gg.essential.loader.stage2.";
    protected static final String STAGE2_CLS = STAGE2_PKG + "EssentialLoader";

    private static final String API_BASE_URL = System.getProperty(
        "essential.download.url",
        System.getenv().getOrDefault("ESSENTIAL_DOWNLOAD_URL", "https://api.essential.gg/mods")
    );
    private static final String VERSION_BASE_URL = API_BASE_URL + "/v1/essential:loader-stage2/versions/%s";
    private static final String CHANGELOG_URL = VERSION_BASE_URL + "/changelog";
    private static final String VERSION_URL = VERSION_BASE_URL + "/platforms/%s";
    private static final String DOWNLOAD_URL = VERSION_URL + "/download";

    private static final String BRANCH_KEY = "branch";
    private static final String AUTO_UPDATE_KEY = "autoUpdate";
    private static final String OVERRIDE_PINNED_VERSION_KEY = "overridePinnedVersion";
    private static final String PENDING_UPDATE_VERSION_KEY = "pendingUpdateVersion";
    private static final String PENDING_UPDATE_RESOLUTION_KEY = "pendingUpdateResolution";

    private static final boolean RELAUNCHING = Boolean.parseBoolean(System.getProperty("essential.loader.relaunched", "false"));

    private final String variant;
    private final String gameVersion;
    private Object stage2;
    private boolean loaded;

    EssentialLoaderBase(final String variant, final String gameVersion) {
        this.variant = variant;
        this.gameVersion = gameVersion;
    }

    public void load(final Path gameDir) throws Exception {
        if (this.loaded) {
            return;
        }
        this.loaded = true; // setting this now, no point in retrying when we error

        final Path dataDir = gameDir
            .resolve("essential")
            .resolve("loader")
            .resolve("stage1")
            .resolve(variant);
        final Path stage2File = dataDir.resolve("stage2." + this.gameVersion + ".jar");
        final Path stage2MetaFile = dataDir.resolve("stage2." + this.gameVersion + ".meta");
        final Path configFile = dataDir.resolve("stage2." + this.gameVersion + ".properties");
        final URL stage2Url = stage2File.toUri().toURL();

        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }

        Properties defaultProps = new Properties();

        // Loading pinned configs
        FileMeta latestPinnedMeta = null;
        Enumeration<URL> urls = getClass().getClassLoader().getResources("essential-loader-stage2.properties");
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            LOGGER.trace("Reading properties file at {}", url);

            Properties properties = new Properties();
            try (InputStream in = url.openStream()) {
                properties.load(in);
            } catch (IOException e) {
                LOGGER.warn("Failed to read properties file at `" + url + "`:", e);
            }

            String pinnedFile = properties.getProperty("pinnedFile");
            if (pinnedFile != null) {
                URL pinnedFileUrl;
                if (pinnedFile.startsWith("/")) {
                    pinnedFileUrl = getClass().getClassLoader().getResource(pinnedFile.substring(1));
                    if (pinnedFileUrl == null) {
                        LOGGER.fatal("Failed to find pinned jar file at {}", pinnedFile);
                        continue;
                    }
                } else {
                    pinnedFileUrl = new URL(pinnedFile);
                }
                String pinnedFileMd5 = properties.getProperty("pinnedFileMd5");
                String pinnedFileVersion = properties.getProperty("pinnedFileVersion");
                if (latestPinnedMeta == null || compareVersions(pinnedFileVersion, latestPinnedMeta.version) > 0) {
                    latestPinnedMeta = new FileMeta(pinnedFileVersion, pinnedFileUrl, pinnedFileMd5);
                }
            }
        }

        // If there's a pinned jar and nothing else in configured, require user confirmation for updates
        if (latestPinnedMeta != null) {
            defaultProps.setProperty(AUTO_UPDATE_KEY, AutoUpdate.WITH_PROMPT);
        }

        // Load defaults from environment
        copyEnvToProp(defaultProps, "ESSENTIAL_STAGE2_BRANCH", BRANCH_KEY);
        copyPropToProp(defaultProps, "essential.stage2.branch", BRANCH_KEY);
        copyPropToProp(defaultProps, "essential.autoUpdate", AUTO_UPDATE_KEY);

        // Load file config
        Properties config = new Properties(defaultProps);
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                config.load(in);
            } catch (Exception e) {
                LOGGER.error("Failed to read config at " + configFile + ":", e);
            }
        }
        AutoUpdate autoUpdate = AutoUpdate.from(config.getProperty(AUTO_UPDATE_KEY));
        String branch = config.getProperty(BRANCH_KEY, "stable");

        // Load local metadata
        String localVersion;
        String localMd5;
        if (Files.exists(stage2MetaFile)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(stage2MetaFile)) {
                properties.load(in);
            } catch (IOException e) {
                LOGGER.error("Failed to read properties file at `" + stage2MetaFile + "`:", e);
            }
            localVersion = properties.getProperty("version");
            localMd5 = properties.getProperty("md5");

            // If the checksum is invalid, throw it away
            if (!localMd5.equals(getChecksum(stage2File))) {
                localVersion = null;
                localMd5 = null;
            }
        } else {
            localVersion = null;
            localMd5 = null;
        }

        // If we don't have a local jar, get hold of one
        if (localVersion == null) {
            FileMeta meta;
            if (latestPinnedMeta != null) {
                meta = latestPinnedMeta;
            } else {
                meta = fetchLatestMetadata(branch);
                if (meta == null) {
                    return;
                }
            }
            if (!doDownload(meta, stage2File, stage2MetaFile)) {
                return;
            }
            localVersion = meta.version;
            localMd5 = meta.checksum;
        }

        // If the pinned version is more recent than the local one, use it
        if (latestPinnedMeta != null && compareVersions(latestPinnedMeta.version, localVersion) > 0) {
            if (doDownload(latestPinnedMeta, stage2File, stage2MetaFile)) {
                localVersion = latestPinnedMeta.version;
                localMd5 = latestPinnedMeta.checksum;
            }
        }

        if (autoUpdate == AutoUpdate.Full && !RELAUNCHING) {
            // Update if our local version isn't exactly the same as the latest online version
            FileMeta latestOnlineMeta = fetchLatestMetadata(branch);
            if (latestOnlineMeta != null && !latestOnlineMeta.checksum.equals(localMd5)) {
                if (doDownload(latestOnlineMeta, stage2File, stage2MetaFile)) {
                    localVersion = latestOnlineMeta.version;
                    localMd5 = latestOnlineMeta.checksum;
                }
            }
        } else if (autoUpdate == AutoUpdate.Manual && !RELAUNCHING) {
            // Check which version the user/mod wants us to be at
            String pinOverride = config.getProperty(OVERRIDE_PINNED_VERSION_KEY);
            // If an override is set but no longer required, remove it, so we follow the real pin again
            if (pinOverride != null && latestPinnedMeta != null && compareVersions(pinOverride, latestPinnedMeta.version) <= 0) {
                config.remove(OVERRIDE_PINNED_VERSION_KEY);
                writeProperties(configFile, config);
                pinOverride = null;
            }

            // If no override is set and we have a pinned version, then use it
            // This allows users to downgrade the effective version by downgrading the container jar
            if (pinOverride == null && latestPinnedMeta != null && !localMd5.equals(latestPinnedMeta.checksum)) {
                if (doDownload(latestPinnedMeta, stage2File, stage2MetaFile)) {
                    localVersion = latestPinnedMeta.version;
                    localMd5 = latestPinnedMeta.checksum;
                }
            }

            // Check if there's a newer version we could be using
            FileMeta onlineMeta = fetchLatestMetadata(branch);
            if (onlineMeta != null && compareVersions(onlineMeta.version, localVersion) > 0) {
                String pendingUpdateVersion = config.getProperty(PENDING_UPDATE_VERSION_KEY);
                Boolean resolution = booleanOrNull(config.getProperty(PENDING_UPDATE_RESOLUTION_KEY));
                boolean blanketPermission = pendingUpdateVersion == null && resolution == Boolean.TRUE;
                if (blanketPermission || Objects.equals(pendingUpdateVersion, onlineMeta.version)) {
                    // Update was already pending, check whether we are allowed to install it
                    if (resolution == null) {
                        resolution = showUpdatePrompt(onlineMeta.version);
                        if (resolution != null) {
                            config.setProperty(PENDING_UPDATE_RESOLUTION_KEY, Boolean.toString(resolution));
                            writeProperties(configFile, config);
                        }
                    }

                    // If the new version was accepted, download it. Otherwise, ignore it.
                    if (resolution == Boolean.TRUE) {
                        if (doDownload(onlineMeta, stage2File, stage2MetaFile)) {
                            localVersion = onlineMeta.version;
                            localMd5 = onlineMeta.checksum;

                            config.setProperty(OVERRIDE_PINNED_VERSION_KEY, onlineMeta.version);
                            config.remove(PENDING_UPDATE_VERSION_KEY);
                            config.remove(PENDING_UPDATE_RESOLUTION_KEY);
                            writeProperties(configFile, config);
                        }
                    } else {
                        LOGGER.warn("Found newer Essential Loader (stage2) version {} [{}], skipping {}",
                            onlineMeta.version, branch,
                            resolution == Boolean.FALSE ? "at user request" : "because no consent could be acquired");
                    }
                } else {
                    LOGGER.info("Found newer Essential Loader (stage2) version {} [{}]", onlineMeta.version, branch);
                    // Queue update for in-game prompt and installation on next boot
                    config.setProperty(PENDING_UPDATE_VERSION_KEY, onlineMeta.version);
                    config.remove(PENDING_UPDATE_RESOLUTION_KEY);
                    writeProperties(configFile, config);
                }
            } else {
                // Discard stale pending updates. This can happen when an update was pending but then the user installed
                // it by updating to a newer pinned jar via some external process (e.g. their launcher).
                if (config.getProperty(PENDING_UPDATE_VERSION_KEY) != null
                    || config.getProperty(PENDING_UPDATE_RESOLUTION_KEY) != null) {
                    config.remove(PENDING_UPDATE_VERSION_KEY);
                    config.remove(PENDING_UPDATE_RESOLUTION_KEY);
                    writeProperties(configFile, config);
                }
            }
        }

        if (autoUpdate != AutoUpdate.Manual) {
            // Clean up pending update properties if we are no longer in Manual update mode
            if (config.getProperty(PENDING_UPDATE_VERSION_KEY) != null
                || config.getProperty(PENDING_UPDATE_RESOLUTION_KEY) != null
                || config.getProperty(OVERRIDE_PINNED_VERSION_KEY) != null) {
                config.remove(PENDING_UPDATE_VERSION_KEY);
                config.remove(PENDING_UPDATE_RESOLUTION_KEY);
                config.remove(OVERRIDE_PINNED_VERSION_KEY);
                writeProperties(configFile, config);
            }
        }

        // Check if we can continue, otherwise do not even try
        if (!Files.exists(stage2File)) {
            return;
        }

        LOGGER.info("Starting Essential Loader (stage2) version {} ({}) [{}]", localVersion, localMd5, branch);
        System.setProperty("essential.stage2.version", localVersion);
        System.setProperty("essential.stage2.branch", branch);
        System.setProperty("essential.stage2.autoUpdate", autoUpdate.toPropertyValue());

        // Add stage2 file to class loader
        ClassLoader classLoader = addToClassLoader(stage2Url);

        // Finally, load stage2
        this.stage2 = Class.forName(STAGE2_CLS, true, classLoader)
            .getConstructor(Path.class, String.class)
            .newInstance(gameDir, this.gameVersion);
        // and continue there
        this.stage2.getClass()
            .getMethod("load")
            .invoke(this.stage2);
    }

    public Object getStage2() {
        return stage2;
    }

    protected abstract ClassLoader addToClassLoader(URL stage2Url) throws Exception;

    public void initialize() {
        if (this.stage2 == null) {
            return;
        }
        try {
            this.stage2.getClass()
                .getMethod("initialize")
                .invoke(this.stage2);
        } catch (Throwable e) {
            throw new RuntimeException("Unexpected error", e);
        }
    }

    private boolean doDownload(FileMeta meta, Path jarFile, Path metaFile) throws IOException {
        if (meta.url == null) {
            meta.url = fetchDownloadUrl(meta.version);
            if (meta.url == null) {
                return false;
            }
        }

        LOGGER.info("Updating Essential Loader (stage2) version {} ({}) from {}", meta.version, meta.checksum, meta.url);

        Path downloadedFile = Files.createTempFile(jarFile.getParent(), "download-", ".jar");
        if (downloadFile(meta, downloadedFile)) {
            Files.deleteIfExists(jarFile);
            Files.move(downloadedFile, jarFile);

            Properties props = new Properties();
            props.setProperty("version", meta.version);
            props.setProperty("md5", meta.checksum);
            writeProperties(metaFile, props);
            return true;
        } else {
            LOGGER.warn("Unable to download Essential, please check your internet connection. If the problem persists, please contact Support.");
            Files.deleteIfExists(downloadedFile);
            return false;
        }
    }

    private void writeProperties(Path path, Properties properties) throws IOException {
        Path tempFile = Files.createTempFile(path.getParent(), "tmp-", ".properties");
        try {
            try (Writer out = Files.newBufferedWriter(tempFile)) {
                properties.store(out, null);
            }
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private String getChecksum(final Path input) {
        try (final InputStream inputStream = Files.newInputStream(input)) {
            return DigestUtils.md5Hex(inputStream);
        } catch (final Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getChecksum(final URL input) {
        try (final InputStream inputStream = input.openStream()) {
            return DigestUtils.md5Hex(inputStream);
        } catch (final Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private URLConnection prepareConnection(final URL url) throws IOException {
        final URLConnection urlConnection = url.openConnection();

        if (urlConnection instanceof HttpURLConnection) {
            final HttpURLConnection httpURLConnection = (HttpURLConnection) urlConnection;

            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setUseCaches(true);
            httpURLConnection.setConnectTimeout(30_000);
            httpURLConnection.setReadTimeout(30_000);
            httpURLConnection.setDoOutput(true);

            httpURLConnection.addRequestProperty("User-Agent", "Mozilla/5.0 (Essential Initializer)");
        }

        return urlConnection;
    }

    private JsonObject fetchJsonObject(String endpoint, boolean allowEmpty) {
        URLConnection connection = null;
        try {
            connection = this.prepareConnection(new URL(endpoint));

            String response;
            try (final InputStream inputStream = connection.getInputStream()) {
                response = IOUtils.toString(inputStream, Charset.defaultCharset());
            }

            JsonElement jsonElement = new JsonParser().parse(response);
            if (!jsonElement.isJsonObject()) {
                if (allowEmpty && jsonElement.isJsonNull()) {
                    return null;
                } else {
                    throw new IOException("Excepted json object, got " + response);
                }
            }
            return jsonElement.getAsJsonObject();
        } catch (final IOException | JsonParseException e) {
            LOGGER.error("Error occurred fetching " + endpoint + ": ", e);
            logConnectionInfoOnError(connection);
            return null;
        }
    }

    private FileMeta fetchLatestMetadata(String branch) {
        JsonObject responseObject = fetchJsonObject(String.format(VERSION_URL,
            branch, this.gameVersion.replace(".", "-")), true);

        if (responseObject == null) {
            LOGGER.warn("Essential does not support the following game version: {}", this.gameVersion);
            return null;
        }

        final JsonElement
            jsonVersion = responseObject.get("version"),
            jsonChecksum = responseObject.get("checksum");
        final String
            version = jsonVersion != null && jsonVersion.isJsonPrimitive() ? jsonVersion.getAsString() : null,
            checksum = jsonChecksum != null && jsonChecksum.isJsonPrimitive() ? responseObject.get("checksum").getAsString() : null;

        if (StringUtils.isEmpty(version) || StringUtils.isEmpty(checksum)) {
            LOGGER.warn("Unexpected response object data (version={}, checksum={})", version, jsonChecksum);
            return null;
        }

        return new FileMeta(version, null, checksum);
    }

    private URL fetchDownloadUrl(String version) {
        JsonObject responseObject = fetchJsonObject(String.format(DOWNLOAD_URL,
            version, this.gameVersion.replace(".", "-")), false);

        if (responseObject == null) {
            return null;
        }

        final JsonElement jsonUrl = responseObject.get("url");
        final String url = jsonUrl != null && jsonUrl.isJsonPrimitive() ? jsonUrl.getAsString() : null;

        try {
            if (url == null) {
                throw new MalformedURLException();
            }
            return new URL(url);
        } catch (MalformedURLException e) {
            LOGGER.error("Received invalid url `" + url + "`:", e);
            return null;
        }
    }

    private boolean downloadFile(FileMeta meta, Path target) {
        URLConnection connection = null;
        try {
            connection = this.prepareConnection(meta.url);
            Files.copy(connection.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            LOGGER.error("Error occurred when downloading file '{}'.", meta.url, e);
            logConnectionInfoOnError(connection);
            return false;
        }

        final String actualHash = this.getChecksum(target);
        if (!meta.checksum.equals(actualHash)) {
            LOGGER.warn(
                "Downloaded Essential file checksum did not match what we expected (actual={}, expected={}",
                actualHash, meta.checksum
            );
            return false;
        }

        return true;
    }

    private void logConnectionInfoOnError(URLConnection connection) {
        if (connection == null) {
            return;
        }
        LOGGER.error("url: {}", connection.getURL());
        LOGGER.error("cf-ray: {}", connection.getHeaderField("cf-ray"));
    }

    private Boolean showUpdatePrompt(String version) {
        String description = "";
        try {
            JsonObject responseObject = fetchJsonObject(String.format(CHANGELOG_URL, version), false);

            if (responseObject != null) {
                description = responseObject.get("summary").getAsString();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load changelog for " + version, e);
        }

        // Skip actual UI for integration tests
        if (System.getProperty("essential.integration_testing") != null) {
            String autoAnswer = System.getProperty("essential.stage1.fallback-prompt-auto-answer");
            if (autoAnswer != null) {
                return Boolean.parseBoolean(autoAnswer);
            } else {
                throw new RuntimeException("Update prompt opened unexpectedly!");
            }
        }

        ForkedUpdatePromptUI promptUI = new ForkedUpdatePromptUI("Essential Loader Update!", description);
        promptUI.show();
        return promptUI.waitForClose();
    }

    private Boolean booleanOrNull(String str) {
        return str == null ? null : Boolean.parseBoolean(str);
    }

    private void copyEnvToProp(Properties properties, String envKey, String dstKey) {
        String value = System.getenv(envKey);
        if (value != null) {
            properties.setProperty(dstKey, value);
        }
    }

    private void copyPropToProp(Properties properties, String srcKey, String dstKey) {
        String value = System.getProperty(srcKey);
        if (value != null) {
            properties.setProperty(dstKey, value);
        }
    }

    private enum AutoUpdate {
        /** Automatically checks for and installs newer versions. */
        Full,
        /** Checks for newer versions but does not automatically install them until the user consents. */
        Manual,
        /** No network communication beyond initial download is performed. */
        Off,
        ;

        public static final String WITH_PROMPT = "with-prompt";

        private static AutoUpdate from(String value) {
            if (value == null) {
                return Full;
            } else if (value.equalsIgnoreCase(WITH_PROMPT)) {
                return Manual;
            } else {
                return Boolean.parseBoolean(value) ? Full : Off;
            }
        }

        private String toPropertyValue() {
            switch (this) {
                case Full: return "true";
                case Manual: return WITH_PROMPT;
                case Off: return "false";
            }
            throw new AssertionError();
        }
    }

    private static class FileMeta {
        String version;
        URL url;
        String checksum;

        public FileMeta(String version, URL url, String checksum) {
            this.version = version;
            this.url = url;
            this.checksum = checksum;
        }
    }
}