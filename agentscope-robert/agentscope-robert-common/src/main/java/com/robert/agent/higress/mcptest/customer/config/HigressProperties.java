package com.robert.agent.higress.mcptest.customer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agentscope.higress")
public class HigressProperties {
    private String mcpEndpoint;
    private String clientName = "higress";
    private String clientKeyAuth;
}
