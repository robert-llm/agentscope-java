package com.robert.agent.higress.mcptest.customer.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.higress.HigressMcpClientBuilder;
import io.agentscope.extensions.higress.HigressMcpClientWrapper;
import io.agentscope.extensions.higress.HigressToolkit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@RequiredArgsConstructor
public class AgentScopeConfig {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    private final HigressProperties higressProperties;
    private final DashScopeProperties dashScopeProperties;

    /**
     * 创建 Higress MCP 客户端，连接网关上发布的 MCP 端点
     * 这一步只做连接，不注册工具
     */
    @Lazy
    @Bean
    public HigressMcpClientWrapper higressMcpClient() {
        log.info("正在创建 Higress MCP 客户端，端点: {}", higressProperties.getMcpEndpoint());
        return HigressMcpClientBuilder
                .create(higressProperties.getClientName())
                .streamableHttpEndpoint(higressProperties.getMcpEndpoint())
                .buildSync();
    }

    /**
     * 创建 HigressToolkit，并把 MCP 客户端注册进去
     * registerMcpClient 会向 Higress 发起 tools/list 请求，拉取所有工具并注册
     * .block() 等待注册完成，确保 Bean 初始化时工具已就绪
     */
    @Lazy
    @Bean
    public HigressToolkit higressToolkit(HigressMcpClientWrapper higressMcpClient) {
        log.info("开始注册 Higress MCP 工具...");
        HigressToolkit toolkit = new HigressToolkit();
        toolkit.registerMcpClient(higressMcpClient).block();
        log.info("Higress MCP 工具注册完成");
        return toolkit;
    }

    /**
     * 创建模型
     */
    @Bean
    public Model dashScopeModel() {
        return DashScopeChatModel.builder()
                .apiKey(dashScopeProperties.getApiKey())
                .modelName(dashScopeProperties.getModelName())
                .build();
    }

    /**
     * 创建 ReActAgent，绑定已注册好 MCP 工具的 Toolkit
     * 应用启动后，这个 Agent 就具备了调用 Higress 上所有已发布 MCP 工具的能力
     */
    @Lazy
    @Bean
    public ReActAgent weatherAgent(Model dashScopeModel, HigressToolkit higressToolkit) {
        log.info("正在创建 WeatherAgent...");
        ReActAgent agent = ReActAgent.builder()
                .name("WeatherAssistant")
                .model(dashScopeModel)
                .toolkit(higressToolkit)
                .build();
        log.info("WeatherAgent 创建完成，已绑定 Higress MCP 工具");
        return agent;
    }
}
