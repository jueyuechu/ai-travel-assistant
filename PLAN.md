# 旅游 AI 助手 — 项目规划

> 更新日期:2026-09-16
> 技术栈:Spring AI 2.0 + MCP + RAG

---

## 1. 项目概述

基于 **Spring AI 2.0 + MCP + RAG** 的旅游 AI 助手,提供三类核心能力:

| 能力 | 说明 | 状态 |
|---|---|---|
| 攻略问答 | 基于 RAG 知识库回答目的地、景点、攻略问题 | ⏳ 阶段 2 |
| 实时查询 | 天气、地图 POI / 路径、联网搜索(调工具) | ✅ |
| 行程规划 | 多轮澄清需求后生成行程,并校验合理性 | ✅ |

---

## 2. 技术栈选型(已定)

| 组件 | 选型 | 说明 |
|---|---|---|
| 框架 | Spring Boot 4.1.1 + Java 21 | |
| AI 框架 | Spring AI 2.0.1(BOM) | |
| 对话模型 | DeepSeek | `spring-ai-starter-model-deepseek` |
| Embedding | 阿里百炼 `qwen3.7-text-embedding` | 走 openai starter 的 OpenAI 兼容接口(见踩坑#1) |
| 向量库 | **Redis Stack**(Linux 虚拟机 + Docker) | Windows 本地 Redis 无向量搜索(见踩坑#5) |
| 地图工具 | 高德 Web 服务 API | `@Tool` |
| 天气工具 | 和风天气(专属 Host + 经纬度) | `@Tool`(见踩坑#9/#10) |
| 联网搜索 | Tavily MCP(stdio) | `spring-ai-starter-mcp-client` |
| 会话记忆 | `MessageWindowChatMemory`(内存) | 生产可换 Redis |
| 工具选择 | `spring-ai-starter-tool-search-advisor` | |

---

## 3. MCP 选型(已核实)

- **核心策略**:官方 API 优先写 `@Tool`,MCP 客户端只接「有官方维护」的开源 server。
- **联网搜索**:Tavily(`npx tavily-mcp@latest`,官方,2.4k★)。
- **已考察不用**:Firecrawl(7.4k★)、Brave(1.4k★)作为备选;`china-travel-kit`(195★,入境游,可参考)、`mako202605/china-travel-mcp`(0★,放弃)。
- **刷 star 提醒**:`worldmonitor`(86k★)、`Agent-Reach`(82k★)等 star 异常高的多为刷的,不可信。

依赖与配置(Windows 注意 `cmd /c` 包装,见踩坑#2):

```yaml
spring:
  ai:
    mcp:
      client:
        request-timeout: 120s       # 首次 npx 下载慢(见踩坑#3)
        stdio:
          connections:
            tavily:
              command: cmd          # Windows 下必须 cmd /c(见踩坑#2)
              args: ["/c", "npx", "-y", "tavily-mcp@latest"]
              env:
                TAVILY_API_KEY: ${TAVILY_API_KEY}
```

---

## 4. 官方 API 工具清单

| 能力 | API | 方式 | 备注 |
|---|---|---|---|
| 地图/POI/路径/地理编码 | 高德 Web 服务 API | `@Tool` | `restapi.amap.com/v3` |
| 天气 | 和风天气 | `@Tool` | 专属 Host + 高德地理编码转经纬度(见踩坑#9/#10) |
| 汇率 | 无官方公开 API | `@Tool` | 用 `open.er-api.com`(免 key)或 `exchangerate.host` |

---

## 5. 向量持久化(Redis Stack,Linux 虚拟机)

- **环境**:Linux 虚拟机 + Docker 跑 `redis/redis-stack-server:latest`(自带 RediSearch),IP `192.168.100.128`,密码 `1234`。
- **原因**:Windows 本地 Redis 无法加载 RediSearch 模块(见踩坑#5)。
- Spring AI `spring-ai-starter-vector-store-redis` 用 **Jedis**(见踩坑#4),索引 HNSW + COSINE。

```yaml
spring:
  data:
    redis:
      client-type: jedis        # 必须,默认 Lettuce 会报错(见踩坑#4)
      host: 192.168.100.128
      port: 6379
      password: "1234"
  ai:
    vectorstore:
      redis:
        initialize-schema: true  # 2.0 起必须显式开启
        index-name: travel-index
        prefix: doc
```

> ⚠️ 当前 vectorstore 配置**被注释禁用**(阶段 2 RAG 时才启用),`RedisVectorStoreAutoConfiguration` 也在 `autoconfigure.exclude` 里。

---

## 6. 编排方案(已实现)

```
用户输入
   │
   ▼
① 意图路由(关键词规则,三分)
   ├─ 问答类 ──► 直接对话(可调工具,SSE 流式)
   ├─ 查实况 ──► 直接对话(可调工具,SSE 流式)
   └─ 规划类 ──► 规划流水线 ▼
                  ② 抽取需求(多轮追问,缺字段累积)
                  ③ 生成行程(LLM 结构化输出,可调天气/地图)
                  ④ 校验(高德查真实通勤时间,>90min 标记问题)
                  ⑤ 修正(有问题重新生成)
                  ⑥ 呈现(LLM 转友好文本)
```

- **多轮追问**:会话级 `Map<conversationId, TripRequest>` 累积需求,`TripRequest.merge()` 合并非空字段。
- **会话记忆**:`MessageChatMemoryAdvisor` + `conversationId`。
- **结构化输出**:`chatClient.call().entity(record.class)`(Spring AI 2.0)。

---

## 7. 数据结构(已定)

| 类型 | 位置 | 说明 |
|---|---|---|
| `TripRequest` | `domain/TripRequest.java` | 需求,`null` 表示未收集,含 `merge()` / `missingRequiredFields()` |
| `Itinerary` | `domain/Itinerary.java` | 行程,`DayPlan → Stop`(含经纬度) |
| `ValidationIssue` | `domain/ValidationIssue.java` | 校验问题,定位到 `day + itemName` |
| `IntentType` | `domain/IntentType.java` | `QA / REALTIME / PLAN` |

> 注意:日期用 `String`,数值用包装类型(`Integer`/`Double`)——避开 Jackson 3 的坑(见踩坑#8/#11)。

---

## 8. 目录结构(当前)

```
com.itjyc.travel
├── domain/
│   ├── TripRequest.java
│   ├── Itinerary.java
│   ├── ValidationIssue.java
│   └── IntentType.java
├── orchestrator/
│   ├── IntentRouter.java        # ① 意图路由(关键词规则)
│   └── TripPlanner.java         # ②~⑥ 流水线(抽取/生成/校验/修正)
├── tool/
│   ├── MapTool.java             # 高德 searchPoi / getRoute
│   └── WeatherTool.java         # 和风(高德地理编码 + 经纬度查天气)
├── controller/
│   └── TravelAssistantController.java  # SSE 流式入口
└── config/
    └── ChatClientConfig.java    # ChatClient + 工具 + 会话记忆
```

---

## 9. 分阶段实施计划

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0. 配置打通 | DeepSeek + embedding + Redis Stack | ✅ |
| 1. 工具/MCP | 高德 + 和风 + Tavily | ✅ |
| 2. RAG 管道 | ETL + embedding 入库 + 检索增强 | ⏳ 待做 |
| 3. 编排 | 意图路由 + 规划流水线 + SSE/记忆/追问 | ✅ |
| 4. 前端 | SSE 流式 + 行程卡片 + 地图点位 | ⏳ 待做 |
| 5. 生产化 | 鉴权/限流/缓存/观测;ChatMemory 换 Redis | ⏳ 待做 |

---

## 10. 下一步

1. **验证本轮改动**:IDEA 编译重启,验证行程卡片、RAG fallback、流式体验(本轮后端改了 `/api/plan` + 手动 RAG,尚未编译)。
2. **地图点位(阶段 4 剩余)**:行程卡片里加高德地图点位(需 JS API key)。
3. **RAG 知识库扩充**:现在只有 6 条种子,采集真实攻略。
4. **小优化**:注入当前日期(system prompt),修复「明天」被当成「今天」的问题。
5. **汇率工具**:`open.er-api.com`(免 key),补全实况查询。

> 踩坑记录见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
