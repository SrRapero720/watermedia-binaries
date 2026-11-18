package org.watermedia.binaries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.tools.IOTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code tools/RepackFFmpeg.java}, run in a clean JVM, repacks LGPL and custom GPL candidates into
 * complete archives and a manifest, and that a wrong argument count, record, archive name, component, provenance or
 * variant leaves the installed archives and manifest alone.
 */
@DisplayName("RepackFFmpeg")
public class RepackToolTest {
    private static final String[] ARCHIVES = { "linux", "linux-arm64", "macos", "macos-arm64", "windows" };
    private static final long TOOL_TIMEOUT_MS = 20_000L;
    private static final long KILL_TIMEOUT_MS = 5_000L;

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // ONE VERIFIED CANDIDATE PER PLATFORM UNDER directory/candidates, THE INSTALLED ARCHIVES WRITTEN AS "existing <archive>";
    // invalidFinal BREAKS THE LAST VERIFICATION RECORD, missingLinuxJni DROPS libjniavcodec FROM LINUX, gpl SWITCHES THE BUILD
    private static Path candidates(final Path directory, final boolean invalidFinal, final boolean missingLinuxJni, final boolean gpl) throws Exception {
        Files.copy(Path.of("gradle.properties"), directory.resolve("gradle.properties"));
        final Properties properties = new Properties();
        try (final var input = Files.newInputStream(directory.resolve("gradle.properties"))) { properties.load(input); }
        if (gpl) {
            properties.setProperty("ffmpeg_version", "9.0-custom-1.5.14");
            properties.setProperty("ffmpeg_variant", "gpl");
            properties.setProperty("ffmpeg_license", "GPL-3.0-or-later");
            properties.remove("ffmpeg_headers_patch_sha256");
        }
        try (final var output = Files.newOutputStream(directory.resolve("gradle.properties"))) { properties.store(output, null); }
        final Path resources = Files.createDirectories(directory.resolve("src/main/resources/libs"));
        final Path candidates = Files.createDirectory(directory.resolve("candidates"));
        final String[] platforms = { "linux-x86_64", "linux-arm64", "macosx-x86_64", "macosx-arm64", "windows-x86_64" };
        final String[][] support = { { "libva.so.2", "libva-drm.so.2", "libdrm.so.2" },
                { "libasound.so.2", "libbcm_host.so", "libvchiq_arm.so", "libvcos.so" },
                { "libatomic.1.dylib" }, { "libatomic.1.dylib" }, { "libwinpthread-1.dll" } };
        for (int i = 0; i < platforms.length; i++) {
            Files.writeString(resources.resolve("ffmpeg-" + ARCHIVES[i] + ".zip"), "existing " + ARCHIVES[i]);
            final String platform = platforms[i];
            final Path candidate = Files.createDirectory(candidates.resolve(platform));
            final String suffix = gpl ? "-gpl" : "";
            final String name = "ffmpeg-" + properties.getProperty("ffmpeg_version") + "-" + platform + suffix + ".jar";
            final Path jar = candidate.resolve(name);
            final String extension = platform.startsWith("windows-") ? ".dll" : platform.startsWith("macosx-") ? ".dylib" : ".so";
            final List<String> libraries = new ArrayList<>();
            for (final String component: new String[] { "avcodec", "avdevice", "avfilter", "avformat", "avutil", "swresample", "swscale" }) {
                libraries.add("lib" + component + extension);
                libraries.add("libjni" + component + extension);
            }
            if (!gpl) libraries.addAll(List.of(support[i]));
            if (i == 0 && missingLinuxJni) libraries.remove("libjniavcodec" + extension);
            try (final var output = new ZipOutputStream(Files.newOutputStream(jar))) {
                for (final String library: libraries) {
                    output.putNextEntry(new ZipEntry("org/bytedeco/ffmpeg/" + platform + suffix + "/" + library));
                    output.write(library.getBytes(StandardCharsets.UTF_8));
                    output.closeEntry();
                }
            }
            final Path wrapper = candidate.resolve("ffmpeg-" + properties.getProperty("ffmpeg_version") + ".jar");
            Files.writeString(wrapper, "wrapper of " + platform);
            final Properties record = new Properties();
            record.setProperty("version", properties.getProperty("ffmpeg_version"));
            record.setProperty("platform", platform);
            record.setProperty("archive", name);
            record.setProperty("archive.sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
            record.setProperty("wrapper", wrapper.getFileName().toString());
            record.setProperty("wrapper.sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(wrapper))));
            record.setProperty("variant", properties.getProperty("ffmpeg_variant"));
            record.setProperty("license", properties.getProperty("ffmpeg_license"));
            record.setProperty("source.repository", properties.getProperty("ffmpeg_source_repository"));
            record.setProperty("source.ref", properties.getProperty("ffmpeg_source_ref"));
            record.setProperty("source.commit", properties.getProperty("ffmpeg_source_commit"));
            record.setProperty("source.sha256", properties.getProperty("ffmpeg_source_sha256"));
            record.setProperty("libxml2.version", properties.getProperty("libxml2_version"));
            record.setProperty("libxml2.sha256", properties.getProperty("libxml2_sha256"));
            record.setProperty("openssl.version", properties.getProperty("openssl_version"));
            record.setProperty("openssl.sha256", properties.getProperty("openssl_sha256"));
            record.setProperty("vulkan.headers.version", properties.getProperty("vulkan_headers_version"));
            record.setProperty("vulkan.headers.sha256", properties.getProperty("vulkan_headers_sha256"));
            if (!gpl) record.setProperty("headers.patch.sha256", properties.getProperty("ffmpeg_headers_patch_sha256"));
            record.setProperty("recipe.sha256", "0".repeat(64));
            record.setProperty("verification", invalidFinal && i == platforms.length - 1 ? "missing-TLS"
                    : gpl ? "JNI-wrappers,vulkan-bindings,vulkan-filters,libkvazaar,DASH,recursive-entities,imports-closure,clean-environment,TLS,HTTP-headers,GPL,libx264"
                    : "JNI-wrappers,vulkan-bindings,vulkan-filters,libkvazaar,DASH,recursive-entities,libxml2-version,openssl-version,imports-closure,clean-environment,TLS,HTTP-headers,LGPL,no-x264,no-x265");
            try (final var output = Files.newOutputStream(candidate.resolve("build.properties"))) { record.store(output, null); }
        }
        return candidates;
    }

    // RUNS THE TOOL ON root WITH THE GIVEN CANDIDATE DIRECTORIES IN A CLEAN JVM OF THE TEST'S RELEASE; A HUNG TOOL DIES AND FAILS
    private static Run repack(final Path root, final Path... candidates) throws Exception {
        final String java = Path.of(System.getProperty("java.home"), "bin", IOTool.platform().startsWith("windows-") ? "java.exe" : "java").toString();
        final List<String> command = new ArrayList<>(List.of(java, Path.of("tools/RepackFFmpeg.java").toAbsolutePath().toString(), root.toString()));
        for (final Path candidate: candidates) command.add(candidate.toString());
        final Path log = root.resolve("process.log");
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        final boolean exited;
        try {
            exited = process.waitFor(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        final String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertTrue(exited, "RepackFFmpeg did not finish in " + TOOL_TIMEOUT_MS + " ms:\n" + output);
        return new Run(process.exitValue(), output);
    }

    private static Properties load(final Path file) throws Exception {
        final Properties properties = new Properties();
        try (final var input = Files.newInputStream(file)) { properties.load(input); }
        return properties;
    }

    private static void assertInstalledArchivesKept(final Path root) throws Exception {
        for (final String archive: ARCHIVES)
            assertEquals("existing " + archive, Files.readString(root.resolve("src/main/resources/libs/ffmpeg-" + archive + ".zip")), archive + " was replaced");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Accepted candidates")
    class Accepted {
        @TempDir Path directory;

        @Test
        @DisplayName("LGPL candidates produce a complete manifest and version markers")
        void lgplCandidatesProduceCompleteManifestAndMarkers() throws Exception {
            final Run run = repack(this.directory, candidates(this.directory, false, false, false));
            assertEquals(0, run.exit(), run.log());
            final Properties manifest = load(this.directory.resolve("tools/ffmpeg-manifest.properties"));
            assertEquals("lgpl", manifest.getProperty("variant"));
            assertEquals("LGPL-3.0-or-later", manifest.getProperty("license"));
            assertEquals("rebuilt", manifest.getProperty("source.kind"));
            // THE JAVA WRAPPERS OF THE FIRST VERIFIED PLATFORM SHIP NEXT TO THE CORE'S DEPENDENCY, THEIR HASH IN THE MANIFEST
            assertEquals("wrapper of linux-x86_64", Files.readString(this.directory.resolve("libs/ffmpeg-" + manifest.getProperty("version") + ".jar")));
            assertEquals(manifest.getProperty("linux.wrapper.sha256"), manifest.getProperty("wrapper.sha256"));
            assertEquals(load(Path.of("gradle.properties")).getProperty("ffmpeg_source_repository"), manifest.getProperty("source.repository"));
            for (final String archive: ARCHIVES) {
                assertEquals(64, manifest.getProperty(archive + ".wrapper.sha256").length(), archive + " wrapper digest");
                assertFalse(manifest.getProperty(archive + ".source").contains("-gpl"), manifest.getProperty(archive + ".source"));
                try (final var zip = new ZipFile(this.directory.resolve("src/main/resources/libs/ffmpeg-" + archive + ".zip").toFile())) {
                    // THE MARKER IS THE LAST ENTRY, SO AN INTERRUPTED EXTRACTION NEVER LOOKS COMPLETE
                    final var entries = zip.stream().toList();
                    assertEquals("version.cfg", entries.get(entries.size() - 1).getName(), archive + " ends with its marker");
                    try (final var input = zip.getInputStream(entries.get(entries.size() - 1))) {
                        assertEquals(manifest.getProperty("version"), new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }

        @Test
        @DisplayName("Custom GPL candidates keep their declared version and variant")
        void customGplCandidatesKeepTheirDeclaredVersionAndVariant() throws Exception {
            final Run run = repack(this.directory, candidates(this.directory, false, false, true));
            assertEquals(0, run.exit(), run.log());
            final Properties manifest = load(this.directory.resolve("tools/ffmpeg-manifest.properties"));
            assertEquals("9.0-custom-1.5.14", manifest.getProperty("version"));
            assertEquals("gpl", manifest.getProperty("variant"));
            assertEquals("GPL-3.0-or-later", manifest.getProperty("license"));
            for (final String archive: ARCHIVES) {
                try (final var zip = new ZipFile(this.directory.resolve("src/main/resources/libs/ffmpeg-" + archive + ".zip").toFile())) {
                    // SEVEN COMPONENTS WITH THEIR JNI LIBRARIES AND THE MARKER; A GPL CANDIDATE CARRIES NO SUPPORT LIBRARIES
                    assertEquals(15, zip.size(), archive + " entries");
                    try (final var input = zip.getInputStream(zip.getEntry("version.cfg"))) {
                        assertEquals("9.0-custom-1.5.14", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }

        @Test
        @DisplayName("Undeclared provenance neither blocks custom candidates nor writes null metadata")
        void undeclaredProvenanceDoesNotBlockCustomCandidatesOrWriteNullMetadata() throws Exception {
            final Path candidates = candidates(this.directory, false, false, true);
            final Path configuration = this.directory.resolve("gradle.properties");
            final Properties properties = load(configuration);
            for (final String key: new String[] { "ffmpeg_source_ref", "ffmpeg_source_commit", "ffmpeg_source_sha256",
                    "libxml2_version", "libxml2_sha256", "openssl_version", "openssl_sha256",
                    "vulkan_headers_version", "vulkan_headers_sha256" })
                properties.remove(key);
            try (final var output = Files.newOutputStream(configuration)) { properties.store(output, null); }
            final Run run = repack(this.directory, candidates);
            assertEquals(0, run.exit(), run.log());
            final Path manifestFile = this.directory.resolve("tools/ffmpeg-manifest.properties");
            final Properties manifest = load(manifestFile);
            assertNull(manifest.getProperty("source.ref"));
            assertNull(manifest.getProperty("source.commit"));
            assertNull(manifest.getProperty("libxml2.version"));
            assertNull(manifest.getProperty("openssl.version"));
            assertNull(manifest.getProperty("vulkan.headers.version"));
            assertFalse(Files.readString(manifestFile).contains("=null"), "the manifest wrote a null value");
            // WITHOUT A DECLARED REF THE SOURCE FALLS BACK TO THE VERSION
            assertEquals("rebuilt:9.0-custom-1.5.14/ffmpeg-9.0-custom-1.5.14-linux-x86_64-gpl.jar", manifest.getProperty("linux.source"));
        }
    }

    @Nested
    @DisplayName("Rejected candidates")
    class Rejected {
        @TempDir Path directory;

        @Test
        @DisplayName("A single argument is refused with the usage text and writes nothing")
        void singleArgumentPrintsTheUsageAndWritesNothing() throws Exception {
            // VERIFIED CANDIDATES SIT IN THE ROOT, BUT THE TOOL NEVER GUESSES WHERE THEY ARE
            candidates(this.directory, false, false, false);
            final Path manifest = Files.createDirectories(this.directory.resolve("tools")).resolve("ffmpeg-manifest.properties");
            Files.writeString(manifest, "existing manifest");
            final Run run = repack(this.directory);
            assertNotEquals(0, run.exit(), run.log());
            assertTrue(run.log().contains("Usage: RepackFFmpeg <binaries-root> <verified-candidate-directory>"), run.log());
            assertInstalledArchivesKept(this.directory);
            assertEquals("existing manifest", Files.readString(manifest));
            assertFalse(Files.exists(this.directory.resolve("build")), "the tool staged packages");
            assertFalse(Files.exists(this.directory.resolve("libs")), "the tool copied a wrapper");
        }

        @Test
        @DisplayName("A candidate archive named outside its folder is refused and keeps every installed archive")
        void archiveNameCannotLeaveItsCandidate() throws Exception {
            final Path candidates = candidates(this.directory, false, false, false);
            final Path candidate = candidates.resolve("linux-x86_64");
            final Path recordPath = candidate.resolve("build.properties");
            final Properties record = load(recordPath);
            // THE TRAVERSAL TARGET IS A COPY OF THE VERIFIED JAR WITH THE SAME DIGEST, SO ONLY THE NAME CHECK CAN REFUSE IT
            Files.copy(candidate.resolve(record.getProperty("archive")), candidates.resolve("other.jar"));
            record.setProperty("archive", "../other.jar");
            try (final var output = Files.newOutputStream(recordPath)) { record.store(output, null); }
            final Run run = repack(this.directory, candidates);
            assertNotEquals(0, run.exit(), run.log());
            assertTrue(run.log().contains("Candidate version or native verification record does not match: "
                    + candidate.toAbsolutePath().normalize()), run.log());
            assertInstalledArchivesKept(this.directory);
            assertFalse(Files.exists(this.directory.resolve("tools/ffmpeg-manifest.properties")), "the tool wrote a manifest");
        }

        @Test
        @DisplayName("An invalid last candidate keeps every installed archive")
        void invalidFinalCandidatePreservesEveryInstalledArchive() throws Exception {
            final Run run = repack(this.directory, candidates(this.directory, true, false, false));
            assertNotEquals(0, run.exit(), run.log());
            // THE FIRST FOUR PLATFORMS STAGED THEIR PACKAGES BEFORE THE LAST RECORD FAILED
            assertTrue(run.log().contains("ffmpeg-macos-arm64.zip:"), run.log());
            assertTrue(run.log().contains("Candidate version or native verification record does not match"), run.log());
            assertInstalledArchivesKept(this.directory);
            assertFalse(Files.exists(this.directory.resolve("tools/ffmpeg-manifest.properties")), "the tool wrote a manifest");
        }

        @Test
        @DisplayName("A missing JNI component keeps the archives and the manifest")
        void missingJniComponentPreservesArchivesAndManifest() throws Exception {
            final Path candidates = candidates(this.directory, false, true, false);
            final Path manifest = Files.createDirectories(this.directory.resolve("tools")).resolve("ffmpeg-manifest.properties");
            Files.writeString(manifest, "existing manifest");
            final Run run = repack(this.directory, candidates);
            assertNotEquals(0, run.exit(), run.log());
            assertTrue(run.log().contains("Missing native or JNI component: avcodec"), run.log());
            assertInstalledArchivesKept(this.directory);
            assertEquals("existing manifest", Files.readString(manifest));
        }

        @Test
        @DisplayName("A candidate without the declared headers patch digest keeps the archives and the manifest")
        void candidateCannotOmitTheHeadersPatch() throws Exception {
            final Path candidates = candidates(this.directory, false, false, false);
            final Path recordPath = candidates.resolve("linux-x86_64/build.properties");
            final Properties record = load(recordPath);
            record.remove("headers.patch.sha256");
            try (final var output = Files.newOutputStream(recordPath)) { record.store(output, null); }
            final Path manifest = Files.createDirectories(this.directory.resolve("tools")).resolve("ffmpeg-manifest.properties");
            Files.writeString(manifest, "existing manifest");
            final Run run = repack(this.directory, candidates);
            assertNotEquals(0, run.exit(), run.log());
            assertTrue(run.log().contains("Candidate provenance mismatch for headers.patch.sha256"), run.log());
            assertInstalledArchivesKept(this.directory);
            assertEquals("existing manifest", Files.readString(manifest));
        }

        @Test
        @DisplayName("A candidate variant must match the declared build")
        void candidateVariantMustMatchDeclaredBuild() throws Exception {
            final Path candidates = candidates(this.directory, false, false, false);
            final Path recordPath = candidates.resolve("linux-x86_64/build.properties");
            final Properties record = load(recordPath);
            record.setProperty("variant", "gpl");
            record.setProperty("license", "GPL-3.0-or-later");
            try (final var output = Files.newOutputStream(recordPath)) { record.store(output, null); }
            final Run run = repack(this.directory, candidates);
            assertNotEquals(0, run.exit(), run.log());
            assertTrue(run.log().contains("Candidate version or native verification record does not match"), run.log());
            assertInstalledArchivesKept(this.directory);
        }
    }

    // ONE RUN OF THE TOOL: ITS EXIT CODE AND ITS OUTPUT
    private record Run(int exit, String log) {}
}
