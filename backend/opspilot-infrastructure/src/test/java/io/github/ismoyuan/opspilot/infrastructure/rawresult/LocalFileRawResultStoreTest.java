package io.github.ismoyuan.opspilot.infrastructure.rawresult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizedText;
import io.github.ismoyuan.opspilot.application.capability.sanitize.Sanitizer;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizerSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 08 TASK-050、06 §32、07 §94～§95：只保存已脱敏文本、file:// 引用、gzip 文件、从不覆盖、只读根目录内文件。 */
class LocalFileRawResultStoreTest {

    /** V003 ck_capability_invocation_raw_result_ref。 */
    static final Pattern REF_CHECK = Pattern.compile("^file://[^\\s]+$");

    private final Sanitizer sanitizer = new Sanitizer(new SanitizerSettings(true));

    @TempDir
    Path temp;

    @Test
    void storesSanitizedTextAsGzipAndReadsItBack() throws IOException {
        LocalFileRawResultStore store = new LocalFileRawResultStore(temp.resolve("raw"));
        SanitizedText content = sanitizer.sanitizeRaw("{\"line\":\"login password=hunter2 ok\",\"n\":\"短链接\"}");

        String ref = store.store(7, 81, content);

        assertThat(ref).matches(REF_CHECK).matches(".*/raw/incident-7/invocation-81-[0-9a-f-]{36}\\.json\\.gz");
        Path file = Path.of(URI.create(ref));
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes[0] & 0xff).isEqualTo(0x1f);
        assertThat(bytes[1] & 0xff).isEqualTo(0x8b);
        assertThat(store.read(ref))
                .isEqualTo("{\"line\":\"login password=[REDACTED] ok\",\"n\":\"短链接\"}")
                .doesNotContain("hunter2");
        try (var files = Files.list(file.getParent())) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    void aDirectoryWithSpacesStillYieldsAWhitespaceFreeReference() {
        LocalFileRawResultStore store = new LocalFileRawResultStore(temp.resolve("raw results"));
        String ref = store.store(1, 2, sanitizer.sanitizeRaw("x"));
        assertThat(ref).matches(REF_CHECK).contains("raw%20results");
        assertThat(store.read(ref)).isEqualTo("x");
    }

    /** 数据库重建后同一编号再次保存：得到新文件，旧文件不被覆盖。 */
    @Test
    void storingNeverOverwritesAnExistingFile() {
        LocalFileRawResultStore store = new LocalFileRawResultStore(temp);
        String first = store.store(1, 2, sanitizer.sanitizeRaw("first"));
        String second = store.store(1, 2, sanitizer.sanitizeRaw("second"));
        assertThat(second).isNotEqualTo(first);
        assertThat(store.read(first)).isEqualTo("first");
        assertThat(store.read(second)).isEqualTo("second");
    }

    @Test
    void onlyReferencesInsideTheRootAreRead() throws IOException {
        Path root = Files.createDirectories(temp.resolve("root"));
        LocalFileRawResultStore store = new LocalFileRawResultStore(root);
        Path outside = temp.resolve("outside.json.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(outside))) {
            out.write("secret".getBytes(StandardCharsets.UTF_8));
        }
        Files.createDirectories(root.resolve("incident-1"));
        Path link = Files.createSymbolicLink(root.resolve("incident-1/invocation-9.json.gz"), outside);

        for (String ref : new String[] {
            outside.toUri().toString(),
            root.toUri() + "../outside.json.gz",
            link.toUri().toString(),
            "http://example.com/raw.json.gz",
            "file://host/share/raw.json.gz",
            "not a uri",
            null
        }) {
            assertThatThrownBy(() -> store.read(ref))
                    .as(String.valueOf(ref))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.read(
                        root.resolve("incident-1/missing.json.gz").toUri().toString()))
                .isInstanceOf(UncheckedIOException.class);
    }

    /** B15-R1：事件目录是指向根外的符号链接时拒绝写入，根外不留下文件；指向根内的链接可写且引用可读回。 */
    @Test
    void writesNeverFollowALinkOutOfTheRoot() throws IOException {
        Path root = Files.createDirectories(temp.resolve("root"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("incident-1"), outside);
        LocalFileRawResultStore store = new LocalFileRawResultStore(root);

        assertThatThrownBy(() -> store.store(1, 2, sanitizer.sanitizeRaw("x")))
                .isInstanceOf(IllegalStateException.class);
        try (var files = Files.list(outside)) {
            assertThat(files).isEmpty();
        }

        Path shared = Files.createDirectories(root.resolve("shared"));
        Files.createSymbolicLink(root.resolve("incident-3"), shared);
        String ref = store.store(3, 4, sanitizer.sanitizeRaw("inside"));
        assertThat(store.read(ref)).isEqualTo("inside");
    }

    @Test
    void overlongReferencesAreRejectedBeforeWriting() {
        Path deep = temp.resolve("d".repeat(250)).resolve("e".repeat(250));
        LocalFileRawResultStore store = new LocalFileRawResultStore(deep);
        assertThatThrownBy(() -> store.store(1, 2, sanitizer.sanitizeRaw("x")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(deep)).isFalse();
    }
}
