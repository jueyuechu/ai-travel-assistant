package com.itjyc.travel.domain;

/**
 * 意图路由结果 —— 编排第①步。
 */
public enum IntentType {
    QA,          // 问答类：走 RAG 直接答
    REALTIME,    // 查实况：调工具（天气/汇率/地图）
    PLAN         // 规划类：进规划流水线
}
