package com.robert.agent.channel;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Channel 多 Agent 流式对话示例配置。
 *
 * <p>使用 {@link GatewayBootstrap} 注册 3 个 Agent，通过 {@link ChatUiChannel}
 * 提供统一的流式对话入口。前端通过 SSE 端点 {@code /api/channel/chat/stream}
 * 发送消息并实时接收 Agent 的流式输出。</p>
 *
 * <h3>注册的 Agent</h3>
 * <ul>
 *   <li><b>assistant</b>（主 Agent）— 通用助手，回答各类问题</li>
 *   <li><b>translator</b> — 翻译专家，中英互译</li>
 *   <li><b>coder</b> — 编程助手，代码编写与技术问答</li>
 * </ul>
 *
 * <h3>路由方式</h3>
 * <p>通过 {@code SendOptions.withAgentId()} 将消息路由到指定 Agent；
 * 未指定时默认路由到主 Agent（assistant）。</p>
 */
@Configuration
public class ChannelExampleConfig implements WebMvcConfigurer {

    /**
     * 配置 SSE 流式端点的异步任务执行器，避免 Spring MVC 默认的 SimpleAsyncTaskExecutor。
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(channelAsyncExecutor());
        configurer.setDefaultTimeout(120_000L);
    }

    @Bean
    public AsyncTaskExecutor channelAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("channel-sse-");
        executor.initialize();
        return executor;
    }

    private static final Logger log = LoggerFactory.getLogger(ChannelExampleConfig.class);

    private GatewayBootstrap gatewayBootstrap;

    @Bean
    public GatewayBootstrap gatewayBootstrap() {
        // 主 Agent: 协调器 — 自动判断并分发到专家子 Agent
        HarnessAgent coordinator = HarnessAgent.builder()
                .name("coordinator")
                .description("智能协调器，根据用户问题自动分发到合适的专家 Agent")
                .sysPrompt(coordinatorPrompt())
                .model("dashscope:qwen-plus")
                .maxIters(5)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                // 声明翻译专家子 Agent
                .subagent(
                        SubagentDeclaration.builder()
                                .name("translator")
                                .description(
                                        "翻译专家。当用户需要翻译、语言转换、"
                                                + "多语言理解时 spawn 此 Agent。")
                                .inlineAgentsBody(translatorPrompt())
                                .persistSession(true)
                                .build())
                // 声明编程助手子 Agent
                .subagent(
                        SubagentDeclaration.builder()
                                .name("coder")
                                .description(
                                        "编程助手。当用户需要编写代码、调试程序、"
                                                + "解答技术问题、讨论架构设计时 spawn 此 Agent。")
                                .inlineAgentsBody(coderPrompt())
                                .persistSession(true)
                                .build())
                .build();

        // 使用 GatewayBootstrap 注册（coordinator 为唯一主 Agent）
        gatewayBootstrap = GatewayBootstrap.builder()
                .agent("coordinator", coordinator)
                .mainAgent("coordinator")
                .build();

        log.info("============================================");
        log.info("  Channel Example 初始化完成");
        log.info("  主 Agent: coordinator (自动路由)");
        log.info("  子 Agent: translator, coder");
        log.info("============================================");

        return gatewayBootstrap;
    }

    @Bean
    public ChatUiChannel chatUiChannel(GatewayBootstrap gw) {
        return gw.chatUiChannel();
    }

    @PreDestroy
    public void onDestroy() {
        if (gatewayBootstrap != null) {
            gatewayBootstrap.stop();
            log.info("Channel Example Gateway 已停止");
        }
    }

    // -----------------------------------------------------------------
    //  System Prompts
    // -----------------------------------------------------------------

    private static String coordinatorPrompt() {
        return """
                你是一个智能协调器。你的职责是分析用户的问题，然后决定自己回答或分发给专家。

                规则：
                - 如果问题是日常知识、常识、文化、生活建议等 → 你自己直接回答
                - 如果问题涉及翻译、语言转换 → spawn translator 子 Agent 处理
                - 如果问题涉及编程、代码、技术 → spawn coder 子 Agent 处理
                - 用中文回答，除非用户明确要求其他语言
                - spawn 子 Agent 后，简要告知用户已将任务交给专家处理
                """;
    }

    private static String translatorPrompt() {
        return """
                你是一个专业的翻译专家。你的职责是帮助用户进行语言翻译。

                规则：
                - 如果用户输入中文，翻译为英文
                - 如果用户输入英文，翻译为中文
                - 如果用户指定目标语言，按指定语言翻译
                - 翻译要准确、自然、符合目标语言的表达习惯
                - 对于有歧义的词句，提供多种可能的翻译并简要说明区别
                - 在翻译结果前标注原文语言和目标语言，例如：[中 → 英]
                """;
    }

    private static String coderPrompt() {
        return """
                你是一个资深的编程助手。你精通多种编程语言和技术框架。

                规则：
                - 用中文解释，代码保持原始语言
                - 代码示例要简洁、可运行、有注释
                - 优先使用主流技术和最佳实践
                - 对于复杂的概念，先给出简要说明再展示代码
                - 如果用户的问题不够明确，先询问编程语言、框架等关键信息
                - 支持的语言包括但不限于：Java, Python, JavaScript, Go, Rust, C++
                """;
    }
}
