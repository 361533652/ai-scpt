package com.gmscpt.server.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 脚本正文的文件存储：{@code dataDir/scripts/<id>.lua} 是**真源**，数据库只存元数据与 content_hash。
 *
 * <p>为什么正文不进数据库：Lua 正文是用户直接用编辑器打开、复制、备份的东西；
 * 放文件系统里可以被 git / 文本工具 / AI 直接读写，也避免大 TEXT 把 WAL 撑爆。
 * 代价是「文件与元数据可能不一致」，所以 {@link SqlScriptRepository} 的写路径都是
 * 「同一事务内写文件 + 更新元数据」，失败时回写旧正文做补偿（见该类注释）。
 *
 * <p>原子性：写入一律「同目录临时文件 + 强制落盘 + 原子替换」。
 * 旧版直接用 Files.write 覆盖，进程被杀会留下半截文件——正是 ADR 里点名的缺陷。
 */
public final class ScriptFileStore {

    private static final Logger LOG = Logger.getLogger(ScriptFileStore.class.getName());

    private static final String SUFFIX = ".lua";
    /** id 会直接拼进文件名，必须挡掉 {@code ../} 之类的路径穿越。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Path dir;

    /** @param dataDir 数据目录（与 {@link Database} 同一个），本类在其下建 {@code scripts/} 子目录。 */
    public ScriptFileStore(Path dataDir) {
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir 不能为空");
        }
        this.dir = dataDir.toAbsolutePath().normalize().resolve("scripts");
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new StoreException("无法创建脚本目录: " + this.dir, e);
        }
    }

    public Path dir() {
        return dir;
    }

    /** 脚本正文文件路径；id 非法时抛 {@link IllegalArgumentException}。 */
    public Path pathOf(String id) {
        requireSafeId(id);
        Path path = dir.resolve(id + SUFFIX).normalize();
        if (!path.getParent().equals(dir)) {
            throw new IllegalArgumentException("非法脚本 id: " + id);
        }
        return path;
    }

    public boolean exists(String id) {
        return Files.isRegularFile(pathOf(id));
    }

    /** 读取正文；文件不存在返回空串（新脚本 / 正文被手工删掉都按空处理，由上层决定是否用快照兜底）。 */
    public String read(String id) {
        return readIfExists(id).orElse("");
    }

    public Optional<String> readIfExists(String id) {
        Path path = pathOf(id);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new StoreException("读取脚本正文失败: " + path, e);
        }
    }

    /**
     * 原子写入正文：同目录临时文件 → fsync → 原子替换。
     * 同目录保证 rename 不跨卷（跨卷会退化成非原子的复制+删除）。
     */
    public void write(String id, String source) {
        Path target = pathOf(id);
        String text = source == null ? "" : source;
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, "." + id + "-", SUFFIX + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            // 先把内容刷到磁盘，再做替换：否则崩溃后可能留下「已替换但内容为空」的文件
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 个别文件系统（网络盘/部分容器挂载）不支持原子重命名，退化为普通替换
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            throw new StoreException("写入脚本正文失败: " + target, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanupError) {
                    LOG.log(Level.FINE, "临时文件清理失败: " + tmp, cleanupError);
                }
            }
        }
    }

    /** 删除正文文件；返回是否真的删掉了。删除失败（被占用等）不抛出，避免挡住元数据删除。 */
    public boolean delete(String id) {
        Path path = pathOf(id);
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "删除脚本正文失败: " + path, e);
            return false;
        }
    }

    /** 正文指纹，与 {@code script.content_hash} / {@code kb_entry.content_hash} 同源。 */
    public static String hash(String source) {
        return SqlSupport.sha256Hex(source);
    }

    private static void requireSafeId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()
                || ".".equals(id) || "..".equals(id)
                || id.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) {
            throw new IllegalArgumentException("非法脚本 id: " + id);
        }
    }
}
