package com.gmscpt.server.store;

/**
 * 持久化层统一异常。
 *
 * <p>{@code *Repository} 接口没有声明受检异常，而 JDBC 全是受检异常，所以在这一层收口：
 * Spring 的 Controller 只需按运行时异常处理，不必到处 {@code try/catch SQLException}。
 * 乐观锁冲突（{@link RevConflictException}）与热键冲突
 * （{@code com.gmscpt.server.kernel.input.HotkeyConflictException}）是业务语义异常，
 * <b>不</b>会被包装成本异常，原样上抛。
 */
public class StoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
