package com.robert.agent.higress.mcptest.provider.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
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
    public GroupedOpenApi handbookApi() {
        return GroupedOpenApi.builder()
                .group("handbook")
                .pathsToMatch("/mcp/api/handbook/**")
                .build();
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("auth")
                .pathsToMatch("/mcp/api/auth/**")
                .build();
    }

    /**
     * 全局 Bearer Auth 安全方案，供 Swagger UI 使用。
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT 认证令牌，通过 /mcp/api/auth/login 获取")));
    }
}
