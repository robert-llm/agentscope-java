package com.robert.agent.router;

import com.robert.agent.router.model.RouterEvent;
import com.robert.agent.router.model.RouterRequest;
import com.robert.agent.router.subagent.SubAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import com.robert.agent.router.model.RoutingDecision;
import com.robert.agent.router.model.SubAgentRequest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RouterAgent：多 Agent 路由编排器。
 *
 * <p>对外提供两种调用方式：
 * <ul>
 *   <li>{@link #stream(RouterRequest)}：返回 {@code Flux<RouterEvent>}，事件逐条推送给调用方</li>
 *   <li>{@link #run(AgentChatContext)}：内部订阅 stream()，把事件转发到 SseEmitter（向后兼容）</li>
 * </ul></p>
 */
public class RouterAgent {

    private static final Logger log = Logger.getLogger(RouterAgent.class.getName());

    private final ReActAgent planningModel;
    private final AgentRegistry registry;
    private final ExecutorService agentExecutor;
    private final int agentTimeoutSec;
    private final AtomicLong seqGenerator = new AtomicLong(0);

    public RouterAgent(ReActAgent planningModel,
                       AgentRegistry registry,
                       ExecutorService agentExecutor,
                       int agentTimeoutSec) {
        this.planningModel = planningModel;
        this.registry = registry;
        this.agentExecutor = agentExecutor;
        this.agentTimeoutSec = agentTimeoutSec;
    }

    // ==================== 流式接口（新增） ====================

    /**
     * 流式执行：返回事件流，调用方订阅后逐条消费。
     *
     * <p>采用 Reactor 原生的 {@link Flux#create} 桥接异步生产端，
     * 生产逻辑仅在订阅时才启动（冷流），避免无人消费时白白执行编排。
     * 订阅者取消时，取消标记会被置位，正在执行的子 Agent 会在
     * ReAct 循环内自检并提前收尾。</p>
     */
    public Flux<RouterEvent> stream(RouterRequest request) {
        return Flux.<RouterEvent>create(sink -> {
            sink.onCancel(() -> request.markCancelled());
            try {
                doStream(sink, request);
            } catch (Exception e) {
                log.severe("[router] stream 异常: " + e.getMessage());
                sink.next(RouterEvent.error(e.getMessage(), seqGenerator.incrementAndGet()));
            } finally {
                sink.complete();
            }
        }, FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.fromExecutor(agentExecutor));
    }

    // ==================== 同步接口（保留，向后兼容） ====================

    /**
     * 同步执行：内部订阅 stream()，把事件分发到 AgentChatContext。
     * 与改造前行为等价：单 Agent 直通时事件走 SseEmitter，多 Agent 时走 EventSink。
     */
    public void run(AgentChatContext ctx) throws Exception {
        RouterRequest request = RouterRequest.from(ctx);
        stream(request)
                .doOnNext(event -> dispatchToContext(ctx, event))
                .blockLast();
    }

    // ==================== 编排核心 ====================

    /**
     * 执行路由规划 + 串行调度 + 事件转发。
     *
     * <p>流程：路由规划 → workflow_started → 初始 plan → 逐个子 Agent 串行调用
     * （流式事件实时推送到 sink）→ workflow_finished → message_end。</p>
     *
     * <p>子 Agent 串行执行：调用 {@link SubAgent#stream} 获取 Flux，
     * 逐条转发到 sink 并赋值 seq，blockLast 等待完成后再启动下一个。
     * 取消标记在每次迭代前检查。</p>
     */
    private void doStream(FluxSink<RouterEvent> sink, RouterRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("[router] doStream 开始, messageId=" + request.getMessageId());

        // ① 路由规划（参数显式指定优先，否则 LLM 判断）
        List<String> targets = CollectionUtils.isNotEmpty(request.getForcedTargets())
                ? request.getForcedTargets()
                : plan(request);

        if (targets.isEmpty()) {
            targets = List.of("eip-agent");
        }
        log.info("[router] 路由目标: " + targets);

        // ② workflow_started
        sink.next(RouterEvent.workflowStarted(seqGenerator.incrementAndGet()));

        // ③ 初始化聚合器，绑定 sink 为事件出口
        StreamAggregator agg = new StreamAggregator(
                sink::next,
                seqGenerator,
                targets,
                registry
        );

        // ④ 初始 plan 前置输出
        agg.emitInitialPlan();

        // ⑤ 串行调度：逐个消费子 Agent 的流式输出
        for (int i = 0; i < targets.size(); i++) {
            if (request.isCancelled()) {
                log.info("[router] 用户取消，终止串行调度（已完成 " + i + "/" + targets.size() + "）");
                break;
            }

            String agentId = targets.get(i);
            SubAgent agent = registry.get(agentId);

            if (agent == null) {
                log.warning("[router] 未找到子 Agent: " + agentId);
                agg.emitDegraded(agentId, "子 Agent 未注册");
                continue;
            }

            SubAgentRequest subReq = buildRequest(request, agentId, i);

            // 节点开始
            sink.next(RouterEvent.nodeStarted("llm", i, agentId, seqGenerator.incrementAndGet()));

            log.info("[router] 调用子 Agent: " + agentId + " (module=" + i + ")");

            // 流式消费子 Agent 输出，逐条赋值 seq 并转发到 sink
            try {
                agent.stream(subReq)
                        .timeout(Duration.ofSeconds(agentTimeoutSec))
                        .doOnNext(event -> {
                            event.setSeq(seqGenerator.incrementAndGet());
                            sink.next(event);
                        })
                        .blockLast();

                log.info("[router] 子 Agent " + agentId + " 完成");
            } catch (Exception e) {
                log.warning("[router] agent=" + agentId + " 异常/超时: " + e.getMessage());
                sink.next(RouterEvent.error(e.getMessage(), seqGenerator.incrementAndGet()));
            }

            // 节点结束
            sink.next(RouterEvent.nodeFinished("llm", i, agentId, seqGenerator.incrementAndGet()));

            // 更新 plan
            agg.emitPlanUpdate(agentId);
        }

        // ⑥ 收尾
        sink.next(RouterEvent.workflowFinished(seqGenerator.incrementAndGet()));
        sink.next(RouterEvent.messageEnd(seqGenerator.incrementAndGet()));
        log.info("[router] doStream 结束, 耗时=" + (System.currentTimeMillis() - startMs) + "ms");
    }

    // ==================== 路由规划 ====================

    private List<String> plan(RouterRequest request) {
        String agentsList = registry.agents().stream()
                .map(a -> "- " + a.agentId() + ": " + a.description())
                .reduce("", (a, b) -> a + "\n" + b);

        String routePrompt = buildRoutePrompt(agentsList);

        // 将路由指令与用户问题合并到 UserMessage（框架禁止在 call 输入中传入 SystemMessage）
        String userText = routePrompt + "\n\n## 用户问题 ##\n" + request.getQuestion();

        RuntimeContext runtimeCtx = RuntimeContext.builder()
                .sessionId("router-" + request.getMessageId())
                .userId(request.getLoginName())
                .build();

        try {
            Msg result = planningModel
                    .call(userText, RoutingDecision.class, runtimeCtx)
                    .block();

            if (result == null) {
                log.warning("[router] 规划返回 null，兜底 eip-agent");
                return List.of("eip-agent");
            }

            RoutingDecision decision = result.getStructuredData(RoutingDecision.class);
            log.info("[router] thought=" + decision.thought
                    + " subAgents=" + decision.subAgents);
            return decision.subAgents;
        } catch (Exception e) {
            log.warning("[router] 规划失败，兜底 eip-agent: " + e.getMessage());
            return List.of("eip-agent");
        }
    }

    private String buildRoutePrompt(String agentsList) {
//        return """
//            你是一个 金地集团内部多 Agent 智能体的路由编排器。
//            你的唯一职责是：分析用户问题，一次性决定调用哪些子 Agent 处理。
//            你不需要回答问题、不需要检索资料、不需要生成答案——最终回答由子 Agent 完成。
//
//            ### 输出要求 ###
//            必须返回 JSON，包含 thought（单行思考摘要）和 subAgents（子 Agent id 列表）。
//
//            ### 可用子 Agent（只允许从以下列表中选择，禁止凭空编造 id）###
//            %s
//
//            ### 路由规则 ###
//            1. 制度 / 新闻 / 公告 / 流程 / 组织 / HR / IT → eip-agent
//            2. 课程 / 课件 / 讲师 / 学习 / 培训 / 视频课 → learning-agent
//            3. 同一问题同时涉及两个域 → 两个子 Agent 依次执行（subAgents 顺序即执行顺序）
//            4. 无法判断 / 闲聊 → 默认路由 eip-agent
//
//            ### 硬性约束（最高优先级）###
//            - 只做一次决策：subAgents 一次性给出全部路由目标
//            - 禁止重复：subAgents 中同一子 Agent 最多出现一次
//            - thought 单行、禁止英文双引号（用「」『』）、禁止反斜杠
//            """.formatted(agentsList);
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
            4. 上面的规则都无法判断 → 根据问题的内容和agent的描述信息匹配度，决定调用哪些agent

            ### 硬性约束（最高优先级）###
            - 只做一次决策：subAgents 一次性给出全部路由目标
            - 禁止重复：subAgents 中同一子 Agent 最多出现一次
            - thought 单行、禁止英文双引号（用「」『』）、禁止反斜杠
            """.formatted(agentsList);
    }

    /**
     * 从 RouterRequest 构建子 Agent 请求，携带会话上下文供子 Agent 构建 RuntimeContext。
     */
    private SubAgentRequest buildRequest(RouterRequest request, String agentId, int module) {
        AgentChatContext ctx = new AgentChatContext();
        ctx.setConversationId(request.getConversationId());
        ctx.setMessageId(request.getMessageId());
        ctx.setLoginName(request.getLoginName());
        ctx.setDeepThink(request.getDeepThink());

        SubAgentRequest req = new SubAgentRequest();
        req.setQuestion(request.getQuestion());
        req.setContext(ctx);
        req.setDeepThink(request.getDeepThink());
        req.setModule(module);
        return req;
    }

    // ==================== 事件分发到 AgentChatContext（同步路径） ====================

    /**
     * 把 RouterEvent 转回 AgentChatContext 的发送方法，保持存量行为。
     */
    @SuppressWarnings("unchecked")
    private void dispatchToContext(AgentChatContext ctx, RouterEvent event) {
        switch (event.getType()) {
            case PLAN -> {
                List<Map<String, Object>> nodeList =
                        (List<Map<String, Object>>) event.getPayload().get("nodeList");
                int finished = (int) event.getPayload().get("finished");
                ctx.sendPlan(nodeList, finished);
            }
            case MESSAGE -> {
                String fragment = (String) event.getPayload().get("answer");
                ctx.sendMessage(fragment, event.getModule());
                ctx.getStreamAnswer().append(fragment);
            }
            case THINK -> {
                String fragment = (String) event.getPayload().get("answer");
                ctx.sendMessage(fragment, event.getModule());
                ctx.getStreamThink().append(fragment);
            }
            case WORKFLOW_STARTED -> ctx.sendEvent("workflow_started", Map.of());
            case WORKFLOW_FINISHED -> ctx.sendEvent("workflow_finished", Map.of());
            case MESSAGE_END -> ctx.sendEvent("message_end", Map.of());
            case ERROR -> ctx.sendEvent("error", event.getPayload());
            default -> {
                Map<String, Object> payload = new LinkedHashMap<>(event.getPayload());
                payload.put("module", event.getModule());
                ctx.sendEvent(event.getType().name().toLowerCase(), payload);
            }
        }
    }
}
