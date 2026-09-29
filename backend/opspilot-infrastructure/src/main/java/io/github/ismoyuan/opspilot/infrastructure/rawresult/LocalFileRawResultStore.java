package io.github.ismoyuan.opspilot.infrastructure.rawresult;

import io.github.ismoyuan.opspilot.application.capability.raw.RawResultStore;
import io.github.ismoyuan.opspilot.application.capability.sanitize.SanitizedText;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 本地目录实现（07 §94～§95）：{@code <root>/incident-<incidentId>/invocation-<invocationId>-<随机后缀>.json.gz}，gzip 压缩的
 * UTF-8 文本，引用为该文件的绝对 {@code file://} URI（满足 V003 raw_result_ref 的 CHECK）。先写同目录临时文件再原子改名，不留下半个文件；
 * 随机后缀使每次保存都是新文件——数据库重建后编号重复也不会覆盖或误读旧文件。写入前确认事件目录的真实路径在根内，读取也只接受根内
 * （按真实路径判断，含符号链接）的文件，二者约束一致：写出的引用总能读回。
 * 不引入对象存储（04 §92）。
 */
public final class LocalFileRawResultStore implements RawResultStore {

    /** 与 capability_invocation.raw_result_ref 列长度一致。 */
    static final int REF_MAX = 512;

    private final Path root;

    public LocalFileRawResultStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String store(long incidentId, long invocationId, SanitizedText content) {
        if (incidentId <= 0 || invocationId <= 0) {
            throw new IllegalArgumentException("incident and invocation ids must be positive");
        }
        Path directory = root.resolve("incident-" + incidentId);
        Path target = directory.resolve("invocation-" + invocationId + "-" + UUID.randomUUID() + ".json.gz");
        String ref = target.toUri().toString();
        if (ref.length() > REF_MAX) {
            throw new IllegalStateException("raw result reference exceeds " + REF_MAX + " characters");
        }
        try {
            Files.createDirectories(directory);
            // 事件目录可能是指向根外的符号链接（B15-R1）：按真实路径确认仍在根内再写，保证写入位置与 read 的约束一致
            if (!directory.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalStateException("raw result directory resolves outside the configured root");
            }
            Path temp = Files.createTempFile(directory, "invocation-" + invocationId + "-", ".tmp");
            try {
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
                    out.write(content.content().getBytes(StandardCharsets.UTF_8));
                }
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("raw result could not be stored for invocation " + invocationId, ex);
        }
        return ref;
    }

    @Override
    public String read(String ref) {
        Path path = resolveInsideRoot(ref);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("raw result could not be read", ex);
        }
    }

    /** 先按规范化路径判断，再按真实路径判断（防止根目录内的符号链接指向外部）。 */
    private Path resolveInsideRoot(String ref) {
        Path path = toPath(ref);
        if (!path.startsWith(root)) {
            throw notOurs();
        }
        boolean inside;
        try {
            inside = path.toRealPath().startsWith(root.toRealPath());
        } catch (IOException ex) {
            throw new UncheckedIOException("raw result could not be read", ex);
        }
        if (!inside) {
            throw notOurs();
        }
        return path;
    }

    private static Path toPath(String ref) {
        if (ref == null) {
            throw notOurs();
        }
        URI uri;
        try {
            uri = new URI(ref);
        } catch (URISyntaxException ex) {
            throw notOurs();
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw notOurs();
        }
        try {
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (IllegalArgumentException | FileSystemNotFoundException ex) {
            throw notOurs();
        }
    }

    private static IllegalArgumentException notOurs() {
        return new IllegalArgumentException("not a raw result reference of this store");
    }
}
