package org.watermedia.binaries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;
import org.watermedia.tools.IOTool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the native provisioning of Binaries: checksums and trust pins, FFmpeg generations, BotGuard archives and the executable cache. */
@DisplayName("Native provisioning")
public class NativeProvisionTest {
    // ==========================================================================
    // FIXTURES
    // ==========================================================================
    // PUBLISHES A VERIFIED INSTALLATION RECORD FOR release AS THE CURRENT GENERATION OF directory
    private static Path cachedExecutable(final Path directory, final String name, final ExecutableBinary.Release release) throws Exception {
        final Path installed = Files.createDirectory(directory.resolve("installed"));
        final Path executable = Files.writeString(installed.resolve(name), "verified executable");
        final Properties record = new Properties();
        record.setProperty("version", release.version());
        record.setProperty("origin", release.url().toString());
        record.setProperty("platform", IOTool.platform());
        record.setProperty("archive.sha256", release.sha256());
        record.setProperty("executable.sha256", IOTool.sha256(executable));
        try (final var output = Files.newOutputStream(installed.resolve("installed.properties"))) {
            record.store(output, null);
        }
        IOTool.publishGeneration(directory, installed);
        return executable;
    }

    // A ONE-ENTRY rustypipe-botguard TAR.XZ; corrupt FLIPS A NAME BIT AFTER THE HEADER CHECKSUM WAS COMPUTED
    private static byte[] tar(final boolean corrupt) throws Exception {
        final byte[] header = new byte[512];
        final byte[] name = "rustypipe-botguard".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, name.length);
        final byte[] size = "00000000010".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        int checksum = 0;
        for (final byte value: header) checksum += value & 255;
        final byte[] field = String.format(Locale.ROOT, "%06o\0 ", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(field, 0, header, 148, field.length);
        if (corrupt) header[0] ^= 1;
        final var bytes = new ByteArrayOutputStream();
        try (final var xz = new XZOutputStream(bytes, new LZMA2Options(1))) {
            xz.write(header);
            xz.write("verified".getBytes(StandardCharsets.UTF_8));
            xz.write(new byte[504 + 1024]);
        }
        return bytes.toByteArray();
    }

    private static Path install(final Path directory, final Fixture fixture) throws IOException {
        return FFmpegBinaries.install(directory, fixture.version, fixture.hash, fixture.zip.length, fixture.libraries, new ByteArrayInputStream(fixture.zip));
    }

    // AN FFmpeg ARCHIVE WITH files PLUS ITS version.cfg, THE ARCHIVE DIGEST AND ONE DIGEST PER LIBRARY
    private static Fixture fixture(final String version, final Map<String, String> files) throws Exception {
        final var output = new ByteArrayOutputStream();
        final var hashes = new LinkedHashMap<String, String>();
        try (final var zip = new ZipOutputStream(output)) {
            for (final var file: files.entrySet()) {
                final byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
                hashes.put(file.getKey(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(data);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("version.cfg"));
            zip.write(version.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        final byte[] bytes = output.toByteArray();
        return new Fixture(version, bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), hashes);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Checksums and trust pins")
    class Integrity {
        @TempDir Path directory;

        @Test
        @DisplayName("A yt-dlp checksum must exist, be valid and be unique; a release needs a digest and HTTPS")
        void checksumMustExistBeValidAndUnique() throws Exception {
            final String hash = "a".repeat(64);
            assertEquals(hash, YtDlpBinary.checksum(hash.toUpperCase() + " *yt-dlp.exe\n", "yt-dlp.exe"));
            assertThrows(IOException.class, () -> YtDlpBinary.checksum(hash + " other", "yt-dlp.exe"));
            assertThrows(IOException.class, () -> YtDlpBinary.checksum("garbage yt-dlp.exe", "yt-dlp.exe"));
            assertThrows(IOException.class, () -> YtDlpBinary.checksum(hash + " yt-dlp.exe\n" + hash + " yt-dlp.exe", "yt-dlp.exe"));
            assertThrows(IllegalArgumentException.class, () -> new ExecutableBinary.Release("v1", URI.create("https://example.com/a"), null));
            assertThrows(IllegalArgumentException.class, () -> new ExecutableBinary.Release("v1", URI.create("http://example.com/a"), hash));
        }

        @Test
        @DisplayName("The BotGuard trust pins cover only the reviewed release")
        void trustPinsCoverOnlyReviewedRelease() throws Exception {
            final var pins = new Properties();
            try (final var input = this.getClass().getResourceAsStream("/META-INF/botguard-pins.properties")) {
                assertNotNull(input);
                pins.load(input);
            }
            assertEquals("v0.1.2", pins.getProperty("version"));
            for (final String platform: new String[] { "windows-x86_64", "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64" }) {
                assertEquals(64, IOTool.requireSha256(pins.getProperty(platform + ".sha256")).length());
                assertTrue(pins.getProperty(platform + ".url").startsWith("https://codeberg.org/ThetaDev/rustypipe-botguard/releases/download/v0.1.2/"));
            }
        }

        @Test
        @DisplayName("A corrupted file fails its SHA-256 verification")
        void corruptFileFailsVerification() throws Exception {
            final Path file = Files.writeString(this.directory.resolve("binary"), "valid");
            final String expected = IOTool.sha256(file);
            IOTool.verifySha256(file, expected);
            Files.writeString(file, "wrong");
            assertThrows(IOException.class, () -> IOTool.verifySha256(file, expected));
        }
    }

    @Nested
    @DisplayName("FFmpeg generations")
    class Generations {
        @TempDir Path directory;

        @Test
        @DisplayName("A missing or corrupted library installs a repaired generation next to the intact marker")
        void extractionRepairsMissingAndCorruptedLibrariesWithIntactMarker() throws Exception {
            final var fixture = fixture("8.1.2-1.5.14", Map.of("native.dll", "native"));
            final Path first = install(this.directory, fixture);
            assertEquals(first, install(this.directory, fixture));
            Files.writeString(first.resolve("native.dll"), "broken");
            final Path repaired = install(this.directory, fixture);
            assertNotEquals(first, repaired);
            assertEquals("native", Files.readString(repaired.resolve("native.dll")));
            assertTrue(Files.exists(first));
            Files.delete(repaired.resolve("native.dll"));
            final Path replaced = install(this.directory, fixture);
            assertNotEquals(repaired, replaced);
            assertEquals(replaced, IOTool.currentGeneration(this.directory));
        }

        @Test
        @DisplayName("A different build qualifier installs a new generation")
        void differentBuildQualifierInstallsNewGeneration() throws Exception {
            final Path first = install(this.directory, fixture("8.1.2-one", Map.of("native.dll", "native")));
            final Path second = install(this.directory, fixture("8.1.2-two", Map.of("native.dll", "native")));
            assertNotEquals(first, second);
            assertEquals("8.1.2-one", Files.readString(first.resolve("version.cfg")));
            assertEquals("8.1.2-two", Files.readString(second.resolve("version.cfg")));
        }

        @Test
        @DisplayName("An interrupted installation keeps the previous generation")
        void interruptedInstallationPreservesPreviousGeneration() throws Exception {
            final Path previous = install(this.directory, fixture("old", Map.of("native.dll", "native")));
            final var next = fixture("new", Map.of("native.dll", "updated"));
            final InputStream failing = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("Interrupted"); }
            };
            assertThrows(IOException.class, () -> FFmpegBinaries.install(this.directory, next.version, next.hash, next.zip.length, next.libraries, failing));
            assertEquals(previous, IOTool.currentGeneration(this.directory));
            try (final var files = Files.list(this.directory)) {
                assertEquals(1, files.filter(Files::isDirectory).count());
            }
            assertNotEquals(previous, install(this.directory, next));
        }

        @Test
        @DisplayName("A wrong archive digest or an unexpected entry path fails closed")
        void archiveDigestAndUnexpectedPathsFailClosed() throws Exception {
            final var good = fixture("v1", Map.of("native.dll", "native"));
            assertThrows(IOException.class, () -> FFmpegBinaries.install(this.directory, good.version, "0".repeat(64), good.zip.length, good.libraries,
                    new ByteArrayInputStream(good.zip)));
            final var unsafe = fixture("v1", Map.of("../escape", "bad"));
            assertThrows(IOException.class, () -> FFmpegBinaries.install(this.directory, unsafe.version, unsafe.hash, unsafe.zip.length, good.libraries,
                    new ByteArrayInputStream(unsafe.zip)));
            assertFalse(Files.exists(this.directory.getParent().resolve("escape")));
            assertNull(IOTool.currentGeneration(this.directory));
        }

        @Test
        @DisplayName("The current pointer cannot escape the cache directory")
        void pointerCannotEscapeCacheDirectory() throws Exception {
            Files.writeString(this.directory.resolve("current"), "../other");
            assertNull(IOTool.currentGeneration(this.directory));
            Files.writeString(this.directory.resolve("current"), this.directory.toAbsolutePath().toString());
            assertNull(IOTool.currentGeneration(this.directory));
        }
    }

    @Nested
    @DisplayName("BotGuard archives")
    class BotGuardArchives {
        @TempDir Path directory;

        @Test
        @DisplayName("A BotGuard ZIP must contain the expected executable")
        void botguardZipMustContainExpectedExecutable() throws Exception {
            final var wrong = fixture("unused", Map.of("../rustypipe-botguard.exe", "bad"));
            final Path archive = Files.write(this.directory.resolve("archive.zip"), wrong.zip);
            assertThrows(IOException.class, () -> BotGuardBinary.extract(archive, this.directory.resolve("binary"), "rustypipe-botguard.exe"));
            assertFalse(Files.exists(this.directory.resolve("binary")));
        }

        @Test
        @DisplayName("A BotGuard TAR header is checked before the executable is written")
        void botguardTarChecksHeaderBeforeWritingExecutable() throws Exception {
            final Path archive = Files.write(this.directory.resolve("botguard.tar.xz"), tar(false));
            final Path binary = this.directory.resolve("binary");
            BotGuardBinary.extract(archive, binary, "rustypipe-botguard");
            assertEquals("verified", Files.readString(binary));
            Files.write(archive, tar(true));
            assertThrows(IOException.class, () -> BotGuardBinary.extract(archive, this.directory.resolve("bad"), "rustypipe-botguard"));
            assertFalse(Files.exists(this.directory.resolve("bad")));
        }

        @Test
        @DisplayName("A BotGuard TAR is drained so its XZ footer gets validated")
        void botguardTarDrainsAndValidatesXzFooter() throws Exception {
            final byte[] bytes = tar(false);
            final Path archive = Files.write(this.directory.resolve("truncated.tar.xz"), Arrays.copyOf(bytes, bytes.length - 8));
            assertThrows(IOException.class, () -> BotGuardBinary.extract(archive, this.directory.resolve("partial"), "rustypipe-botguard"));
        }
    }

    @Nested
    @DisplayName("Executables")
    class Executables {
        @TempDir Path directory;

        @Test
        @DisplayName("An executable override still requires a WaterMedia session")
        void executableOverrideRequiresWaterMediaSession() throws Exception {
            final String property = "watermedia.ytdlp.binary";
            final String previous = System.getProperty(property);
            try {
                System.setProperty(property, Files.writeString(this.directory.resolve("override"), "host").toString());
                assertThrows(IOException.class, () -> new YtDlpBinary().executable());
            } finally {
                if (previous == null) System.clearProperty(property);
                else System.setProperty(property, previous);
            }
        }

        @Test
        @DisplayName("Extraction dispatches by binary type")
        void executableExtractionDispatchesByBinaryType() throws Exception {
            final ExecutableBinary ytdlp = new YtDlpBinary();
            final ExecutableBinary botguard = new BotGuardBinary();
            assertNotNull(ytdlp.name);
            assertNotNull(botguard.name);

            final Path ytdlpArchive = Files.writeString(this.directory.resolve("yt-dlp-download"), "direct executable");
            final Path ytdlpExecutable = this.directory.resolve(ytdlp.name);
            ytdlp.extract(ytdlpArchive, ytdlpExecutable);
            assertFalse(Files.exists(ytdlpArchive));
            assertEquals("direct executable", Files.readString(ytdlpExecutable));

            final Path botguardArchive = this.directory.resolve("botguard-download");
            if (botguard.name.endsWith(".exe")) {
                try (final var output = new ZipOutputStream(Files.newOutputStream(botguardArchive))) {
                    output.putNextEntry(new ZipEntry(botguard.name));
                    output.write("verified".getBytes(StandardCharsets.UTF_8));
                    output.closeEntry();
                }
            } else {
                Files.write(botguardArchive, tar(false));
            }
            final Path botguardExecutable = this.directory.resolve(botguard.name);
            botguard.extract(botguardArchive, botguardExecutable);
            assertEquals("verified", Files.readString(botguardExecutable));
            assertTrue(Files.isRegularFile(botguardArchive));
        }

        @Test
        @DisplayName("The executable cache uses the pinned release and survives an offline lookup")
        void executableCacheUsesPinnedReleaseAndSurvivesOfflineLookup() throws Exception {
            final YtDlpBinary ytdlp = new YtDlpBinary();
            final BotGuardBinary botguard = new BotGuardBinary();
            assertNotNull(ytdlp.name);
            assertNotNull(botguard.name);
            final Path ytdlpDir = Files.createDirectory(this.directory.resolve("yt-dlp"));
            final Path botguardDir = Files.createDirectory(this.directory.resolve("botguard"));
            final Path ytdlpFile = cachedExecutable(ytdlpDir, ytdlp.name,
                    new ExecutableBinary.Release("offline", URI.create("https://offline.invalid/yt-dlp"), "a".repeat(64)));
            final Path botguardFile = cachedExecutable(botguardDir, botguard.name, botguard.latest());
            final Field paths = WaterMediaBinaries.class.getDeclaredField("paths");
            paths.setAccessible(true);
            final Object previousPaths = paths.get(null);
            final String previousYtdlp = System.getProperty("watermedia.ytdlp.binary");
            final String previousBotguard = System.getProperty("watermedia.botguard.binary");
            final ProxySelector previousProxy = ProxySelector.getDefault();
            final AtomicInteger lookups = new AtomicInteger();
            try {
                paths.set(null, Map.of(WaterMediaBinaries.YTDLP_ID, ytdlpDir, WaterMediaBinaries.BOTGUARD_ID, botguardDir));
                System.clearProperty("watermedia.ytdlp.binary");
                System.clearProperty("watermedia.botguard.binary");
                assertEquals(botguardFile, botguard.executable());
                assertEquals(botguardFile, botguard.executable());

                ProxySelector.setDefault(new ProxySelector() {
                    @Override public List<Proxy> select(final URI uri) {
                        lookups.incrementAndGet();
                        throw new IllegalStateException("Network disabled for executable cache test");
                    }
                    @Override public void connectFailed(final URI uri, final SocketAddress address, final IOException failure) {}
                });
                assertEquals(ytdlpFile, ytdlp.executable());
                assertTrue(lookups.get() > 0);
                assertEquals(ytdlpFile, ytdlp.executable());
                assertEquals(ytdlpFile.getParent(), IOTool.currentGeneration(ytdlpDir));
                assertEquals(botguardFile.getParent(), IOTool.currentGeneration(botguardDir));
            } finally {
                ProxySelector.setDefault(previousProxy);
                paths.set(null, previousPaths);
                if (previousYtdlp == null) System.clearProperty("watermedia.ytdlp.binary");
                else System.setProperty("watermedia.ytdlp.binary", previousYtdlp);
                if (previousBotguard == null) System.clearProperty("watermedia.botguard.binary");
                else System.setProperty("watermedia.botguard.binary", previousBotguard);
            }
        }
    }

    private record Fixture(String version, byte[] zip, String hash, Map<String, String> libraries) {}
}
