package com.gmscpt.server.config;

import java.security.SecureRandom;

/**
 * 本次启动的访问令牌。
 *
 * <p>为什么需要它：服务监听在本机回环上，但**浏览器里的任意网页**都能向
 * {@code http://127.0.0.1:8790} 发请求（CSRF / DNS rebinding）。一旦被利用，
 * 攻击页面可以让本地服务注入键鼠、跑脚本。旧版完全没有这层防护。
 * 每次启动随机生成，写进启动 URL，前端存 sessionStorage 后走请求头。
 */
public class AccessToken {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final String value;

    public AccessToken() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        this.value = sb.toString();
    }

    public String value() {
        return value;
    }

    public boolean matches(String candidate) {
        return candidate != null && constantTimeEquals(value, candidate);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
