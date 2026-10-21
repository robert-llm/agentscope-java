package com.robert.agent.higress.mcptest.provider;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

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

    // ==================== 天气查询接口 ====================

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


    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式查询天气", description = "通过 Server-Sent Events 流式返回指定城市的天气信息")
    public Flux<ServerSentEvent<String>> streamWeatherByPath(
            @Parameter(description = "城市名称，如：北京、上海", required = true)
            @RequestParam("city") String city) {
        log.info("Received streaming request for weather in city: {}", city);

        return Flux.concat(
                // 1. 发送开始事件
                createSseEvent("start", Map.of(
                        "status", "querying",
                        "city", city,
                        "timestamp", LocalDateTime.now().toString()
                )),

                // 2. 延迟后发送城市信息
                Mono.delay(Duration.ofMillis(300))
                        .flatMap(delay -> createSseEvent("city_info", Map.of(
                                "city", city,
                                "queryTime", LocalDateTime.now().toString()
                        ))),

                // 3. 延迟后发送天气状况
                Mono.delay(Duration.ofMillis(600))
                        .flatMap(delay -> createSseEvent("weather", Map.of(
                                "weather", mockWeather(city).weather(),
                                "description", getWeatherDescription(mockWeather(city).weather())
                        ))),

                // 4. 延迟后发送温度信息
                Mono.delay(Duration.ofMillis(900))
                        .flatMap(delay -> createSseEvent("temperature", Map.of(
                                "temperature", mockWeather(city).temperature(),
                                "unit", "摄氏度"
                        ))),

                // 5. 延迟后发送湿度信息
                Mono.delay(Duration.ofMillis(1200))
                        .flatMap(delay -> createSseEvent("humidity", Map.of(
                                "humidity", mockWeather(city).humidity(),
                                "unit", "%"
                        ))),

                // 6. 延迟后发送风力信息
                Mono.delay(Duration.ofMillis(1500))
                        .flatMap(delay -> createSseEvent("wind", Map.of(
                                "wind", mockWeather(city).wind()
                        ))),

                // 7. 延迟后发送完整数据
                Mono.delay(Duration.ofMillis(1800))
                        .flatMap(delay -> createSseEvent("complete", mockWeather(city))),

                // 8. 发送结束事件
                Mono.delay(Duration.ofMillis(2000))
                        .flatMap(delay -> createSseEvent("done", Map.of(
                                "status", "completed",
                                "timestamp", LocalDateTime.now().toString()
                        )))
        );
    }

    @GetMapping(value = "/stream/{city}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式查询天气", description = "通过 Server-Sent Events 流式返回指定城市的天气信息")
    public Flux<ServerSentEvent<String>> streamWeather(
            @Parameter(description = "城市名称，如：北京、上海", required = true)
            @PathVariable("city") String city) {
        log.info("Received streaming request for weather in city: {}", city);

        return Flux.concat(
                // 1. 发送开始事件
                createSseEvent("start", Map.of(
                        "status", "querying",
                        "city", city,
                        "timestamp", LocalDateTime.now().toString()
                )),

                // 2. 延迟后发送城市信息
                Mono.delay(Duration.ofMillis(300))
                        .flatMap(delay -> createSseEvent("city_info", Map.of(
                                "city", city,
                                "queryTime", LocalDateTime.now().toString()
                        ))),

                // 3. 延迟后发送天气状况
                Mono.delay(Duration.ofMillis(600))
                        .flatMap(delay -> createSseEvent("weather", Map.of(
                                "weather", mockWeather(city).weather(),
                                "description", getWeatherDescription(mockWeather(city).weather())
                        ))),

                // 4. 延迟后发送温度信息
                Mono.delay(Duration.ofMillis(900))
                        .flatMap(delay -> createSseEvent("temperature", Map.of(
                                "temperature", mockWeather(city).temperature(),
                                "unit", "摄氏度"
                        ))),

                // 5. 延迟后发送湿度信息
                Mono.delay(Duration.ofMillis(1200))
                        .flatMap(delay -> createSseEvent("humidity", Map.of(
                                "humidity", mockWeather(city).humidity(),
                                "unit", "%"
                        ))),

                // 6. 延迟后发送风力信息
                Mono.delay(Duration.ofMillis(1500))
                        .flatMap(delay -> createSseEvent("wind", Map.of(
                                "wind", mockWeather(city).wind()
                        ))),

                // 7. 延迟后发送完整数据
                Mono.delay(Duration.ofMillis(1800))
                        .flatMap(delay -> createSseEvent("complete", mockWeather(city))),

                // 8. 发送结束事件
                Mono.delay(Duration.ofMillis(2000))
                        .flatMap(delay -> createSseEvent("done", Map.of(
                                "status", "completed",
                                "timestamp", LocalDateTime.now().toString()
                        )))
        );
    }

    private Mono<ServerSentEvent<String>> createSseEvent(String eventName, Object data) {
        try {
            String jsonData = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(data);
            return Mono.just(
                    ServerSentEvent.<String>builder()
                            .event(eventName)
                            .data(jsonData)
                            .build()
            );
        } catch (Exception e) {
            log.error("Failed to serialize SSE event: {}", eventName, e);
            return Mono.just(
                    ServerSentEvent.<String>builder()
                            .event("error")
                            .data("{\"message\":\"序列化失败\"}")
                            .build()
            );
        }
    }

    private String getWeatherDescription(String weather) {
        return switch (weather) {
            case "晴" -> "天气晴朗，适合户外活动";
            case "多云" -> "云层较多，阳光时隐时现";
            case "小雨" -> "有轻微降雨，记得携带雨具";
            case "阴" -> "天空阴沉，光线较暗";
            default -> "天气情况一般";
        };
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
