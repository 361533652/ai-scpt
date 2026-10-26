package com.gmscpt.server.api;

import com.gmscpt.server.ai.AiGateway;
import com.gmscpt.server.domain.ChatMessage;
import com.gmscpt.server.domain.ChatSession;
import com.gmscpt.server.service.ChatService;
import com.gmscpt.server.service.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话接口（SSE）+ 通道/模型/会话管理。
 *
 * <p>取消是这里的关键：旧版 SSE 读超时设成 0 且没有取消入口，
 * 上游一旦挂起，聊天输入框就永久禁用，只能重启程序。
 */
@RestController
public class AiController {

    private final ChatService chatService;
    private final Map<String, AiGateway> gateways = new LinkedHashMap<>();

    public AiController(ChatService chatService, List<AiGateway> gatewayList) {
        this.chatService = chatService;
        for (AiGateway gw : gatewayList) {
            gateways.put(gw.id(), gw);
        }
    }

    @PostMapping(value = "/api/ai/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatService.ChatInput body) {
        SseEmitter emitter = new SseEmitter(0L);
        ChatService.StreamHandle handle = chatService.openStream(body);
        Disposable[] holder = new Disposable[1];
        holder[0] = handle.frames().subscribe(
                frame -> {
                    if ("noop".equals(frame.event())) {
                        return; // 上游的 done 帧由服务层统一替换，这里跳过占位
                    }
                    try {
                        emitter.send(SseEmitter.event().name(frame.event()).data(frame.data()));
                    } catch (Exception e) {
                        emitter.complete();
                    }
                },
                error -> {
                    try {
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("code", error instanceof ServiceException se ? se.getCode() : "INTERNAL");
                        err.put("message", error.getMessage());
                        emitter.send(SseEmitter.event().name("error").data(err));
                    } catch (Exception ignored) {
                        // 连接已断，无论如何都要收尾
                    }
                    emitter.complete();
                },
                emitter::complete);
        chatService.track(handle.sessionId(), holder[0]);
        emitter.onCompletion(() -> holder[0].dispose());
        emitter.onTimeout(() -> holder[0].dispose());
        emitter.onError(e -> holder[0].dispose());
        return emitter;
    }

    @PostMapping("/api/ai/chat/stop")
    public Map<String, Object> stop(@RequestBody Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("stopped", chatService.cancelAll());
        }
        return Map.of("stopped", chatService.cancel(sessionId));
    }

    @GetMapping("/api/ai/providers")
    public Map<String, Object> providers() {
        List<Map<String, Object>> items = gateways.values().stream().map(gw -> {
            Map<String, Object> m = new LinkedHashMap<>();
            boolean available;
            List<String> models;
            try {
                available = gw.available();
                models = gw.models();
            } catch (Throwable t) {
                available = false;
                models = List.of();
            }
            m.put("id", gw.id());
            m.put("available", available);
            m.put("models", models);
            return m;
        }).toList();
        return Map.of("items", items);
    }

    @GetMapping("/api/ai/models")
    public Map<String, Object> models(@RequestParam(required = false) String providerId) {
        if (providerId == null || providerId.isBlank()) {
            Map<String, Object> all = new LinkedHashMap<>();
            gateways.forEach((id, gw) -> {
                try {
                    all.put(id, gw.models());
                } catch (Throwable t) {
                    all.put(id, List.of());
                }
            });
            return Map.of("models", all);
        }
        AiGateway gw = gateways.get(providerId);
        if (gw == null) {
            throw ServiceException.badRequest("未知的 AI 通道: " + providerId);
        }
        return Map.of("models", gw.models());
    }

    @GetMapping("/api/ai/sessions")
    public Map<String, Object> sessions() {
        return Map.of("items", chatService.sessions());
    }

    @GetMapping("/api/ai/sessions/{id}/messages")
    public Map<String, Object> messages(@PathVariable String id) {
        List<ChatMessage> items = chatService.messages(id);
        return Map.of("items", items);
    }

    @DeleteMapping("/api/ai/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        chatService.cancel(id);
        chatService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }
}
