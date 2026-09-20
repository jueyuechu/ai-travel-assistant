package com.itjyc.travel.tool;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 和风天气工具（官方 API，专属 Host）。
 * 流程：城市名 → 高德地理编码拿经纬度 → 和风按经纬度查天气。
 * 结果缓存到 Redis（1.5 小时），重复查询跳过 API；Redis 不可用时自动降级为直接查询。
 * key 前缀 weather:geo:（经纬度）、weather:data:（天气），与向量库 doc: 区分。
 */
@Component
public class WeatherTool {

    private static final Duration CACHE_TTL = Duration.ofMinutes(90);  // 1.5 小时

    private final RestClient qweatherClient;
    private final RestClient amapClient;
    private final String qweatherKey;
    private final String amapKey;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public WeatherTool(@Value("${qweather.api-key}") String qweatherKey,
                       @Value("${qweather.api-host}") String apiHost,
                       @Value("${amap.api-key}") String amapKey,
                       StringRedisTemplate redis,
                       ObjectMapper objectMapper) {
        this.qweatherKey = qweatherKey;
        this.amapKey = amapKey;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.qweatherClient = RestClient.builder().baseUrl(apiHost).build();
        this.amapClient = RestClient.builder().baseUrl("https://restapi.amap.com/v3").build();
    }

    /** 单日天气。 */
    public record WeatherDay(String date, String weather, String tempMin, String tempMax, String wind) {}

    /** 查询城市未来几天天气，返回结构化数据（供前端直接渲染）。 */
    public List<WeatherDay> getWeatherData(String city, int days) {
        String key = "weather:data:" + city + ":" + days;
        List<WeatherDay> cached = readWeatherCache(key);
        if (cached != null) return cached;

        String location = geocode(city);
        String path = days <= 3 ? "/v7/weather/3d" : "/v7/weather/7d";

        JsonNode body = qweatherClient.get()
                .uri(u -> u.path(path)
                        .queryParam("location", location)
                        .queryParam("key", qweatherKey)
                        .build())
                .retrieve().body(JsonNode.class);

        List<WeatherDay> result = new ArrayList<>();
        JsonNode daily = body == null ? null : body.path("daily");
        if (daily != null && daily.isArray()) {
            for (JsonNode d : daily) {
                String date = d.path("fxDate").asText("");
                if (date.length() >= 10) {
                    date = date.substring(5);   // "2026-09-16" -> "09-16"
                }
                result.add(new WeatherDay(
                        date,
                        d.path("textDay").asText(""),
                        d.path("tempMin").asText(""),
                        d.path("tempMax").asText(""),
                        d.path("windDirDay").asText("") + " " + d.path("windScaleDay").asText("") + "级"
                ));
            }
        }
        writeWeatherCache(key, result);
        return result;
    }

    /** 查询城市未来几天天气，返回 markdown 表格（给 LLM 工具调用用，供前端渲染）。 */
    @Tool(description = "查询某城市未来几天的天气，返回每日天气摘要")
    public String getWeather(@ToolParam(description = "城市名，如 北京") String city,
                             @ToolParam(description = "天数，3 或 7") int days) {
        List<WeatherDay> list = getWeatherData(city, days);
        StringBuilder sb = new StringBuilder(city).append(" 未来天气:\n\n");
        sb.append("| 日期 | 天气 | 最低温 | 最高温 | 风力 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        for (WeatherDay d : list) {
            sb.append("| ").append(d.date())
                    .append(" | ").append(d.weather())
                    .append(" | ").append(d.tempMin()).append("℃")
                    .append(" | ").append(d.tempMax()).append("℃")
                    .append(" | ").append(d.wind()).append(" |\n");
        }
        return sb.toString();
    }

    /** 城市名 → "lng,lat"（高德地理编码），结果缓存 Redis。 */
    private String geocode(String city) {
        String key = "weather:geo:" + city;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) return cached;
        } catch (Exception ignored) {
            // Redis 不可用，降级为直接查询
        }

        JsonNode body = amapClient.get()
                .uri(u -> u.path("/geocode/geo")
                        .queryParam("key", amapKey)
                        .queryParam("address", city)
                        .build())
                .retrieve().body(JsonNode.class);
        JsonNode loc = body == null ? null : body.path("geocodes").path(0).path("location");
        String result = loc == null || loc.isMissingNode() ? "" : loc.asText();
        if (!result.isBlank()) {
            try {
                redis.opsForValue().set(key, result, CACHE_TTL);
            } catch (Exception ignored) {
                // 缓存写失败忽略
            }
        }
        return result;
    }

    private List<WeatherDay> readWeatherCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, new TypeReference<List<WeatherDay>>() {});
        } catch (Exception ignored) {
            return null;  // 读失败或反序列化失败，忽略缓存
        }
    }

    private void writeWeatherCache(String key, List<WeatherDay> days) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(days), CACHE_TTL);
        } catch (Exception ignored) {
            // 缓存写失败忽略
        }
    }
}
