package com.robert.agent.router.subagent;

import com.robert.agent.router.model.RouterEvent;
import com.robert.agent.router.model.SubAgentRequest;
import reactor.core.publisher.Flux;

/**
 * 子 Agent 抽象：与 RouterAgent 的编排逻辑解耦。
 *
 * <p>子 Agent 以 {@link Flux} 形式输出流式事件，RouterAgent 直接消费并转发。</p>
 *
 * <p>{@link SubAgentContext} 是在 Agent 链路中层层传递的共享上下文，
 * 每个 SubAgent 可以读取上游数据、写入中间结果供下游消费。</p>
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
     *
     * @param req     子 Agent 请求（包含用户问题、会话信息等）
     * @param context 流转上下文（包含上游 Agent 的中间数据，可自定义扩展）
     */
    Flux<RouterEvent> stream(SubAgentRequest req, SubAgentContext context);
}
