package com.robert.agent.higress.mcptest.provider.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: luorenshu
 * @Date: 2026/10/21/16:42
 * @Description:
 **/
@Configuration
public class SpringDocConfig {
    @Bean
    public GroupedOpenApi weatherApi() {
        return GroupedOpenApi.builder()
                .group("weather")
                .pathsToMatch("/mcp/api/weather/**")
                .build();
    }

    @Bean
    public GroupedOpenApi handbootApi() {
        return GroupedOpenApi.builder()
                .group("handbook")
                .pathsToMatch("/mcp/api/handbook/**")
                .build();
    }
}
