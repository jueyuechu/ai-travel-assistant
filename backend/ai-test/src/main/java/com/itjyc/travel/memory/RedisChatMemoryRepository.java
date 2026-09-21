package com.itjyc.travel.memory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redis 版 ChatMemoryRepository：把 LLM 多轮上下文持久化到 Redis，后端重启记忆不丢。
 * 只负责存取；窗口滑动（保留最近 N 条）由 MessageWindowChatMemory 复用，Redis 里始终只存窗口内消息。
 *
 * 存储：chat:memory:{conversationId} → JSON 数组 [{"type":"USER","text":"..."}]
 * 只存 USER/ASSISTANT/SYSTEM；TOOL（工具返回）不记忆。
 */
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String PREFIX = "chat:memory:";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        // MessageWindowChatMemory 不调用此方法；如需列全会话再补 SCAN
        return List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            String json = redis.opsForValue().get(PREFIX + conversationId);
            if (json == null) {
                log.info("[chat-memory] 读 {} -> 空", conversationId);
                return List.of();
            }
            JsonNode arr = objectMapper.readTree(json);
            List<Message> messages = new ArrayList<>();
            for (JsonNode node : arr) {
                Message m = toMessage(node.path("type").asText(""), node.path("text").asText(""));
                if (m != null) messages.add(m);
            }
            log.info("[chat-memory] 读 {} -> {} 条", conversationId, messages.size());
            return messages;
        } catch (Exception e) {
            log.warn("[chat-memory] 读 {} 失败", conversationId, e);
            return List.of();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            List<Map<String, String>> list = new ArrayList<>();
            for (Message m : messages) {
                if (m == null || m.getMessageType() == MessageType.TOOL) continue;
                list.add(Map.of("type", m.getMessageType().name(),
                        "text", m.getText() == null ? "" : m.getText()));
            }
            redis.opsForValue().set(PREFIX + conversationId, objectMapper.writeValueAsString(list), TTL);
            log.info("[chat-memory] 写 {} -> {} 条", conversationId, list.size());
        } catch (Exception e) {
            log.warn("[chat-memory] 写 {} 失败", conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(PREFIX + conversationId);
    }

    private Message toMessage(String type, String text) {
        return switch (type) {
            case "USER" -> new UserMessage(text);
            case "ASSISTANT" -> new AssistantMessage(text);
            case "SYSTEM" -> new SystemMessage(text);
            default -> null;
        };
    }
}
