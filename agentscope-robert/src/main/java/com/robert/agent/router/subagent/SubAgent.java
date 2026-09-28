package com.robert.agent.router.subagent;

import com.robert.agent.router.model.RouterEvent;
import com.robert.agent.router.model.SubAgentRequest;
import reactor.core.publisher.Flux;

/**
 * 子 Agent 抽象：与 RouterAgent 的编排逻辑解耦。
 *
 * <p>子 Agent 以 {@link Flux} 形式输出流式事件，RouterAgent 直接消费并转发。</p>
 */
public interface SubAgent {
    String agentId();
    String description();

    /**
     * 流式执行：返回子 Agent 的事件流。
     *
     * <p>返回的 Flux 中的事件会被 RouterAgent 直接转发到前端。
     * Flux 完成（complete）表示子 Agent 执行结束，
     * 异常（error）表示子 Agent 执行失败。</p>
     */
    Flux<RouterEvent> stream(SubAgentRequest req);
}
