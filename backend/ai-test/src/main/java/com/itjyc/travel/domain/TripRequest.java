package com.itjyc.travel.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 旅行需求 —— 编排第②步「问清需求」的结构化结果。
 * 字段为 null 表示尚未收集，用于判断缺哪些必填字段、驱动追问。
 */
public record TripRequest(
        String destination,          // 目的地（必填）
        String startDate,            // 出发日期，如 2026-10-01（可空，默认近期）
        Integer days,                // 天数（必填）
        Integer adults,              // 成人（默认 2）
        Integer children,            // 儿童（默认 0）
        BigDecimal budget,           // 预算/元（必填）
        List<String> preferences,    // 偏好：美食/文化/亲子/自然...
        String pace                  // 强度：轻松/适中/紧凑（默认 适中）
) {
    public TripRequest {
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
    }

    /** 缺失的必填字段，用于驱动追问（最多 2 轮）。 */
    public List<String> missingRequiredFields() {
        List<String> missing = new ArrayList<>();
        if (destination == null || destination.isBlank()) missing.add("目的地");
        if (days == null) missing.add("天数");
        if (budget == null) missing.add("预算");
        return missing;
    }

    /** 必填字段是否已收集齐全。 */
    public boolean isComplete() {
        return missingRequiredFields().isEmpty();
    }

    /** 合并：用 newer 的非空字段覆盖当前值，用于多轮追问累积需求。 */
    public TripRequest merge(TripRequest newer) {
        if (newer == null) return this;
        return new TripRequest(
                newer.destination != null ? newer.destination : destination,
                newer.startDate != null ? newer.startDate : startDate,
                newer.days != null ? newer.days : days,
                newer.adults != null ? newer.adults : adults,
                newer.children != null ? newer.children : children,
                newer.budget != null ? newer.budget : budget,
                (newer.preferences != null && !newer.preferences.isEmpty()) ? newer.preferences : preferences,
                newer.pace != null ? newer.pace : pace
        );
    }
}
