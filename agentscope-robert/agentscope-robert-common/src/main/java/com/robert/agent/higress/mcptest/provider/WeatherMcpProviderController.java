package com.robert.agent.higress.mcptest.provider;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/mcp/api/weather")
@Tag(name = "Weather", description = "天气查询接口（Mock）")
/**
 * 通过：http://localhost:18099/v3/api-docs 可以获取接口文档数据，然后把这个数据拷贝到higress界面即可
 * @Author: luorenshu
 * @Date: 2026/10/10/11:11
 * @Description:
 **/
public class WeatherMcpProviderController {
    private static final Logger log = LoggerFactory.getLogger(WeatherMcpProviderController.class);

    @GetMapping
    @Operation(summary = "按城市名查询天气", description = "通过 query 参数 city 查询指定城市的天气信息")
    public WeatherResponse getWeather(
            @Parameter(description = "城市名称，如：北京、上海", required = true)
            @RequestParam("city") String city) {
        log.info("Received request for weather in city: {}", city);
        return mockWeather(city);
    }

    @GetMapping("/{city}")
    @Operation(summary = "按城市名查询天气（路径参数）", description = "通过路径参数 city 查询指定城市的天气信息")
    public WeatherResponse getWeatherByPath(
            @Parameter(description = "城市名称，如：北京、上海", required = true)
            @PathVariable("city") String city) {
        log.info("Received request for weather in city: {}", city);
        return mockWeather(city);
    }

    private WeatherResponse mockWeather(String city) {
        String now = LocalDateTime.now().toString();
        return switch (city) {
            case "北京" -> new WeatherResponse("北京", "晴", 25, 40, "西北风 3 级", now);
            case "上海" -> new WeatherResponse("上海", "多云", 27, 65, "东南风 2 级", now);
            case "广州" -> new WeatherResponse("广州", "小雨", 30, 80, "南风 2 级", now);
            case "深圳" -> new WeatherResponse("深圳", "阴", 29, 75, "东风 3 级", now);
            default -> new WeatherResponse(city, "未知", 20 + (int)(Math.random() * 15), 50 + (int)(Math.random() * 40), "微风", now);
        };
    }

    @Schema(description = "天气查询响应")
    public record WeatherResponse(
            @Schema(description = "城市名称", example = "北京")
            String city,
            @Schema(description = "天气状况", example = "晴")
            String weather,
            @Schema(description = "温度（摄氏度）", example = "25")
            Integer temperature,
            @Schema(description = "湿度（百分比）", example = "40")
            Integer humidity,
            @Schema(description = "风力风向", example = "西北风 3 级")
            String wind,
            @Schema(description = "查询时间")
            String timestamp
    ) {

    }
}
