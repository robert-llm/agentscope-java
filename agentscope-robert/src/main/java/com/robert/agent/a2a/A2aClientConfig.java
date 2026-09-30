package com.robert.agent.a2a;

import com.robert.agent.a2a.client.RemoteAgentProxy;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A2A 客户端配置 — 通过 Nacos 自动发现远程 Agent。
 *
 * <p>{@link AgentCardResolver} 由 {@code agentscope-nacos-spring-boot-starter} 自动注入
 * （即 {@code NacosAgentCardResolver}），它从 Nacos 注册中心查询远程 Agent 的 AgentCard。</p>
 *
 * <p>每个远程 Agent 对应一个 {@link A2aAgent} Bean，通过 {@link RemoteAgentProxy} 封装，
 * 提供统一的调用入口。</p>
 *
 * <p>使用方式：
 * <pre>{@code
 * // 注入 RemoteAgentProxy
 * @Autowired RemoteAgentProxy remoteAgentProxy;
 *
 * // 调用远程 Agent
 * Msg result = remoteAgentProxy.call("remote-agent-name", userMessage);
 * }</pre>
 */
@Configuration
public class A2aClientConfig {

    private static final Logger log = LoggerFactory.getLogger(A2aClientConfig.class);

    /**
     * 远程 Agent 代理 — 封装 A2aAgent 的创建和调用。
     *  agentscope.a2a.nacos.enabled=true, agentscope-nacos-spring-boot-starter 会自动注入NacosAgentCardResolver
     * <p>通过 {@link AgentCardResolver}（Nacos）按需创建 A2aAgent 实例，
     * 支持缓存和动态发现。</p>
     *
     * @param agentCardResolver Nacos 自动注入的 AgentCard 解析器
     */
    @Bean
    public RemoteAgentProxy remoteAgentProxy(AgentCardResolver agentCardResolver) {
        log.info("[A2A Client] 创建 RemoteAgentProxy，使用 Nacos 发现远程 Agent");
        return new RemoteAgentProxy(agentCardResolver);
    }
}
