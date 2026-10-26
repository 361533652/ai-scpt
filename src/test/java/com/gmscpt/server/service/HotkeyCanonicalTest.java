package com.gmscpt.server.service;

import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.kernel.input.Accelerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 热键表达式规范化的回归测试。
 *
 * <p>背景（实测踩到）：接口层曾经把用户提交的原始写法直接落库，
 * 于是同一个组合会以 {@code shift+ctrl+f9} 和 {@code ctrl+shift+f9} 两种形态存在。
 * 冲突检测靠 {@code canonical()} 还能拦住，但界面显示与重启回读都对不上账。
 */
class HotkeyCanonicalTest {

    @Test
    @DisplayName("各种写法都会归一成 ctrl+alt+shift+meta 的固定顺序")
    void modifiersAreOrdered() {
        assertEquals("ctrl+shift+f9", Accelerator.parse("shift+ctrl+f9").canonical());
        assertEquals("ctrl+shift+f9", Accelerator.parse("CTRL+SHIFT+F9").canonical());
        assertEquals("ctrl+alt+shift+meta+k", Accelerator.parse("meta+shift+alt+ctrl+k").canonical());
        assertEquals("alt+f4", Accelerator.parse("alt+f4").canonical());
    }

    @Test
    @DisplayName("单键与鼠标键也走同一套规范化")
    void singleAndMouseKeys() {
        assertEquals("f8", Accelerator.parse("F8").canonical());
        assertEquals("mouse4", Accelerator.parse("MOUSE4").canonical());
        assertEquals("ctrl+mouse5", Accelerator.parse("Control+Mouse5").canonical());
    }

    @Test
    @DisplayName("规范化之后，两种写法判定为同一个热键")
    void normalizedFormsCollideAsExpected() {
        assertEquals(Accelerator.parse("shift+ctrl+f9").canonical(),
                Accelerator.parse("ctrl+shift+f9").canonical());
    }

    @Test
    @DisplayName("非法表达式在落库前就被拒，不会写进数据库")
    void invalidRejectedBeforePersist() {
        assertThrows(IllegalArgumentException.class, () -> Accelerator.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Accelerator.parse("ctrl"));
        assertThrows(IllegalArgumentException.class, () -> Accelerator.parse("f9+f10"));
        assertThrows(IllegalArgumentException.class, () -> Accelerator.parse("瞎写的键"));
    }
}
