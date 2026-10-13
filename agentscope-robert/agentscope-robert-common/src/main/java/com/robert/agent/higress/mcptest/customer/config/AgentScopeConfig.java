package com.robert.agent.higress.mcptest.customer.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
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
import reactor.core.publisher.Mono;

import java.util.Set;

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
    //@Lazy
    @Bean
    public HigressMcpClientWrapper higressMcpClient() {
        log.info("正在创建 Higress MCP 客户端，端点: {}", higressProperties.getMcpEndpoint());
        log.info("client -name {}", higressProperties.getClientName());
        return HigressMcpClientBuilder
                .create(higressProperties.getClientName())
                .streamableHttpEndpoint(higressProperties.getMcpEndpoint())
                .header("Authorization", "Bearer " + higressProperties.getClientKeyAuth())
//                .header("token", higressProperties.getClientKeyAuth())
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
        log.info("HigressToolkit 已注册工具数量: {}", higressToolkit.getToolSchemas().size());
        higressToolkit.getToolSchemas().forEach(ts ->
                log.info("  - 工具: {} ({})", ts.getName(), ts.getDescription()));

        // 只拦截指定工具的日志（留空则拦截所有工具）
        Set<String> watchedTools = Set.of("getWeather");

        // 工具调用日志拦截 Hook：在每次工具调用前后打印日志
        Hook toolLoggingHook = new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PreActingEvent e) {
                    ToolUseBlock toolUse = e.getToolUse();
                    if (!watchedTools.isEmpty() && !watchedTools.contains(toolUse.getName())) {
                        return Mono.just(event);
                    }
                    log.info(">>> [ToolCall] 调用工具: {} | 参数: {}",
                            toolUse.getName(), toolUse.getInput());
                } else if (event instanceof PostActingEvent e) {
                    ToolUseBlock toolUse = e.getToolUse();
                    if (!watchedTools.isEmpty() && !watchedTools.contains(toolUse.getName())) {
                        return Mono.just(event);
                    }
                    ToolResultBlock result = e.getToolResult();

                    // 从 ToolResultBlock 的 output 中提取纯 JSON
                    String rawText = result.getOutput().stream()
                            .filter(block -> block instanceof TextBlock)
                            .map(block -> ((TextBlock) block).getText())
                            .findFirst()
                            .orElse("");

                    String pureJson = extractJson(rawText);
                    log.info("<<< [ToolCall] 工具返回: {} | 纯JSON: {}",
                            toolUse.getName(), pureJson);
                }
                return Mono.just(event);
            }

            @Override
            public int priority() {
                return 50; // 高优先级，尽早执行
            }
        };

        ReActAgent agent = ReActAgent.builder()
                .name("WeatherAssistant")
                .sysPrompt("你是一个智能天气助手。当用户询问天气相关问题时，你必须使用可用的天气查询工具来获取实时天气数据，然后根据工具返回的结果回答用户。不要编造天气数据，始终使用工具获取真实数据。")
                .model(dashScopeModel)
                .toolkit(higressToolkit)
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .hook(toolLoggingHook)
                .build();
        log.info("WeatherAgent 创建完成，已绑定 Higress MCP 工具");
        return agent;
    }

    /**
     * 从 Higress 网关返回的包装文本中提取纯 JSON。
     * Higress 会在工具响应外包裹 "# API Response Information" 等说明文本，
     * 实际 JSON 位于 "## Original Response" 标记之后。
     * 如果没有找到标记，则尝试直接提取文本中的第一个 JSON 对象/数组。
     */
    private static String extractJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        // 方式1：查找 "## Original Response" 标记
        String marker = "## Original Response";
        int idx = rawText.indexOf(marker);
        if (idx >= 0) {
            String afterMarker = rawText.substring(idx + marker.length()).trim();
            if (!afterMarker.isEmpty()) {
                return afterMarker;
            }
        }

        // 方式2：回退，直接找第一个 { 或 [ 开始的 JSON
        int braceStart = rawText.indexOf('{');
        int bracketStart = rawText.indexOf('[');
        int start = -1;
        if (braceStart >= 0 && bracketStart >= 0) {
            start = Math.min(braceStart, bracketStart);
        } else if (braceStart >= 0) {
            start = braceStart;
        } else if (bracketStart >= 0) {
            start = bracketStart;
        }

        if (start >= 0) {
            int end = rawText.lastIndexOf('}');
            if (end < 0) {
                end = rawText.lastIndexOf(']');
            }
            if (end > start) {
                return rawText.substring(start, end + 1);
            }
        }

        // 都没找到，返回原始文本
        return rawText;
    }
}
