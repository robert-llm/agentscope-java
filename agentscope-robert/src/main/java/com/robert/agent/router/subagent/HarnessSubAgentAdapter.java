package com.robert.agent.router.subagent;

import com.robert.agent.router.model.RouterEvent;
import com.robert.agent.router.model.SubAgentRequest;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 基于 HarnessAgent 的真实子 Agent 包装。
 *
 * <p>将 HarnessAgent 的 {@code streamEvents} 事件流适配为 {@link RouterEvent} 流，
 * RouterAgent 直接消费该 Flux 并转发到前端。</p>
 *
 * <p>处理以下事件类型：
 * <ul>
 *   <li>{@link TextBlockDeltaEvent} — 正文流式分片 → {@code RouterEvent.message}</li>
 *   <li>{@link ThinkingBlockDeltaEvent} — 思考过程分片 → {@code RouterEvent.thinkTime}</li>
 * </ul>
 * 其他事件类型被忽略（可按需扩展）。</p>
 */
public class HarnessSubAgentAdapter implements SubAgent {

    private static final Logger log = Logger.getLogger(HarnessSubAgentAdapter.class.getName());

    private final String agentId;
    private final String description;
    private final HarnessAgent harnessAgent;

    public HarnessSubAgentAdapter(String agentId, String description, HarnessAgent harnessAgent) {
        this.agentId = agentId;
        this.description = description;
        this.harnessAgent = harnessAgent;
    }

    @Override
    public String agentId() { return agentId; }

    @Override
    public String description() { return description; }

    /**
     * 流式执行：将 HarnessAgent 的事件流转换为 RouterEvent 流。
     *
     * <p>返回的 Flux 是冷流，订阅时才启动 HarnessAgent 的事件消费。
     * 每个 AgentEvent 被转换为 RouterEvent（seq=0，由 RouterAgent 统一赋值）。</p>
     *
     * <p>定制化处理点：
     * <ul>
     *   <li>{@link #enrichWithCustomData(int)} — 在 Agent 启动后注入业务 Map 数据</li>
     *   <li>{@link #appendSummary(int)} — 在流末尾追加定制文本摘要</li>
     *   <li>{@link #toRouterEvent(AgentEvent, int)} — 逐事件转换，可按需扩展更多类型</li>
     * </ul>
     * </p>
     */
    @Override
    public Flux<RouterEvent> stream(SubAgentRequest req) {
        int module = req.getModule();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("sub-" + req.getContext().getMessageId())
                .userId(req.getContext().getLoginName())
                .build();

        return harnessAgent.streamEvents(
                        List.of(new UserMessage(req.getQuestion())),
                        ctx
                )
                // 1) 逐事件转换：AgentEvent → RouterEvent
                .mapNotNull(event -> toRouterEvent(event, module))
                // 2) 在 AgentStart 之后注入自定义 Map 数据（业务定制点）
                .transform(flux -> injectCustomData(flux, module))
                // 3) 在流末尾追加定制文本摘要
                .concatWith(appendSummary(module));
    }

    /**
     * 将 HarnessAgent 的 AgentEvent 转换为 RouterEvent。
     * 无法转换的事件返回 null（由 mapNotNull 过滤）。
     *
     * <p>扩展点：如果需要转发更多事件类型（如 ToolCallStartEvent、CustomEvent 等），
     * 在这里增加新的 instanceof 分支即可。</p>
     */
    private RouterEvent toRouterEvent(AgentEvent event, int module) {
        if (event instanceof TextBlockDeltaEvent delta) {
            String token = delta.getDelta();
            if (token != null && !token.isEmpty()) {
                return RouterEvent.message(token, module, agentId, 0);
            }
        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
            String token = delta.getDelta();
            if (token != null && !token.isEmpty()) {
                return RouterEvent.thinkTime(token, module, agentId, 0);
            }
        }
        // 其他事件类型暂不转发，可按需扩展
        return null;
    }

    // ==================== 业务定制点 ====================

    /**
     * 在事件流中注入自定义 Map 数据。
     *
     * <p>示例场景：当 AgentStart 事件到达时，紧跟一个 CUSTOM_DATA 事件，
     * 携带该 SubAgent 的元信息（如能力描述、处理参数等）给前端渲染。</p>
     *
     * <p>你可以在这里实现任意业务逻辑：
     * <ul>
     *   <li>检测特定事件类型，在其后插入自定义数据</li>
     *   <li>累积统计信息，在特定节点注入汇总</li>
     *   <li>根据 AgentEvent 的 metadata 做条件判断</li>
     * </ul>
     * </p>
     */
    private Flux<RouterEvent> injectCustomData(Flux<RouterEvent> flux, int module) {
        return flux.flatMap(event -> {
            // 默认直接透传
            if (event.getType() != RouterEvent.Type.MESSAGE) {
                return Flux.just(event);
            }
            // 示例：在第一条 MESSAGE 到达时，前置一个 CUSTOM_DATA 事件
            // 实际场景中你可以根据业务条件决定是否注入
            // 这里仅作演示，实际使用时可去掉注释启用
            // Map<String, Object> bizData = buildBusinessMetadata();
            // RouterEvent dataEvent = RouterEvent.customData("agent_meta", bizData, module, agentId, 0);
            // return Flux.just(dataEvent, event);
            return Flux.just(event);
        });
    }

    /**
     * 在流末尾追加定制文本。
     *
     * <p>示例：追加来源标记、处理统计、参考链接等。
     * 前端收到这段文本后会渲染在回复末尾。</p>
     */
    private Flux<RouterEvent> appendSummary(int module) {
        // 示例：追加来源标记文本
        String summary = String.format("\n\n---\n📌 来自 %s 的处理结果", agentId);
        return Flux.just(RouterEvent.message(summary, module, agentId, 0));
    }

    /**
     * 构建业务元数据 Map — 示范如何组装结构化数据给前端。
     *
     * <p>返回的 Map 会作为 CUSTOM_DATA 事件的 payload 传到前端，
     * 前端可根据 dataName 区分不同业务数据并做差异化渲染。</p>
     */
    private Map<String, Object> buildBusinessMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("agentId", agentId);
        meta.put("description", description);
        meta.put("capabilities", List.of("text_generation", "analysis", "translation"));
        // 可以放任意业务数据
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("processingTime", System.currentTimeMillis());
        extra.put("dataSource", "internal_kb");
        meta.put("extra", extra);
        return meta;
    }
}
