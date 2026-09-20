package com.itjyc.travel.domain;

import java.util.List;

/**
 * 行程 —— 编排第④步「生成」、第⑦步「输出」的结构化结果。
 * 含经纬度，前端可直接渲染地图点位、预算表、提醒。
 * 数值字段用包装类型：Jackson 3 对基本类型的 null 会抛异常，包装类型可为 null，更鲁棒。
 */
public record Itinerary(
        String destination,
        List<DayPlan> days,
        Double totalCost,
        List<String> alerts          // 提醒：闭馆、需预约、天气提示等
) {
    /** 单日计划。 */
    public record DayPlan(
            Integer day,
            String date,             // 日期，如 2026-10-01
            String weather,          // 当天天气摘要
            List<Stop> stops,
            Double dayCost
    ) {}

    /** 单个行程点：景点/餐饮/酒店/交通。 */
    public record Stop(
            String time,             // "09:00-11:30"
            String type,             // attraction / food / hotel / transport
            String name,
            Double longitude,        // 供前端地图打点
            Double latitude,
            String address,
            Integer durationMin,     // 停留时长（分钟）
            Double cost,
            String note              // 备注（预约/开放时间等）
    ) {}
}
