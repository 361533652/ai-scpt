package com.gmscpt.server.kernel.input;

/** 同一个热键表达式被绑定了两次（或 `RUN`/`TOGGLE` 动作冲突）。 */
public class HotkeyConflictException extends RuntimeException {

    private final String accelerator;

    public HotkeyConflictException(String accelerator, String scriptIdA, String scriptIdB) {
        super("热键 " + accelerator + " 已被脚本 " + scriptIdA + " 占用，与 " + scriptIdB + " 冲突");
        this.accelerator = accelerator;
    }

    public String getAccelerator() {
        return accelerator;
    }
}
