package com.gmscpt.server;

import com.gmscpt.server.config.GmScriptProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * GmScript 入口：本地后端服务（前端是浏览器里的 SPA）。
 *
 * <p>扫描范围仍显式收在 {@code com.gmscpt.server}。旧版 Swing 代码（{@code com.gmscpt.gui}
 * 等，见提交 {@code fe0178c}）已在本版删除，但从 {@code com.gmscpt} 根包扫描会把
 * 未来的测试夹具、示例代码一并卷进来；显式限定范围是刻意的约束，不是遗留。
 */
@SpringBootApplication(scanBasePackages = "com.gmscpt.server")
@EnableConfigurationProperties(GmScriptProperties.class)
public class GmScriptApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GmScriptApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
