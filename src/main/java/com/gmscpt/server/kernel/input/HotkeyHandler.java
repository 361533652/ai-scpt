package com.gmscpt.server.kernel.input;

import com.gmscpt.server.domain.HotkeyBinding;

/** 热键命中回调，由 RunService 实现（避免 input 包反向依赖 service 包）。 */
public interface HotkeyHandler {

    void onHotkey(HotkeyBinding binding);
}
