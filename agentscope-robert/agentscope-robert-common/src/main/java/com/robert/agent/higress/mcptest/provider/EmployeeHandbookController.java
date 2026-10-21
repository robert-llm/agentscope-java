package com.robert.agent.higress.mcptest.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robert.agent.higress.mcptest.service.EmployeeHandbookService;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 员工手册知识库 Controller。
 *
 * <p>提供基于员工手册文档的 RAG 问答接口，支持流式和同步两种模式。
 *
 * @Author: luorenshu
 * @Date: 2026/10/21
 */
@RestController
@RequestMapping("/mcp/api/handbook")
@Tag(name = "EmployeeHandbook", description = "员工手册知识库问答接口")
public class EmployeeHandbookController {

    private static final Logger log = LoggerFactory.getLogger(EmployeeHandbookController.class);

    /**
     * 文本增量缓冲最小块大小，累积到该大小后再推送一次 SSE 事件，减少事件数量。
     */
    private static final int MIN_DELTA_CHUNK_SIZE = 50;

    private final EmployeeHandbookService employeeHandbookService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong sessionCounter = new AtomicLong(0);

    public EmployeeHandbookController(EmployeeHandbookService employeeHandbookService) {
        this.employeeHandbookService = employeeHandbookService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "员工手册流式对话",
            description = "基于员工手册知识库的流式问答接口，通过 SSE 实时推送回答内容")
    public SseEmitter handbookStream(@RequestBody HandbookChatRequest request) {
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("error", "message 参数不能为空")));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(ignored);
            }
            return emitter;
        }

        if (!employeeHandbookService.isReady()) {
            SseEmitter emitter = new SseEmitter(1000L);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("error", "员工手册知识库服务未初始化，请检查配置")));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(ignored);
            }
            return emitter;
        }

        // 5 分钟超时，知识库问答可能需要较长时间
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        String sessionId = "handbook-session-" + sessionCounter.incrementAndGet();
        log.info(">>> [员工手册流式对话] message={}, session={}", message, sessionId);

        // 发送开始事件
        try {
            emitter.send(SseEmitter.event()
                    .name("start")
                    .data(Map.of("status", "processing", "sessionId", sessionId)));
        } catch (Exception e) {
            log.warn("发送 start 事件失败: {}", e.getMessage());
        }

        Flux<AgentEvent> eventFlux = employeeHandbookService.streamChat(message);

        // 文本增量缓冲区：累积小段 TextBlockDelta 事件后合并推送，减少 SSE 事件数量
        final StringBuilder textBuffer = new StringBuilder();

        eventFlux.subscribe(
                event -> {
                    try {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            textBuffer.append(delta.getDelta());
                            if (textBuffer.length() >= MIN_DELTA_CHUNK_SIZE) {
                                emitter.send(SseEmitter.event()
                                        .name("TEXT_BLOCK_DELTA")
                                        .data(Map.of("delta", textBuffer.toString())));
                                textBuffer.setLength(0);
                            }
                        } else if (event instanceof TextBlockEndEvent) {
                            // 刷新缓冲区中剩余的文本
                            if (textBuffer.length() > 0) {
                                emitter.send(SseEmitter.event()
                                        .name("TEXT_BLOCK_DELTA")
                                        .data(Map.of("delta", textBuffer.toString())));
                                textBuffer.setLength(0);
                            }
                            emitter.send(SseEmitter.event()
                                    .name("TEXT_BLOCK_END")
                                    .data(Map.of("type", "TEXT_BLOCK_END")));
                        } else {
                            // 其他事件：先刷新缓冲区，再转发
                            if (textBuffer.length() > 0) {
                                emitter.send(SseEmitter.event()
                                        .name("TEXT_BLOCK_DELTA")
                                        .data(Map.of("delta", textBuffer.toString())));
                                textBuffer.setLength(0);
                            }
                            String json = objectMapper.writeValueAsString(event);
                            emitter.send(SseEmitter.event()
                                    .name(event.getType().name())
                                    .data(json));
                        }
                    } catch (Exception e) {
                        log.warn("发送 SSE 事件失败: {}", e.getMessage());
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.error("[员工手册流式对话] 错误 (session={})", sessionId, error);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(Map.of("error", error.getMessage())));
                    } catch (Exception ignored) {
                        // 客户端可能已断开连接
                    }
                    emitter.completeWithError(error);
                },
                () -> {
                    // 刷新剩余缓冲文本
                    try {
                        if (textBuffer.length() > 0) {
                            emitter.send(SseEmitter.event()
                                    .name("TEXT_BLOCK_DELTA")
                                    .data(Map.of("delta", textBuffer.toString())));
                        }
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data(Map.of("status", "completed", "sessionId", sessionId)));
                    } catch (Exception ignored) {
                        // best effort
                    }
                    log.info("<<< [员工手册流式对话] 完成 (session={})", sessionId);
                    emitter.complete();
                });

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.debug("SSE 连接异常: {}", e.getMessage()));

        return emitter;
    }

    @PostMapping(value = "/chat")
    @Operation(summary = "员工手册同步问答",
            description = "基于员工手册知识库的同步问答接口，返回完整回答")
    public Map<String, Object> handbookChat(@RequestBody HandbookChatRequest request) {
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return Map.of("error", "message 参数不能为空");
        }

        if (!employeeHandbookService.isReady()) {
            return Map.of("error", "员工手册知识库服务未初始化，请检查配置");
        }

        log.info("[员工手册同步问答] message={}", message);

        try {
            Msg response = employeeHandbookService.chat(message);
            String replyText = "";
            if (response != null && response.getContent() != null) {
                replyText = response.getContent().stream()
                        .filter(block -> block instanceof io.agentscope.core.message.TextBlock)
                        .map(block -> ((io.agentscope.core.message.TextBlock) block).getText())
                        .reduce("", (a, b) -> a + b);
            }
            return Map.of(
                    "reply", replyText,
                    "status", "success"
            );
        } catch (Exception e) {
            log.error("[员工手册同步问答] 错误", e);
            return Map.of("error", "问答失败: " + e.getMessage(), "status", "error");
        }
    }

    @GetMapping("/status")
    @Operation(summary = "员工手册服务状态", description = "检查员工手册知识库服务是否已就绪")
    public Map<String, Object> handbookStatus() {
        return Map.of(
                "ready", employeeHandbookService.isReady(),
                "service", "employee-handbook-rag"
        );
    }
}
