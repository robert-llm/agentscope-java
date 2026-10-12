package com.robert.agent.higress.mcptest.customer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agentscope.model.dashscope")
public class DashScopeProperties {
    private String apiKey;
    private String modelName = "qwen-max";
}
