package com.robert.agent.higress.mcptest.customer;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/mcp/customer")
@Tag(name = "Weather", description = "天气查询接口（Mock）")
/**
 * 通过：http://localhost:18099/v3/api-docs 可以获取接口文档数据，然后把这个数据拷贝到higress界面即可
 * @Author: luorenshu
 * @Date: 2026/10/10/11:11
 * @Description:
 **/
public class WeatherMcpCustomerController {

    @Autowired
    private ReActAgent weatherAgent; // Changed from private final ReActAgent weatherAgent;

    /**
     * 统一的 Agent 对话接口
     * Agent 会自动决定是否调用已注册的 Higress MCP 工具
     *
     * GET /api/agent/chat?message=北京今天天气怎么样
     */
    @GetMapping("/chat")
    @Operation(summary = "聊天测试", description = "查询指定城市的天气信息")
    public Map<String, String> getWeatherByPath(
            @Parameter(description = "聊天内容", required = true)
            @RequestParam("message") String message) {
        Msg response = weatherAgent.call(message).block();
        String text = response.getTextContent();
        return Map.of(
                "question", message,
                "answer", text
        );
    }
}
