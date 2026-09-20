package com.itjyc.travel.orchestrator;

import com.itjyc.travel.domain.IntentType;
import org.springframework.stereotype.Component;

/**
 * 第①步「意图路由」：把用户请求分成三类。
 */
@Component
public class IntentRouter {

    /**
     * 骨架实现：先用关键词规则兜底，后续换成 ChatClient + 结构化输出分类。
     * 优先级：规划类 &gt; 查实况类 &gt; 问答类。
     */
    public IntentType route(String message) {
        if (message == null || message.isBlank()) return IntentType.QA;
        String m = message.trim();

        if (containsAny(m, "规划", "行程", "攻略", "路线", "安排", "玩几天", "几天")) return IntentType.PLAN;
        if (containsAny(m, "天气", "气温", "汇率", "价格", "多少钱", "距离", "多远", "怎么去")) return IntentType.REALTIME;
        return IntentType.QA;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
