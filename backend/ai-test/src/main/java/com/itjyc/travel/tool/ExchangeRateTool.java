package com.itjyc.travel.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 汇率工具（open.er-api.com，免 key）。
 * 结果缓存到 Redis（1 小时），同一基准货币的汇率只查一次 API，key 前缀 exchangerate:。
 */
@Component
public class ExchangeRateTool {

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final RestClient client;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ExchangeRateTool(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.client = RestClient.builder().baseUrl("https://open.er-api.com/v6").build();
    }

    /** 查询 1 单位源货币等于多少目标货币。 */
    @Tool(description = "查询两种货币之间的实时汇率，返回 1 单位源货币等于多少目标货币")
    public double getExchangeRate(@ToolParam(description = "源货币代码，如 USD、CNY、EUR") String from,
                                  @ToolParam(description = "目标货币代码，如 CNY、USD") String to) {
        String base = from == null ? "USD" : from.trim().toUpperCase();
        String target = to == null ? "CNY" : to.trim().toUpperCase();

        String cacheKey = "exchangerate:" + base;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                double rate = parseRate(cached, target);
                if (rate > 0) return rate;
            }
        } catch (Exception ignored) {
            // Redis 不可用，降级为直接查询
        }

        try {
            JsonNode body = client.get().uri("/latest/" + base).retrieve().body(JsonNode.class);
            JsonNode rates = body == null ? null : body.path("rates");
            double result = rates == null ? 0 : rates.path(target).asDouble(0);
            if (rates != null && result > 0) {
                try {
                    redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(rates), CACHE_TTL);
                } catch (Exception ignored) {
                }
            }
            return result;
        } catch (Exception e) {
            return 0;  // 查询失败，返回 0
        }
    }

    private double parseRate(String json, String target) {
        try {
            JsonNode rates = objectMapper.readTree(json);
            JsonNode rate = rates.get(target);
            return rate == null ? 0 : rate.asDouble(0);
        } catch (Exception e) {
            return 0;
        }
    }
}
