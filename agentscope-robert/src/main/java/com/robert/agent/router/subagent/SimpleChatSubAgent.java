package com.robert.agent.router.subagent;

import com.robert.agent.router.model.RouterEvent;
import com.robert.agent.router.model.SubAgentRequest;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.logging.Logger;

/**
 * 简单对话测试 SubAgent：模拟流式输出，无需 LLM 依赖。
 *
 * <p>用于测试 RouterAgent 的流式编排链路：
 * <ul>
 *   <li>收到问题后，先输出一个 THINK_TIME 事件（模拟思考）</li>
 *   <li>然后将固定前缀 + 用户问题逐字以 MESSAGE 事件流式输出</li>
 *   <li>最后 Flux 完成，RouterAgent 自动发送 nodeFinished</li>
 * </ul></p>
 *
 * <p>注册到 AgentRegistry 后即可被 RouterAgent 路由到。
 * 可通过 {@code forcedTargets} 参数强制路由到该 Agent 进行测试。</p>
 */
public class SimpleChatSubAgent implements SubAgent {

    private static final Logger log = Logger.getLogger(SimpleChatSubAgent.class.getName());

    private final String agentId;
    private final String description;

    /**
     * @param agentId     注册 id，如 "test-chat"
     * @param description 路由描述，会展示给路由规划 LLM
     */
    public SimpleChatSubAgent(String agentId, String description) {
        this.agentId = agentId;
        this.description = description;
    }

    @Override
    public String agentId() { return agentId; }

    @Override
    public String description() { return description; }

    /**
     * 流式执行：模拟逐字输出回复。
     *
     * <p>返回冷 Flux，订阅后：
     * <ol>
     *   <li>延迟 300ms 输出 THINK_TIME（模拟思考耗时）</li>
     *   <li>每隔 50ms 输出一个 MESSAGE 事件（每次一个字符）</li>
     *   <li>全部字符输出完毕后 Flux complete</li>
     * </ol></p>
     */
    @Override
    public Flux<RouterEvent> stream(SubAgentRequest req, SubAgentContext context) {
        String question = req.getQuestion();
        int module = req.getModule();

        log.info("[SimpleChatSubAgent] 收到问题: " + question
                + (context.hasOutput() ? ", 上游文本长度=" + context.getOutput().length() : ""));

        // 构造回复文本
        String reply = "你好，我是测试 Agent「" + agentId + "」。"
                + "你问的问题是：「" + question + "」。"
                + "这是一个模拟流式输出的回复，用于验证 RouterAgent 的编排链路是否正常。";

        // 先输出思考事件，再逐字输出回复
        Flux<RouterEvent> thinkFlux = Flux.just(
                RouterEvent.thinkTime("正在思考...", module, agentId, 0)
        ).delayElements(Duration.ofMillis(300));

        // 将回复拆成单字符，每个字符一个 MESSAGE 事件
        Flux<RouterEvent> messageFlux = Flux.fromArray(reply.split(""))
                .filter(token -> !token.isEmpty())
                .map(token -> RouterEvent.message(token, module, agentId, 0))
                .delayElements(Duration.ofMillis(50));

        return Flux.concat(thinkFlux, messageFlux)
                .doOnComplete(() -> log.info("[SimpleChatSubAgent] 回复完成"));
    }
}
