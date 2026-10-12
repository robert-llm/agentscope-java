package com.robert.agent.higress.mcptest.customer;

import com.robert.agent.higress.mcptest.provider.WeatherAgentApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 测试前先要打开docker 和 higress 以及启动provider服务
 * @Author: luorenshu
 * @Date: 2026/10/10/14:26
 * @Description:
 **/
@SpringBootApplication
@ComponentScan(
        basePackages = {
                "com.robert.agent.higress.mcptest.customer",
                "io.agentscope"
        }
)
public class WeatherCustomerApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WeatherCustomerApplication.class);
        app.setAdditionalProfiles("customer");
        app.run(args);
    }
}
