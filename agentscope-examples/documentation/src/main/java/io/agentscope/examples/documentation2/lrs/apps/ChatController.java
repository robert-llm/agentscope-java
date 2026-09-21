/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.documentation2.lrs.apps;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * REST controller for the Router Agent HTTP API.
 *
 * <p>This controller simply receives HTTP requests and forwards them to the Router Agent.
 * The Router Agent handles all routing logic internally via its RoutingTools.
 *
 * <p>Two API modes are supported:
 * <ul>
 *   <li>{@code POST /api/chat} — blocking, returns full response JSON
 *   <li>{@code POST /api/chat/stream} — SSE streaming, emits real-time {@link AgentEvent}s
 * </ul>
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /**
     * Minimum character count before flushing buffered text deltas to the SSE client.
     * Smaller values = more events (lower latency); larger values = fewer events (less overhead).
     * Configurable via {@code sse.delta.min-chunk-size} in application.properties.
     */
    @Value("${sse.delta.min-chunk-size:50}")
    private int minDeltaChunkSize;

    @Autowired private HarnessAgent routerAgent;

    private final AtomicInteger sessionCounter = new AtomicInteger(0);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void logConfig() {
        log.info("SSE delta buffer: min-chunk-size={}", minDeltaChunkSize);
    }

    /**
     * POST /api/chat — Send a message to the router agent.
     *
     * <p>The router agent will analyze the request, discover available teams,
     * and route the task to the appropriate team.
     *
     * <pre>
     * curl -X POST http://localhost:18096/api/chat \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "调查订单 O-1001"}'
     * </pre>
     */
    @PostMapping("/api/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        log.info(">>> [API Request] message={}", message);

        try {
            Msg input = Msg.builder().role(MsgRole.USER).textContent(message).build();
            String sessionId = "api-session-" + sessionCounter.incrementAndGet();
            RuntimeContext rc = RuntimeContext.builder().sessionId(sessionId).build();

            log.info("Calling router-agent (session={})...", sessionId);
            long t0 = System.currentTimeMillis();

            Msg response = routerAgent.call(input, rc).block(Duration.ofMinutes(2));

            long elapsed = System.currentTimeMillis() - t0;
            String reply = response != null ? response.getTextContent() : "";
            log.info("<<< [API Response] ({}ms) {}", elapsed, reply);

            return ResponseEntity.ok(Map.of("reply", reply, "sessionId", sessionId));
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException
                    || e.getMessage() != null && e.getMessage().contains("Timeout")) {
                log.error("API chat timed out (2 min)");
                return ResponseEntity.status(504).body(Map.of("error", "Agent timed out (2 min)"));
            }
            log.error("API chat failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/chat/stream — Send a message and receive real-time SSE events.
     *
     * <p>Each SSE {@code data} frame is a JSON-serialized {@link AgentEvent}. Key event types:
     * <ul>
     *   <li>{@code AGENT_START} — agent begins processing
     *   <li>{@code TEXT_BLOCK_DELTA} — incremental text content (buffered, minimum chunk
     *       size controlled by {@code sse.delta.min-chunk-size} property, default 50 chars)
     *   <li>{@code TOOL_CALL_START} / {@code TOOL_CALL_END} — tool invocation lifecycle
     *   <li>{@code TOOL_RESULT_TEXT_DELTA} — incremental tool output
     *   <li>{@code AGENT_RESULT} — final result message
     *   <li>{@code AGENT_END} — agent finished processing
     * </ul>
     *
     * <pre>
     * curl -N -X POST http://localhost:18096/api/chat/stream \
     *   -H "Content-Type: application/json" \
     *   -d '{"message": "调查订单 O-1001"}'
     * </pre>
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send(
                        SseEmitter.event()
                                .name("error")
                                .data(Map.of("error", "message is required")));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(ignored);
            }
            return emitter;
        }

        // 2-minute timeout for long-running agent tasks
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
        String sessionId = "sse-session-" + sessionCounter.incrementAndGet();

        log.info(">>> [SSE Stream] message={}, session={}", message, sessionId);

        Msg input = Msg.builder().role(MsgRole.USER).textContent(message).build();
        RuntimeContext rc = RuntimeContext.builder().sessionId(sessionId).build();

        Flux<AgentEvent> eventFlux = routerAgent.streamEvents(input, rc);

        // Text-delta buffer: accumulates small TEXT_BLOCK_DELTA events and
        // emits them as larger chunks to reduce SSE event count.
        final StringBuilder textBuffer = new StringBuilder();
        final String[] bufferMeta = new String[2]; // [0]=replyId, [1]=blockId

        eventFlux.subscribe(
                event -> {
                    try {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            textBuffer.append(delta.getDelta());
                            if (bufferMeta[0] == null) {
                                bufferMeta[0] = delta.getReplyId();
                                bufferMeta[1] = delta.getBlockId();
                            }
                            if (textBuffer.length() >= minDeltaChunkSize) {
                                TextBlockDeltaEvent chunk =
                                        new TextBlockDeltaEvent(
                                                bufferMeta[0],
                                                bufferMeta[1],
                                                textBuffer.toString());
                                String json = objectMapper.writeValueAsString(chunk);
                                emitter.send(
                                        SseEmitter.event().name("TEXT_BLOCK_DELTA").data(json));
                                textBuffer.setLength(0);
                                bufferMeta[0] = null;
                                bufferMeta[1] = null;
                            }
                        } else if (event instanceof TextBlockEndEvent) {
                            // Flush remaining buffer before forwarding end event
                            if (textBuffer.length() > 0) {
                                TextBlockDeltaEvent chunk =
                                        new TextBlockDeltaEvent(
                                                bufferMeta[0],
                                                bufferMeta[1],
                                                textBuffer.toString());
                                String json = objectMapper.writeValueAsString(chunk);
                                emitter.send(
                                        SseEmitter.event().name("TEXT_BLOCK_DELTA").data(json));
                                textBuffer.setLength(0);
                                bufferMeta[0] = null;
                                bufferMeta[1] = null;
                            }
                            String json = objectMapper.writeValueAsString(event);
                            emitter.send(
                                    SseEmitter.event().name(event.getType().name()).data(json));
                        } else {
                            // Non-text event: flush buffer first, then forward
                            if (textBuffer.length() > 0) {
                                TextBlockDeltaEvent chunk =
                                        new TextBlockDeltaEvent(
                                                bufferMeta[0],
                                                bufferMeta[1],
                                                textBuffer.toString());
                                String json = objectMapper.writeValueAsString(chunk);
                                emitter.send(
                                        SseEmitter.event().name("TEXT_BLOCK_DELTA").data(json));
                                textBuffer.setLength(0);
                                bufferMeta[0] = null;
                                bufferMeta[1] = null;
                            }
                            String json = objectMapper.writeValueAsString(event);
                            emitter.send(
                                    SseEmitter.event().name(event.getType().name()).data(json));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to send SSE event: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.error("[SSE Stream] error (session={})", sessionId, error);
                    try {
                        emitter.send(
                                SseEmitter.event()
                                        .name("error")
                                        .data(Map.of("error", error.getMessage())));
                    } catch (Exception ignored) {
                        // client may have disconnected
                    }
                    emitter.completeWithError(error);
                },
                () -> {
                    // Flush any remaining buffered text before completing
                    try {
                        if (textBuffer.length() > 0) {
                            TextBlockDeltaEvent chunk =
                                    new TextBlockDeltaEvent(
                                            bufferMeta[0], bufferMeta[1], textBuffer.toString());
                            String json = objectMapper.writeValueAsString(chunk);
                            emitter.send(SseEmitter.event().name("TEXT_BLOCK_DELTA").data(json));
                        }
                    } catch (Exception ignored) {
                        // best effort
                    }
                    log.info("<<< [SSE Stream] completed (session={})", sessionId);
                    emitter.complete();
                });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.debug("SSE connection error: {}", e.getMessage()));

        return emitter;
    }

    /** GET /health — Health check endpoint. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "agent", "router-agent"));
    }
}
