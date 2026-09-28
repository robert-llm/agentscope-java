package com.robert.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * agentscope-robert 模块 Spring Boot 启动类。
 *
 * <p>启动内部 Router Agent 多 Agent 路由编排体系：
 * <ul>
 *   <li>{@code RouterAgentConfig} — 注册 AgentRegistry、子 Agent、线程池</li>
 *   <li>{@code RouterStreamController} — SSE 流式对话接口 {@code /api/router/stream}</li>
 *   <li>{@code RouterAgent} — 路由规划 + 串行调度 + 事件转发</li>
 * </ul></p>
 *
 * <p><b>环境变量：</b>
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} (required) — DashScope API Key</li>
 *   <li>{@code SERVER_PORT} (default: 18096) — HTTP 端口</li>
 * </ul></p>
 *
 * <p><b>测试接口：</b>
 * <pre>{@code
 * curl -X POST http://localhost:18096/api/router/stream \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *     "question": "你好",
 *     "conversationId": "c001",
 *     "messageId": "m001",
 *     "loginName": "tester",
 *     "targetTeams": ["test-chat"]
 *   }'
 * }</pre></p>
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.robert.agent",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.robert\\.agent\\.exampleagent\\..*"
        )
)
public class RobertApplication {

    private static final Logger log = LoggerFactory.getLogger(RobertApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RobertApplication.class, args);
        log.info("============================================");
        log.info("  agentscope-robert 启动完成");
        log.info("  SSE 流式接口: http://localhost:{}/api/router/stream", port());
        log.info("  MVC 流式接口: http://localhost:{}/api/router/stream2", port());
        log.info("============================================");
    }

    private static String port() {
        String p = System.getenv("SERVER_PORT");
        return (p != null && !p.isBlank()) ? p : "18096";
    }
}
