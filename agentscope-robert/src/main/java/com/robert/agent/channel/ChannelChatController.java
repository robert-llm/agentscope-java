package com.robert.agent.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Channel 流式对话 SSE 控制器。
 *
 * <p>将 {@link ChatUiChannel} 的 {@code sendStream()} 事件流桥接为前端可消费的
 * {@code text/event-stream}。支持通过 {@code agentId} 参数路由到不同 Agent。</p>
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code GET /api/channel/chat/stream} — SSE 流式对话</li>
 * </ul>
 *
 * <h3>请求参数</h3>
 * <ul>
 *   <li>{@code message} — 用户消息（必填）</li>
 *   <li>{@code userId} — 用户标识（必填，用于 session 隔离）</li>
 *   <li>{@code agentId} — 目标 Agent id（可选，默认 assistant）</li>
 *   <li>{@code sessionId} — 会话 id（可选，用于多轮对话）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/channel")
public class ChannelChatController {

    private static final Logger log = LoggerFactory.getLogger(ChannelChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatUiChannel chatUiChannel;

    public ChannelChatController(ChatUiChannel chatUiChannel) {
        this.chatUiChannel = chatUiChannel;
    }

    /**
     * SSE 流式对话端点。
     *
     * <p>前端通过 EventSource 或 fetch + ReadableStream 消费。
     * 每个 SSE data 帧为 JSON，包含 {@code type} 字段区分事件类型。</p>
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam("message") String message,
            @RequestParam("userId") String userId,
            @RequestParam(value = "agentId", required = false) String agentId,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        log.info("Channel SSE 请求: userId={}, agentId={}, sessionId={}, message={}",
                userId, agentId, sessionId, truncate(message, 80));

        // 构建 SendOptions
        SendOptions options;
        if (sessionId != null && !sessionId.isBlank()) {
            options = SendOptions.of(userId, sessionId);
        } else {
            options = SendOptions.userId(userId);
        }
        if (agentId != null && !agentId.isBlank()) {
            options = options.withAgentId(agentId);
        }

        // 通过 ChatUiChannel 的 sendStream 获取 AgentEvent 流
        Flux<AgentEvent> events = chatUiChannel.sendStream(options, message);

        // 使用 sink 桥接，确保 SSE 帧正确终止
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        events.subscribe(
                event -> {
                    String frame = toSseFrame(event);
                    if (frame != null) {
                        sink.tryEmitNext(frame);
                    }
                },
                error -> {
                    log.error("Channel SSE 错误: {}", error.getMessage(), error);
                    sink.tryEmitNext(errorFrame(error.getMessage()));
                    sink.tryEmitComplete();
                },
                () -> {
                    sink.tryEmitNext(doneFrame());
                    sink.tryEmitComplete();
                }
        );

        return sink.asFlux();
    }

    // -----------------------------------------------------------------
    //  AgentEvent → SSE JSON frame
    // -----------------------------------------------------------------

    private String toSseFrame(AgentEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", event.getType().name());
            payload.put("id", event.getId());

            if (event.getSource() != null) {
                payload.put("source", event.getSource());
            }

            if (event instanceof TextBlockDeltaEvent delta) {
                payload.put("delta", delta.getDelta());
                payload.put("blockId", delta.getBlockId());

            } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                payload.put("delta", delta.getDelta());
                payload.put("blockId", delta.getBlockId());

            } else if (event instanceof AgentStartEvent start) {
                payload.put("name", start.getName());
                payload.put("role", start.getRole());
                payload.put("sessionId", start.getSessionId());

            } else if (event instanceof AgentEndEvent end) {
                payload.put("replyId", end.getReplyId());

            } else if (event instanceof ToolCallStartEvent tc) {
                payload.put("toolCallName", tc.getToolCallName());

            } else if (event instanceof ToolResultTextDeltaEvent tr) {
                payload.put("delta", tr.getDelta());

            } else if (event instanceof SubagentExposedEvent se) {
                payload.put("subagentId", se.getSubagentId());
                payload.put("agentId", se.getAgentId());
                payload.put("label", se.getLabel());
            }

            return MAPPER.writeValueAsString(payload);

        } catch (JsonProcessingException e) {
            log.warn("SSE 事件序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String errorFrame(String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "ERROR");
            payload.put("message", message != null ? message : "unknown error");
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"ERROR\",\"message\":\"serialization error\"}";
        }
    }

    private String doneFrame() {
        return "{\"type\":\"DONE\"}";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
