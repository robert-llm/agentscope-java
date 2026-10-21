package com.robert.agent.higress.mcptest.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * @Author: luorenshu
 * @Date: 2026/10/10/14:26
 * @Description:
 **/
@SpringBootApplication
@ComponentScan(
        basePackages = {
                "com.robert.agent.higress.mcptest.provider",
                "com.robert.agent.higress.mcptest.service",
                "io.agentscope"
        }
)
public class WeatherAgentApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WeatherAgentApplication.class);
        app.setAdditionalProfiles("provider");
        app.run(args);
    }
}
