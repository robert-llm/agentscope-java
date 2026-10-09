package com.robert.agent.a2a.client;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程 Agent 代理 — 封装 A2aAgent 的创建、缓存和调用。
 *
 * <p>通过 {@link AgentCardResolver}（Nacos）自动发现远程 Agent 的 AgentCard，
 * 按需创建 {@link A2aAgent} 实例并缓存。支持同步调用和流式调用两种模式。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Autowired RemoteAgentProxy proxy;
 *
 * // 同步调用
 * Msg result = proxy.call("remote-agent-name", "你好，请帮我分析数据");
 *
 * // 流式调用
 * proxy.stream("remote-agent-name", "分析数据").subscribe(event -> ...);
 *
 * // 列出已缓存的远程 Agent
 * proxy.getCachedAgents().forEach((name, agent) -> ...);
 * }</pre>
 */
public class RemoteAgentProxy {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentProxy.class);

    private final AgentCardResolver agentCardResolver;

    /** 缓存已创建的 A2aAgent 实例，避免重复创建 */
    private final Map<String, A2aAgent> agentCache = new ConcurrentHashMap<>();

    public RemoteAgentProxy(AgentCardResolver agentCardResolver) {
        this.agentCardResolver = agentCardResolver;
    }

    /**
     * 同步调用远程 Agent。
     *
     * @param agentName 远程 Agent 名称（在 Nacos 中注册的名称）
     * @param message   用户消息文本
     * @return Agent 的回复消息
     */
    public Msg call(String agentName, String message) {
        A2aAgent agent = getOrCreateAgent(agentName);
        Msg userMsg = Msg.builder().textContent(message).build();
        log.info("[A2A Client] 调用远程 Agent: {}, 消息长度: {}", agentName, message.length());

        Msg result = agent.call(userMsg).block();
        log.info("[A2A Client] 远程 Agent {} 返回结果, 长度: {}",
                agentName, result != null ? result.getTextContent().length() : 0);
        return result;
    }

    /**
     * 同步调用远程 Agent（带超时）。
     *
     * @param agentName      远程 Agent 名称
     * @param message        用户消息文本
     * @param timeoutSeconds 超时秒数
     * @return Agent 的回复消息
     */
    public Msg call(String agentName, String message, long timeoutSeconds) {
        A2aAgent agent = getOrCreateAgent(agentName);
        Msg userMsg = Msg.builder().textContent(message).build();
        log.info("[A2A Client] 调用远程 Agent: {} (超时 {}s)", agentName, timeoutSeconds);

        Msg result = agent.call(userMsg)
                .block(java.time.Duration.ofSeconds(timeoutSeconds));
        log.info("[A2A Client] 远程 Agent {} 返回结果", agentName);
        return result;
    }

    /**
     * 流式调用远程 Agent（模拟）— 通过 call() 获取最终结果后以 Flux 返回。
     *
     * <p>由于 A2aAgent 不支持真正的流式 API（stream() 已废弃），这里通过 call() 获取最终结果，
     * 然后包装为 Flux 返回，方便 Controller 层统一处理。</p>
     *
     * @param agentName 远程 Agent 名称
     * @param message   用户消息文本
     * @return 包含最终结果的事件流
     */
    public Flux<Msg> stream(String agentName, String message) {
        A2aAgent agent = getOrCreateAgent(agentName);
        Msg userMsg = Msg.builder().textContent(message).build();
        log.info("[A2A Client] 流式调用远程 Agent: {}", agentName);

        return Flux.defer(() -> {
            Msg result = agent.call(userMsg).block();
            if (result != null) {
                return Flux.just(result);
            }
            return Flux.empty();
        });
    }

    /**
     * 异步调用远程 Agent（返回 Mono）。
     *
     * @param agentName 远程 Agent 名称
     * @param message   用户消息文本
     * @return 异步结果
     */
    public Mono<Msg> callAsync(String agentName, String message) {
        A2aAgent agent = getOrCreateAgent(agentName);
        Msg userMsg = Msg.builder().textContent(message).build();
        log.info("[A2A Client] 异步调用远程 Agent: {}", agentName);
        return agent.call(userMsg);
    }

    /**
     * 获取或创建 A2aAgent 实例。
     *
     * <p>首次调用时通过 AgentCardResolver 从 Nacos 获取 AgentCard 并创建 A2aAgent，
     * 后续调用直接返回缓存实例。</p>
     */
    public A2aAgent getOrCreateAgent(String agentName) {
        return agentCache.computeIfAbsent(agentName, name -> {
            log.info("[A2A Client] 从 Nacos 发现远程 Agent: {}", name);
            A2aAgent agent = A2aAgent.builder()
                    .name(name)
                    .agentCardResolver(agentCardResolver)
                    .build();
            log.info("[A2A Client] 远程 Agent {} 创建成功", name);
            return agent;
        });
    }

    /** 获取已缓存的远程 Agent 列表 */
    public Map<String, A2aAgent> getCachedAgents() {
        return Map.copyOf(agentCache);
    }

    /** 移除缓存的 Agent（用于强制刷新 AgentCard） */
    public void evict(String agentName) {
        agentCache.remove(agentName);
        log.info("[A2A Client] 已移除缓存的远程 Agent: {}", agentName);
    }
}
