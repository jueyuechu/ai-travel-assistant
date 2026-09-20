package com.itjyc.travel.domain;

/**
 * 校验问题 —— 编排第⑤步「校验」的产出。
 * 必须可定位到具体 item（day + itemName），第⑥步「修正」才改得动。
 */
public record ValidationIssue(
        Integer day,
        String itemName,
        String type,                // route_time / closed / over_budget / pace
        String problem,
        String suggestion
) {}
