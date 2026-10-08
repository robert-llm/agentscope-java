package com.robert.agent.a2a;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * A2A 服务端配置。
 *
 * <p>提供一个 {@link ReActAgent.Builder} Bean，A2A Spring Boot Starter 会自动：
 * <ul>
 *   <li>基于该 Builder 创建 AgentRunner</li>
 *   <li>构建 AgentScopeA2aServer，暴露 JSON-RPC 端点</li>
 *   <li>注册 AgentCardController（GET /.well-known/agent-card.json）</li>
 *   <li>注册 A2aJsonRpcController（POST /a2a）</li>
 *   <li>通过 NacosAgentRegistry 自动注册到 Nacos</li>
 * </ul>
 *
 * <p>配置项在 application.properties 中：
 * <pre>
 * agentscope.a2a.server.enabled=true
 * agentscope.a2a.server.card.name=doctor-assistant
 * agentscope.a2a.server.card.description=智能助手
 * agentscope.a2a.nacos.enabled=true
 * agentscope.a2a.nacos.server-addr=127.0.0.1:8848
 * </pre>
 */
@Configuration
public class A2aServerConfig {

    private static final Logger log = LoggerFactory.getLogger(A2aServerConfig.class);

    /**
     * A2A 服务端的 Agent Builder。
     *
     * <p>Starter 会为每个请求创建一个新的 Agent 实例（per-request），
     * 因此这里只提供 Builder 模板。</p>
     */
    @Bean
    public ReActAgent.Builder a2aAgentBuilder() {
        log.info("[A2A Server] 注册 ReActAgent.Builder，agent 名称: doctor-assistant");
        return ReActAgent.builder()
                .name("doctor-assistant")
                .sysPrompt("""
                        你是一个医生智能助手, 你的职责包括：
                        1. 理解用户的疾病症状描述
                        2. 使用合适的工具来获取信息或执行操作
                        3. 给出准确、有帮助的回答

                        请用中文回答用户的问题。
                        """);
    }

    @Bean
    public AgentRegistry agentRegistry() {
        // 设置 Nacos 地址
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, "10.36.21.120:8848");
// 创建 Nacos Client
        AiService aiService = null;
        try {
            aiService = AiFactory.createAiService(properties);
        }catch (NacosException e){
            log.error("nacos注册器创建失败");
        }
        // 可以在这里设置 namespace 等其他属性
        return NacosAgentRegistry.builder(aiService).build();
    }
}
