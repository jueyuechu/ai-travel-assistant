package com.itjyc.travel.memory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 会话历史存储（Redis）：由前端主导同步，存完整的展示消息（含行程卡片）。
 * 与 ChatMemory（LLM 多轮上下文，内存）分离。
 *
 * - 数据：session:data:{conversationId} → 前端 messages 的 JSON，TTL 7 天
 * - 元信息：session:meta → Hash{conversationId → {title, lastTime}}
 */
@Component
public class SessionStore {

    private static final String DATA_PREFIX = "session:data:";
    private static final String META_KEY = "session:meta";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public SessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 保存（或覆盖）某个会话的历史消息。 */
    public void save(String conversationId, String title, JsonNode messages) {
        try {
            redis.opsForValue().set(DATA_PREFIX + conversationId, objectMapper.writeValueAsString(messages), TTL);
            Meta meta = new Meta(title == null || title.isBlank() ? "新对话" : title, System.currentTimeMillis());
            redis.opsForHash().put(META_KEY, conversationId, objectMapper.writeValueAsString(meta));
        } catch (Exception ignored) {
            // 存储失败不影响对话
        }
    }

    /** 加载某个会话的历史消息，不存在返回 null。 */
    public JsonNode load(String conversationId) {
        try {
            String json = redis.opsForValue().get(DATA_PREFIX + conversationId);
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 会话列表，按最后更新时间倒序。 */
    public List<SessionSummary> list() {
        Map<Object, Object> entries = redis.opsForHash().entries(META_KEY);
        List<SessionSummary> result = new ArrayList<>();
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            try {
                Meta meta = objectMapper.readValue(e.getValue().toString(), Meta.class);
                result.add(new SessionSummary(e.getKey().toString(), meta.title(), meta.lastTime()));
            } catch (Exception ignored) {
                // 单条损坏，跳过
            }
        }
        result.sort(Comparator.comparingLong(SessionSummary::lastTime).reversed());
        return result;
    }

    /** 删除会话。 */
    public void delete(String conversationId) {
        redis.delete(DATA_PREFIX + conversationId);
        redis.opsForHash().delete(META_KEY, conversationId);
    }

    private record Meta(String title, long lastTime) {}

    public record SessionSummary(String id, String title, long lastTime) {}
}
