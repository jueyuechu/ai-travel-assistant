# 旅游 AI 助手（Travel AI Assistant）

一个面向中文用户的中文旅游 AI 助手,基于 **Spring AI 2.0 + RAG + Vue 3** 构建。支持多轮对话、结构化行程规划、实时天气/汇率/地图、联网搜索,并内置「搜索即沉淀」的知识库与生产化能力（限流 / 健康检查 / Redis 记忆）。

---
## ✨ 学习/交流
- +q3054756347


## ✨ 功能特性

- **意图路由**：自动识别「规划 / 问答 / 实况」三类意图，路由到不同的工具集，避免工具循环。
- **结构化行程规划**：多轮追问收集需求 → 生成行程 → 路线校验 → 自动修正 → 回填真实天气/经纬度 → 预算提醒，前端渲染成卡片。
- **流式输出**：SSE 流式 + 前端打字机平滑 + 思考动画 + 闪烁光标，输出不生硬。
- **多工具**：天气（和风）、汇率（open.er-api.com）、地图（高德 POI/路线/地理编码）、联网搜索（Tavily）。
- **RAG 知识库**：种子攻略 + 「搜索即沉淀」（联网搜索到的攻略自动向量化入库，两周过期），检索不到时回退到模型自身知识。
- **会话历史**：Redis 持久化（刷新/重启不丢），支持多会话、切换、删除。
- **生产化**：按 IP 限流、Actuator 健康检查/指标、ChatMemory 落 Redis（后端重启记忆不丢）。

---

## 🛠 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 4.1.1 · Spring AI 2.0.1 · Java 21 |
| 对话模型 | DeepSeek（deepseek-v4-flash） |
| 向量化 | 阿里百炼 DashScope（qwen3.7-text-embedding，OpenAI 兼容接口） |
| 向量库 | Redis Stack（RediSearch + 向量检索） |
| 缓存/记忆/会话 | Redis Stack |
| 前端 | Vue 3 · Vite · markdown-it · DOMPurify |
| 地图 | 高德 JS API v2.0 |

---

## 🏗 架构

```
前端 (Vue 3, :3000)
   │  关键词判定意图
   ▼
/ api/chat (SSE 流式)   /api/plan (结构化行程)
   │                          │
   ▼                          ▼
IntentRouter ────────────  TripPlanner
   │ 按意图路由              │ 抽取→生成→校验→修正→回填
   ▼                          │
chatClient (基础/无工具)      ├─ WeatherTool   和风天气
qaChatClient (搜索+地图)      ├─ MapTool       高德 POI/路线
realtimeChatClient (天气+汇率+搜索)  ├─ ExchangeRateTool
   │                          └─ SearchAndIndexTool  Tavily搜索即沉淀
   ▼
Redis Stack（向量库 + 会话历史 + ChatMemory + 缓存 + 限流）
```

**关键设计**：按意图拆分 3 个 `ChatClient`，缩小每个场景的工具集，避免 Spring AI 2.0 无内建迭代上限导致的工具调用死循环（详见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md#13-llm-工具调用无限循环前端一直思考中卡住)）。

---

## 📁 项目结构

```
ai-mcp/
├── backend/ai-test/                    # Spring Boot 后端
│   └── src/main/java/com/itjyc/travel/
│       ├── controller/                 # 对话 / 会话 / 天气接口
│       ├── orchestrator/               # 意图路由 + 规划流水线
│       ├── tool/                       # 天气 / 汇率 / 地图 / 搜索工具
│       ├── config/                     # ChatClient / 限流 / 跨域
│       ├── memory/                     # 会话历史 + Redis 记忆
│       ├── rag/                        # 种子数据初始化
│       └── domain/                     # 结构化模型（行程/需求/意图）
├── frontend/                           # Vue 3 前端
│   └── src/
│       ├── components/                 # ChatMessage / ItineraryCard / MapView
│       ├── lib/                        # markdown 渲染
│       └── App.vue
├── PLAN.md                             # 规划文档
├── TROUBLESHOOTING.md                  # 踩坑记录（21 条）
└── FEATURES.md                         # 前端功能说明
```

---

## 🚀 快速开始

### 前置条件

- **JDK 21**（后端）
- **Node.js 18+**（前端）
- **Redis Stack**（必须带 RediSearch 模块，普通 Redis 不行，向量搜索依赖它）

### 1. 启动 Redis Stack

```bash
docker run -d --name redis-stack -p 6379:6379 \
  -e REDIS_ARGS="--requirepass 1234" redis/redis-stack-server:latest
```

> Windows 本地装不了 RediSearch，建议用 Linux 虚拟机/Docker。详见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md#5-windows-本地-redis-无向量搜索模块)。

### 2. 配置环境变量

后端需要以下环境变量（在 IDE 运行配置或系统环境变量中设置）：

```bash
DEEPSEEK_API_KEY=xxx    # DeepSeek 对话模型
DASHSCOPE_API_KEY=xxx   # 阿里百炼（embedding）
TAVILY_API_KEY=xxx      # 联网搜索
AMAP_API_KEY=xxx        # 高德 Web 服务（后端地理编码/POI/路线）
QWEATHER_API_KEY=xxx    # 和风天气
```

前端在 `frontend/` 下创建 `.env`（可复制 `.env.example`）：

```bash
VITE_AMAP_JS_KEY=xxx      # 高德「Web端(JS API)」类型的 key
VITE_AMAP_JS_SCODE=xxx    # 高德安全密钥 securityJsCode
```

### 3. 修改 Redis 连接

后端 `backend/ai-test/src/main/resources/application.yaml` 里默认指向 `192.168.100.128:6379`（密码 `1234`），改成你自己的 Redis Stack 地址。

### 4. 启动后端

```bash
cd backend/ai-test
export JAVA_HOME=/path/to/jdk21   # 项目要 JDK 21
mvn spring-boot:run
```

默认监听 `http://localhost:8080`。

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:3000`。前端已配置代理，`/api` 请求自动转发到 `:8080`。

---

## 🔑 环境变量一览

| 变量 | 用途 | 申请来源 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 对话模型 | platform.deepseek.com |
| `DASHSCOPE_API_KEY` | 向量化 embedding | 阿里云百炼 bailian.console.aliyun.com |
| `TAVILY_API_KEY` | 联网搜索 | tavily.com |
| `AMAP_API_KEY` | 高德 Web 服务（后端） | console.amap.com |
| `QWEATHER_API_KEY` | 和风天气 | console.qweather.com |
| `VITE_AMAP_JS_KEY` | 高德 JS API（前端地图） | console.amap.com（Web端 JS API 类型） |
| `VITE_AMAP_JS_SCODE` | 高德安全密钥 | 控制台开启「安全密钥」后生成 |

---

## 📡 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | 问答/实况，SSE 流式返回 |
| POST | `/api/plan` | 结构化行程规划（多轮追问） |
| GET/PUT/DELETE | `/api/sessions` | 会话历史管理 |
| GET | `/api/weather` | 天气查询 |
| GET | `/actuator/health` | 健康检查（含 Redis 状态） |
| GET | `/actuator/metrics` | 指标 |

---

## 📚 文档

- [PLAN.md](PLAN.md) — 整体规划与设计思路
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — 21 条踩坑记录（Spring AI 2.0 / Jackson 3 / 高德 / 和风 / MCP 等）
- [FEATURES.md](FEATURES.md) — 前端功能与交互说明

---

## 📝 说明

- 本项目为学习/演示用途，鉴权暂未实现（可自行扩展 JWT/API Key）。
- 生产化基础已就绪（限流 + 健康检查 + Redis 记忆），Prometheus/Grafana 等可自行接 `micrometer-registry-prometheus`。
