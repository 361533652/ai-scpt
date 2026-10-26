package com.gmscpt.server.domain;

/**
 * 热键绑定：一个脚本可以绑定多个热键，但同一个 accelerator + event 组合全局唯一。
 *
 * @param accelerator 形如 {@code ctrl+shift+f8}、{@code mouse4}、{@code f8}，统一小写、按固定顺序排列修饰键
 * @param event       触发时机（按下 / 抬起）
 * @param action      触发动作（启动 / 停止 / 切换）
 */
public record HotkeyBinding(
        String id,
        String scriptId,
        String accelerator,
        HotkeyEvent event,
        HotkeyAction action,
        boolean enabled
) {
    public HotkeyBinding {
        if (scriptId == null || scriptId.isBlank()) throw new IllegalArgumentException("scriptId 不能为空");
        if (accelerator == null || accelerator.isBlank()) throw new IllegalArgumentException("accelerator 不能为空");
        if (event == null) event = HotkeyEvent.KEY_PRESSED;
        if (action == null) action = HotkeyAction.TOGGLE;
    }
}
