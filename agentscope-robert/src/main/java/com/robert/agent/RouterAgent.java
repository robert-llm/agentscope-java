package com.robert.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.UserMessage;
import com.robert.agent.model.AgentResult;
import com.robert.agent.model.RoutingDecision;
import com.robert.agent.model.SubAgentRequest;

import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class RouterAgent {

    private static final Logger log = Logger.getLogger(RouterAgent.class.getName());

    private final ReActAgent routerAgent;
    private final AgentRegistry registry;
    private final ExecutorService agentExecutor;
    private final int agentTimeoutSec;

    public RouterAgent(ReActAgent routerAgent,
                       AgentRegistry registry,
                       ExecutorService agentExecutor,
                       int agentTimeoutSec) {
        this.routerAgent = routerAgent;
        this.registry = registry;
        this.agentExecutor = agentExecutor;
        this.agentTimeoutSec = agentTimeoutSec;
    }

    public void run(AgentChatContext ctx) throws Exception {
        List<String> targets = plan(ctx);
        if (targets.isEmpty()) {
            targets = List.of("eip-agent");
        }

        StreamAggregator agg = new StreamAggregator(ctx, targets, registry, agentExecutor, agentTimeoutSec);
        agg.emitInitialPlan();

        for (int i = 0; i < targets.size(); i++) {
            String agentId = targets.get(i);
            SubAgent agent = registry.get(agentId);
            if (agent == null) {
                log.warning("[router] 未找到子 Agent: " + agentId);
                agg.emitDegraded(agentId, "子 Agent 未注册");
                continue;
            }
            SubAgentRequest req = buildRequest(ctx, agentId, i);
            AgentResult result = safeExecute(agent, req, agentId, i, agg);
            agg.emitPlanUpdate(agentId, result);
        }

        agg.flushToContext();
    }

    private List<String> plan(AgentChatContext ctx) {
        String agentsList = registry.agents().stream()
                .map(a -> "- " + a.agentId() + ": " + a.description())
                .reduce("", (a, b) -> a + "\n" + b);

        String sysPrompt = buildRoutePrompt(agentsList);

        RuntimeContext runtimeCtx = RuntimeContext.builder()
                .sessionId("router-" + ctx.getMessageId())
                .userId(ctx.getLoginName())
                .build();

        try {
            RoutingDecision decision = routerAgent
                    .call(
                            List.of(new SystemMessage(sysPrompt), new UserMessage(ctx.getQuestion())),
                            RoutingDecision.class,
                            runtimeCtx
                    )
                    .block()
                    .getStructuredData(RoutingDecision.class);

            log.info("[router] thought=" + decision.thought + " subAgents=" + decision.subAgents);
            return decision.subAgents;
        } catch (Exception e) {
            log.warning("[router] 规划失败，兜底 eip-agent: " + e.getMessage());
            return List.of("eip-agent");
        }
    }

    private String buildRoutePrompt(String agentsList) {
        return """
            你是一个 金地集团内部多 Agent 智能体的路由编排器。
            你的唯一职责是：分析用户问题，一次性决定调用哪些子 Agent 处理。
            你不需要回答问题、不需要检索资料、不需要生成答案——最终回答由子 Agent 完成。

            ### 输出要求 ###
            必须返回 JSON，包含 thought（单行思考摘要）和 subAgents（子 Agent id 列表）。

            ### 可用子 Agent（只允许从以下列表中选择，禁止凭空编造 id）###
            %s

            ### 路由规则 ###
            1. 制度 / 新闻 / 公告 / 流程 / 组织 / HR / IT → eip-agent
            2. 课程 / 课件 / 讲师 / 学习 / 培训 / 视频课 → learning-agent
            3. 同一问题同时涉及两个域 → 两个子 Agent 依次执行（subAgents 顺序即执行顺序）
            4. 无法判断 / 闲聊 → 默认路由 eip-agent

            ### 硬性约束（最高优先级）###
            - 只做一次决策：subAgents 一次性给出全部路由目标
            - 禁止重复：subAgents 中同一子 Agent 最多出现一次
            - thought 单行、禁止英文双引号（用「」『』）、禁止反斜杠
            """.formatted(agentsList);
    }

    private AgentResult safeExecute(SubAgent agent, SubAgentRequest req,
                                    String agentId, int module,
                                    StreamAggregator agg) {
        try {
            CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return agent.execute(req, agg.listener(module)).join();
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, agentExecutor);

            return future
                    .orTimeout(agentTimeoutSec, TimeUnit.SECONDS)
                    .exceptionally(t -> {
                        log.warning("[router] agent=" + agentId + " 执行失败/超时: " + t.getMessage());
                        agg.emitDegraded(agentId, "该部分内容获取失败，请稍后重试。");
                        return AgentResult.degraded(agentId, t);
                    })
                    .join();
        } catch (Exception e) {
            agg.emitDegraded(agentId, "该部分内容获取失败，请稍后重试。");
            return AgentResult.degraded(agentId, e);
        }
    }

    private SubAgentRequest buildRequest(AgentChatContext ctx, String agentId, int module) {
        SubAgentRequest req = new SubAgentRequest();
        req.setQuestion(ctx.getQuestion());
        req.setContext(ctx);
        req.setDeepThink(ctx.getDeepThink());
        req.setModule(module);
        return req;
    }
}
