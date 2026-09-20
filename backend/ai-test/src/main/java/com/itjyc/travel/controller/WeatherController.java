package com.itjyc.travel.controller;

import com.itjyc.travel.tool.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 天气查询接口：直接返回结构化天气数据，供前端渲染（绕过 LLM 转述，避免格式被破坏）。
 */
@RestController
@RequestMapping("/api")
public class WeatherController {

    private final WeatherTool weatherTool;
    private final ChatClient chatClient;

    public WeatherController(WeatherTool weatherTool, @Qualifier("chatClient") ChatClient chatClient) {
        this.weatherTool = weatherTool;
        this.chatClient = chatClient;
    }

    @GetMapping("/weather")
    public WeatherResponse weather(@RequestParam String query) {
        String city = extractCity(query);
        List<WeatherTool.WeatherDay> days = weatherTool.getWeatherData(city, 3);
        return new WeatherResponse(city, days);
    }

    /** 用 LLM 从查询里提取城市名。 */
    private String extractCity(String query) {
        return chatClient.prompt()
                .system("从用户的话里提取城市名，只返回城市名本身，如：北京。")
                .user(query)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "weather-extract"))
                .call()
                .entity(CityName.class)
                .city();
    }

    public record CityName(String city) {}

    public record WeatherResponse(String city, List<WeatherTool.WeatherDay> days) {}
}
