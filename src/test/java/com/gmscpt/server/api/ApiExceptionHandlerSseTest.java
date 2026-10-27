package com.gmscpt.server.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 全局异常处理器在 SSE 请求上的行为。
 *
 * <p>对应一个真实刷屏 bug：客户端断开后，SSE 的收尾动作抛
 * {@code AsyncRequestNotUsableException}；如果它落到通用处理器，
 * 会尝试把 JSON 错误体写进 {@code text/event-stream} 响应，再抛
 * {@code HttpMessageNotWritableException}。结果每次关标签页都刷两条 ERROR，
 * 把真正的故障淹掉。
 */
class ApiExceptionHandlerSseTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @DisplayName("SSE 收尾异常被静默吞掉，不写任何响应体")
    void asyncNotUsableIsSwallowed() {
        AsyncRequestNotUsableException e =
                new AsyncRequestNotUsableException("Response not usable after response errors.");
        assertDoesNotThrow(() -> handler.handleAsyncNotUsable(e));
    }

    @Test
    @DisplayName("SSE 请求上的其他异常不能尝试写 JSON（否则二次报错）")
    void otherExceptionOnEventStreamReturnsNull() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        Object result = assertDoesNotThrow(
                () -> handler.handleOther(new IllegalStateException("boom"), response));
        assertNull(result, "SSE 响应上必须返回 null，绝不能返回要序列化的错误体");
    }

    @Test
    @DisplayName("普通 JSON 请求上的异常仍然返回结构化错误体")
    void otherExceptionOnJsonStillReturnsBody() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Object result = handler.handleOther(new IllegalStateException("boom"), response);
        assertNotNull(result, "非 SSE 请求必须正常返回错误体");
    }

    @Test
    @DisplayName("Content-Type 为 null 时按普通请求处理，不误判成 SSE")
    void nullContentTypeTreatedAsNormal() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNotNull(handler.handleOther(new IllegalStateException("boom"), response));
    }
}
