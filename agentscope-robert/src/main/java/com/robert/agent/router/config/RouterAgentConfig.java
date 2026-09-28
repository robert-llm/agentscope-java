package com.robert.agent.router.config;

import com.robert.agent.router.AgentRegistry;
//import com.robert.agent.EIPSubAgent;
import com.robert.agent.router.RouterAgent;
import com.robert.agent.router.subagent.HarnessSubAgentAdapter;
import com.robert.agent.router.subagent.SimpleChatSubAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RouterAgentConfig {

    @Bean("agentExecutor")
    public ExecutorService agentExecutor() {
        return Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2
        );
    }

    @Bean("routerReActAgent")
    public ReActAgent routerReActAgent() {
        return ReActAgent.builder()
                .name("master-router")
                .model("dashscope:qwen-plus")
                .build();
    }

    //    @Bean
//    public AgentRegistry agentRegistry(ReActSmartAgent reActSmartAgent,
//                                       ExecutorService agentExecutor) {
    @Bean
    public AgentRegistry agentRegistry(ExecutorService agentExecutor) {
        AgentRegistry registry = new AgentRegistry();
//        registry.register(new EIPSubAgent(reActSmartAgent, agentExecutor));
        // registry.register(new LearningSubAgent(...));

        // 测试用简单对话 SubAgent（无需 LLM 依赖，模拟流式输出）
        registry.register(new SimpleChatSubAgent(
                "test-chat",
                "我是一个情感聊天助手，用于和用户进行情感交流"
        ));

        // 天气助手 SubAgent（基于 HarnessAgent，真实 LLM 流式输出）
        registry.register(new HarnessSubAgentAdapter(
                "weather-agent",
                "天气查询助手，可查询全球各城市的实时天气、天气预报、气温、湿度、风力等信息",
                weatherAgent()
        ));

        return registry;
    }

    /**
     * 天气助手 HarnessAgent：纯对话，无工具调用，用于测试流式链路。
     */
    private HarnessAgent weatherAgent() {
        return HarnessAgent.builder()
                .name("weather-agent")
                .description("天气查询助手，回答用户关于天气、气温、湿度、风力等问题")
                .sysPrompt(weatherPrompt())
                .model("dashscope:qwen-plus")
                .maxIters(5)
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableMemoryTools()
                .build();
    }

    private static String weatherPrompt() {
        return """
                你是一个专业的天气助手。

                ## 你的职责
                - 回答用户关于天气、气温、湿度、风力、空气质量等问题
                - 提供简洁、准确的天气信息

                ## 回复格式
                1. 先简要说明查询的城市/地区
                2. 给出天气概况（温度、天气状况、风力等）
                3. 提供穿衣/出行建议

                ## 注意事项
                - 始终使用中文回复
                - 如果没有实时数据，基于常识给出一般性建议
                - 回复简洁友好，避免冗长
                - 可以适当使用 emoji 让回复更生动
                """;
    }

    @Bean
    public RouterAgent routerAgent(ReActAgent routerReActAgent,
                                   AgentRegistry agentRegistry,
                                   ExecutorService agentExecutor) {
        return new RouterAgent(routerReActAgent, agentRegistry, agentExecutor, 90);
    }
}
