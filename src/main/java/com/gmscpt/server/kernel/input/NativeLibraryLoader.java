package com.gmscpt.server.kernel.input;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeLibraryLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 自己实现的 JNativeHook 原生库定位器——**不打这个补丁，打包成 fat jar 后全局钩子必然失效**。
 *
 * <h2>为什么必须自己写</h2>
 * JNativeHook 2.2.2 的 {@code DefaultLibraryLocator} 这样定位原生库：
 * <pre>
 *   URL classLocation = GlobalScreen.class.getProtectionDomain().getCodeSource().getLocation();
 *   classFile = new File(classLocation.toURI());          // ← 这里
 * </pre>
 * 普通 jar 下 code source 是 {@code file:/path/jnativehook.jar}，没问题。
 * 但 Spring Boot 的可执行 jar 把依赖放在 {@code BOOT-INF/lib/} 里，code source 变成
 * {@code jar:file:/path/app.jar!/BOOT-INF/lib/jnativehook-2.2.2.jar}——
 * 这是 scheme 为 {@code jar} 的**非分层 URI**，{@code new File(uri)} 直接抛
 * {@code IllegalArgumentException: URI is not hierarchical}。
 * 而 JNativeHook 只 catch 了 {@code URISyntaxException}，抓不住这个异常，
 * 表现为类初始化失败、{@code UnsatisfiedLinkError}，最终就是
 * 「脚本显示在跑，但按什么键都没反应」。
 *
 * <p>实测证据：修好之前 {@code /api/health} 返回
 * {@code hook:{registered:false,error:"URI is not hierarchical"}}；
 * 修好之后同一环境返回 {@code registered:true}。
 *
 * <h2>做法</h2>
 * 直接从 classpath 读原生库二进制，解包到 {@code ~/.gmscpt/native/}，再交给 JVM 加载。
 * JNativeHook 用 {@code Class.forName(...).getDeclaredConstructor().newInstance()} 创建本类，
 * 因此**必须保留公开无参构造**。
 */
public final class NativeLibraryLoader implements NativeLibraryLocator {

    private static final Logger log = LoggerFactory.getLogger(NativeLibraryLoader.class);

    private static final String LIB_NAME = "JNativeHook";

    /** 公开无参构造：JNativeHook 通过反射实例化，不能用单例或私有构造。 */
    public NativeLibraryLoader() {
    }

    /**
     * 安装本定位器（幂等）。必须在**任何代码触碰 {@link GlobalScreen} 的静态成员之前**调用，
     * 因为它的静态初始化块只会执行一次，失败后就再也没有第二次机会。
     */
    public static void installIfNeeded() {
        if (System.getProperty("jnativehook.lib.locator") != null) {
            return; // 已显式指定，尊重之
        }
        if (!needsCustomLocator()) {
            return; // 普通 classpath / 普通 jar 下默认定位器工作正常
        }
        System.setProperty("jnativehook.lib.locator", NativeLibraryLoader.class.getName());
        log.info("检测到嵌套 jar 运行环境，已启用自定义原生库定位器");
    }

    /** code source 是 {@code jar:...!/...} 这类非分层 URI 时，必须自己解包。 */
    static boolean needsCustomLocator() {
        try {
            java.security.CodeSource cs = GlobalScreen.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return true;
            }
            String loc = cs.getLocation().toString();
            return loc.startsWith("jar:") || loc.contains("!/");
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public Iterator<File> getLibraries() {
        return List.of(extract()).iterator();
    }

    /** 从 classpath 解包原生库到磁盘并返回文件。 */
    static File extract() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dir = arch.contains("64") ? "x86_64" : "x86";
        String mapped = System.mapLibraryName(LIB_NAME);
        String resourcePath = "/com/github/kwhat/jnativehook/lib/windows/" + dir + "/" + mapped;

        Path targetDir = Paths.get(System.getProperty("user.home"), ".gmscpt", "native",
                "jnativehook-2.2.2-" + dir);
        Path target = targetDir.resolve(mapped);
        try {
            Files.createDirectories(targetDir);
            try (InputStream in = GlobalScreen.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IllegalStateException("classpath 里找不到原生库：" + resourcePath);
                }
                Path tmp = targetDir.resolve(mapped + ".tmp");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("原生库已解包：{}", target);
            return target.toFile();
        } catch (IOException e) {
            throw new IllegalStateException("解包 JNativeHook 原生库失败：" + e.getMessage(), e);
        }
    }
}
