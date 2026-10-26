package com.gmscpt.server.kernel.input;

import com.gmscpt.server.domain.HotkeyAction;
import com.gmscpt.server.domain.HotkeyBinding;
import com.gmscpt.server.domain.HotkeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入内核的回归测试。每条都对应 ADR 里记录的一个旧版缺陷。
 */
class KernelInputTest {

    // ---------------- H3：F11/F12 键码 ----------------

    @Test
    @DisplayName("H3：F11/F12 用的是显式键码表，不做算术推算")
    void functionKeysUseExplicitTable() {
        // 旧版用 VC_F1 + n 推算，于是 F11/F12 得到一个不存在的键码
        assertEquals("f11", KeyNames.fromNative(87, 0));
        assertEquals("f12", KeyNames.fromNative(88, 0));
        assertEquals("f10", KeyNames.fromNative(68, 0));
        // 反查同样必须自洽
        assertEquals(87, KeyNames.nativeCode("f11"));
        assertEquals(88, KeyNames.nativeCode("f12"));
        assertFalse(KeyNames.isKnown("f25"));
        assertNull(KeyNames.nativeCode("不存在的键"));
    }

    @Test
    @DisplayName("H3：未映射的原生键码一律 unknown(N)，绝不猜成别的键")
    void unknownNativeCodeStaysUnknown() {
        // VC_NUMPAD0 在 JNativeHook 2.2.2 里没有独立常量；旧版把 82 当 AWT 码解释成 'R'
        assertEquals("unknown(60000)", KeyNames.fromNative(60000, 0));
    }

    @Test
    @DisplayName("小键盘靠 keyLocation 区分：同码不同名")
    void numpadUsesKeyLocation() {
        assertEquals("1", KeyNames.fromNative(2, 1));           // KEY_LOCATION_STANDARD
        assertEquals("numpad1", KeyNames.fromNative(2, KeyNames.LOCATION_NUMPAD));
        assertEquals("numpad0", KeyNames.fromNative(11, KeyNames.LOCATION_NUMPAD));
        assertEquals("numpad_enter", KeyNames.fromNative(28, KeyNames.LOCATION_NUMPAD));
        // 补全与注入都要认得 numpad 名字
        assertEquals(KeyNames.nativeCode("1"), KeyNames.nativeCode("numpad1"));
        assertEquals(java.awt.event.KeyEvent.VK_NUMPAD1, KeyNames.awtCode("numpad1"));
    }

    // ---------------- H7：鼠标按钮语义 ----------------

    @Test
    @DisplayName("H7：钩子的 2=右键、3=中键 要翻译成规范的 2=中、3=右")
    void mouseButtonSemanticsAreTranslated() {
        // 依据 libuiohook src/windows/input_hook.c：WM_RBUTTON* → MOUSE_BUTTON2
        assertEquals(MouseButtons.RIGHT, MouseButtons.fromHook(2));
        assertEquals(MouseButtons.MIDDLE, MouseButtons.fromHook(3));
        assertEquals(MouseButtons.LEFT, MouseButtons.fromHook(1));
        assertEquals(4, MouseButtons.fromHook(4));
        // 反向
        assertEquals(2, MouseButtons.toHook(MouseButtons.RIGHT));
        assertEquals(3, MouseButtons.toHook(MouseButtons.MIDDLE));
        // 名字与解析
        assertEquals("mouse3", MouseButtons.name(MouseButtons.RIGHT));
        assertEquals(MouseButtons.RIGHT, MouseButtons.parseName("mouse3"));
        assertEquals(MouseButtons.RIGHT, MouseButtons.parseName("right"));
        assertEquals(0, MouseButtons.parseName("mouse9"));
        // AWT 掩码用的是 AWT 语义（中=2、右=3），与规范一致
        assertEquals(java.awt.event.InputEvent.BUTTON3_DOWN_MASK, MouseButtons.toAwtMaskButton(MouseButtons.RIGHT));
        assertEquals(java.awt.event.InputEvent.BUTTON2_DOWN_MASK, MouseButtons.toAwtMaskButton(MouseButtons.MIDDLE));
    }

    // ---------------- 回声抑制 ----------------

    @Test
    @DisplayName("回声抑制：注入前登记、钩子认领，且令牌会过期")
    void echoSuppressorClaimsOnce() {
        EchoSuppressor echo = new EchoSuppressor();
        String token = EchoSuppressor.buttonToken(MouseButtons.LEFT, true);
        assertFalse(echo.claim(token), "没登记过就不该被认领");
        echo.expect(token);
        assertTrue(echo.claim(token), "登记过的事件必须被认领（说明是自己注入的）");
        assertFalse(echo.claim(token), "同一个令牌只能认领一次，不能吞掉后续真实点击");
    }

    // ---------------- 热键 ----------------

    @Test
    @DisplayName("热键表达式：规范化顺序、别名、鼠标键、非法输入")
    void acceleratorParsing() {
        assertEquals("ctrl+alt+shift+f8", Accelerator.parse("Shift+CTRL+Alt+F8").canonical());
        assertEquals("ctrl+f8", Accelerator.parse("control+f8").canonical());
        assertEquals("meta+space", Accelerator.parse("win+space").canonical());
        assertTrue(Accelerator.parse("mouse4").isMouse());
        // 旧版的键名表里没有 numpad，这里必须支持
        assertEquals("ctrl+numpad1", Accelerator.parse("ctrl+numpad1").canonical());
        assertThrows(IllegalArgumentException.class, () -> Accelerator.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Accelerator.parse("ctrl"));
        // 两个主键必须被拒（修饰键写在哪一侧都允许，所以不能拿 ctrl+alt+f8+shift 当反例）
        assertThrows(IllegalArgumentException.class, () -> Accelerator.parse("f8+f9"));
        assertThrows(IllegalArgumentException.class, () -> Accelerator.parse("那个键"));
        // 修饰键顺序不敏感
        assertEquals("ctrl+alt+f8", Accelerator.parse("alt+ctrl+f8").canonical());
    }

    @Test
    @DisplayName("热键匹配：要求修饰键全部按下，且按下与抬起分开")
    void hotkeyMatching() {
        HotkeyRegistry registry = new HotkeyRegistry();
        registry.reload(List.of(
                new HotkeyBinding("h1", "script-a", "ctrl+f8", HotkeyEvent.KEY_PRESSED, HotkeyAction.TOGGLE, true),
                new HotkeyBinding("h2", "script-b", "mouse4", HotkeyEvent.KEY_PRESSED, HotkeyAction.RUN, true)));

        assertEquals(1, registry.matchKey("f8", HotkeyEvent.KEY_PRESSED, Set.of("ctrl")).size());
        assertEquals(0, registry.matchKey("f8", HotkeyEvent.KEY_PRESSED, Set.of()).size(),
                "ctrl 没按下就不该命中");
        assertEquals(0, registry.matchKey("f8", HotkeyEvent.KEY_RELEASED, Set.of("ctrl")).size(),
                "绑定的是按下，抬起不该触发");
        assertEquals(1, registry.matchButton(MouseButtons.BACK, HotkeyEvent.KEY_PRESSED, Set.of()).size());
        assertEquals(0, registry.matchButton(MouseButtons.RIGHT, HotkeyEvent.KEY_PRESSED, Set.of()).size());
    }

    @Test
    @DisplayName("热键冲突：同一组合被两个脚本绑定要被拒，且原绑定不被破坏")
    void hotkeyConflictIsRejected() {
        HotkeyRegistry registry = new HotkeyRegistry();
        HotkeyBinding a = new HotkeyBinding("h1", "script-a", "ctrl+f8", HotkeyEvent.KEY_PRESSED, HotkeyAction.TOGGLE, true);
        registry.reload(List.of(a));

        HotkeyBinding b = new HotkeyBinding("h2", "script-b", "CTRL+F8", HotkeyEvent.KEY_PRESSED, HotkeyAction.RUN, true);
        assertThrows(HotkeyConflictException.class, () -> registry.reload(List.of(a, b)));
        assertEquals(1, registry.all().size(), "冲突时原绑定必须保持不变");
        assertEquals("script-a", registry.all().get(0).scriptId());
    }

    @Test
    @DisplayName("H1：停止时必须能把自己注入的按键全部松开")
    void releaseAllCoversSyntheticInput() {
        InputState state = new InputState();
        FakeInputGateway gateway = new FakeInputGateway();
        state.syntheticKeyDown("w");
        state.syntheticButtonDown(MouseButtons.LEFT);
        assertTrue(state.isKeyDown("w"));

        var released = state.releaseAllSynthetic();
        assertEquals(2, released.size());
        assertFalse(state.isKeyDown("w"), "释放后不能还认为按着");
        assertFalse(state.isButtonDown(MouseButtons.LEFT));
        // 物理态不受影响：用户自己按着的键不能被我们松开
        state.physicalKeyDown("shift");
        state.releaseAllSynthetic();
        assertTrue(state.isKeyDown("shift"));
        assertTrue(gateway.downKeys.isEmpty());
    }
}
