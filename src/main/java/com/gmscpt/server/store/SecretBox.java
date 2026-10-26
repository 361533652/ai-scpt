package com.gmscpt.server.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 敏感配置（云端 apiKey）的落盘加密：AES-256-GCM，密钥保存在 {@code dataDir/secret.key}。
 *
 * <p>为什么不是 ADR 里写的 Windows DPAPI：DPAPI 要走 JNA 平台调用，
 * 失败路径（非 Windows / JNA 缺失 / 结构体对齐）不可控，而这个应用的核心要求是「离线可用、启动不崩」。
 * 本机密钥文件 + 强加密（带认证标签的 GCM）达到同样的效果——apiKey 不再以明文落在磁盘上，
 * 且完全用 JDK 自带能力实现，零新依赖。
 *
 * <p>密钥文件权限：POSIX 下调成 600；Windows 上继承用户目录 ACL（本机单用户场景可接受）。
 * 密钥损坏时不会让服务起不来——重命名坏文件、换新密钥（旧密文将无法解密，getSecret 返回 null 并告警）。
 */
final class SecretBox {

    private static final Logger LOG = Logger.getLogger(SecretBox.class.getName());

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;   // AES-256
    private static final int IV_BYTES = 12;    // GCM 推荐 96 bit
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final Path keyFile;

    SecretBox(Path keyFile) {
        this.keyFile = keyFile.toAbsolutePath().normalize();
        this.key = new SecretKeySpec(loadOrCreateKey(), "AES");
    }

    /** 密文格式：[12 字节 IV][密文 + 16 字节认证标签]。 */
    byte[] seal(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new StoreException("加密敏感配置失败", e);
        }
    }

    /** 解密失败抛 {@link StoreException}（认证标签不匹配 / 密钥已换），由调用方决定降级为 null。 */
    String open(byte[] blob) {
        if (blob == null || blob.length <= IV_BYTES) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new StoreException("解密敏感配置失败（密钥已更换或数据损坏）", e);
        }
    }

    private byte[] loadOrCreateKey() {
        try {
            if (Files.isRegularFile(keyFile)) {
                String text = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
                byte[] decoded = Base64.getDecoder().decode(text);
                if (decoded.length == KEY_BYTES) {
                    return decoded;
                }
                // 长度不对：不能静默换密钥（会让所有密文变成垃圾且无迹可循），先留证据再重建
                Path broken = keyFile.resolveSibling(keyFile.getFileName() + ".broken-" + System.currentTimeMillis());
                Files.move(keyFile, broken, StandardCopyOption.REPLACE_EXISTING);
                LOG.severe("密钥文件长度非法，已备份为 " + broken + "，将生成新密钥（既有密文需重新录入）");
            }
            byte[] fresh = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(fresh);
            Path tmp = Files.createTempFile(keyFile.getParent(), ".secret-", ".key.tmp");
            Files.writeString(tmp, Base64.getEncoder().encodeToString(fresh), StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            restrictPermissions(tmp);
            try {
                Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(keyFile);
            return fresh;
        } catch (IOException e) {
            throw new StoreException("无法读写密钥文件: " + keyFile, e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
                return;
            }
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException e) {
            LOG.log(Level.FINE, "设置密钥文件权限失败（非 POSIX 文件系统）: " + path, e);
        }
    }
}
