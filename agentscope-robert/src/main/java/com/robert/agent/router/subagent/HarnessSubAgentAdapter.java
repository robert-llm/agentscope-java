package com.robert.agent.router.subagent;

import com.robert.agent.router.model.RouterEvent;
import com.robert.agent.router.model.SubAgentRequest;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;
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
                .mapNotNull(event -> toRouterEvent(event, module));
    }

    /**
     * 将 HarnessAgent 的 AgentEvent 转换为 RouterEvent。
     * 无法转换的事件返回 null（由 mapNotNull 过滤）。
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
}
