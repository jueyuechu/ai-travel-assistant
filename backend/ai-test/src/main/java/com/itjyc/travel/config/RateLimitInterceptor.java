package com.itjyc.travel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;

/**
 * 简单限流：Redis 固定窗口（按 IP + 分钟），保护 LLM API 成本、防滥用。
 * Redis 不可用时降级放行（限流不阻断正常对话）。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;
    private final int maxPerMinute;

    public RateLimitInterceptor(StringRedisTemplate redis,
                                @Value("${ratelimit.max-per-minute:30}") int maxPerMinute) {
        this.redis = redis;
        this.maxPerMinute = maxPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;  // CORS 预检请求放行
        }
        String ip = clientIp(request);
        long minute = System.currentTimeMillis() / 60000;
        String key = PREFIX + ip + ":" + minute;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofSeconds(61));
            }
            if (count != null && count > maxPerMinute) {
                response.setStatus(429);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("请求过于频繁，请稍后再试");
                return false;
            }
        } catch (Exception ignored) {
            // Redis 不可用：降级放行
        }
        return true;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
