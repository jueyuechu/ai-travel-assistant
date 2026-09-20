package com.itjyc.travel.config;

import com.itjyc.travel.memory.RedisChatMemoryRepository;
import com.itjyc.travel.tool.ExchangeRateTool;
import com.itjyc.travel.tool.MapTool;
import com.itjyc.travel.tool.SearchAndIndexTool;
import com.itjyc.travel.tool.WeatherTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 ChatClient。按意图拆分工具集，避免「工具全挂」导致 agentic 循环不收敛。
 *
 * - chatClient         基础：仅会话记忆，不挂工具（行程呈现/建议）
 * - planningChatClient 规划内部（抽取/生成/修正）：无记忆，避免内部 JSON 污染主会话
 * - realtimeChatClient 实况：天气 + 汇率 + 搜索沉淀（天气/汇率/资讯）
 * - qaChatClient       问答：搜索沉淀 + 地图（攻略/地点）
 *
 * 注：RAG 检索在 Controller 手动完成（不再用 QuestionAnswerAdvisor），
 * 以便「检索不到相关内容时用自身知识 fallback」，而非默认模板的「无法回答」。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    ChatMemory chatMemory(RedisChatMemoryRepository repository) {
        // 滑动窗口记忆存 Redis（重启不丢），保留最近 20 条消息
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }

    /** 基础：仅记忆，不挂工具。 */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** 规划内部 LLM 调用（抽取/生成/修正）专用：不挂记忆，避免内部 JSON 污染主会话，也避开 MessageChatMemoryAdvisor 强制的 conversationId 必填。 */
    @Bean
    ChatClient planningChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /** 实况查询：天气 + 汇率 + 联网搜索。不挂地图，避免查天气时误触地图/路线。 */
    @Bean
    ChatClient realtimeChatClient(ChatClient.Builder builder,
                                  WeatherTool weatherTool,
                                  ExchangeRateTool exchangeRateTool,
                                  SearchAndIndexTool searchTool,
                                  ChatMemory chatMemory) {
        return builder
                .defaultTools(weatherTool, exchangeRateTool, searchTool)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** 问答：联网搜索 + 地图。不挂天气，天气类问题由意图路由走实况 client。 */
    @Bean
    ChatClient qaChatClient(ChatClient.Builder builder,
                            MapTool mapTool,
                            SearchAndIndexTool searchTool,
                            ChatMemory chatMemory) {
        return builder
                .defaultTools(searchTool, mapTool)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
