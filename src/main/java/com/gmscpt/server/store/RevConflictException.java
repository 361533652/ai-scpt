package com.gmscpt.server.store;

/** 乐观锁冲突：客户端提交的 rev 已经过期。 */
public class RevConflictException extends RuntimeException {

    private final long expected;
    private final long actual;

    public RevConflictException(String scriptId, long expected, long actual) {
        super("脚本 " + scriptId + " 的 rev 已过期：客户端提交 " + expected + "，当前 " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public long getExpected() {
        return expected;
    }

    public long getActual() {
        return actual;
    }
}
