package com.gmscpt.server.ai;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 给 Spring AI 的两个客户端装上「有界超时」的连接器。
 *
 * <p>为什么必须在这里动手：Spring AI 的 {@code OllamaApi}/{@code OpenAiApi} builder 本身没有暴露
 * 超时开关，{@code WebClient}/{@code RestClient} 的默认连接器也不设读超时。旧版正是栽在这上面
 * ——{@code readTimeout(0)} 等于永久等待，上游挂起一次聊天框就死了（ADR-0001 H4）。
 *
 * <p>为什么用 JDK 自带的 {@link HttpClient} 而不是 Netty：本项目的依赖树里<b>没有</b> reactor-netty
 * （只有 reactor-core），Spring 的 {@code DefaultWebClientBuilder.initConnector()} 在 ClassNotFound
 * 之后会回落到 {@link JdkClientHttpConnector}。与其依赖那条隐式回落链，不如显式指定。
 * 附带好处是 JDK 客户端的 {@code sendAsync} 在 Reactor 取消时会取消底层 CompletableFuture，
 * 取消语义是真的能传到 socket 的。
 */
final class AiHttp {

    private AiHttp() {
    }

    /** 带建连上限的 JDK HttpClient。 */
    static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 流式（WebClient）用：只约束建连，读侧由 Reactor 的 Flux.timeout 管。 */
    static WebClient.Builder webClient(Duration connectTimeout) {
        return WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(httpClient(connectTimeout)));
    }

    /**
     * 阻塞调用（RestClient，如 /v1/models、/api/tags）用：
     * 建连与读都有上限，避免探活把请求线程钉死。
     */
    static RestClient.Builder restClient(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient(connectTimeout));
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }
}
