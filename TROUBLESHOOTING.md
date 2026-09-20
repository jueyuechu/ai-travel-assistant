# 踩坑记录 — Spring AI 2.0 + MCP + RAG 旅游助手

> 记录本项目建设过程中遇到的问题、根因、解决方案和可复用的经验。
> 环境:Spring Boot 4.1.1 + Spring AI 2.0.1 + Java 21 + Windows 开发机 + Linux 虚拟机(Redis Stack)

---

## 1. DashScope starter 启动报错(依赖 milestone 的坑)

**现象**:应用启动即失败
```
Unable to read meta-data for class DashScopeMultimodalEmbeddingAutoConfiguration
Caused by: FileNotFoundException: .../DashScopeMultimodalEmbeddingAutoConfiguration.class does not exist
```

**根因**:`com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope:2.0.0-M1.1` 是 **milestone 版本**,其 `AutoConfiguration.imports` 文件引用了一个 jar 里根本不存在的类(模块拆分遗留问题)。

**解决**:放弃 dashscope starter,复用项目已有的 `spring-ai-starter-model-openai`,走阿里百炼的 **OpenAI 兼容端点**:

```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY}          # 用百炼的 key
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      embedding:
        options:
          model: qwen3.7-text-embedding
```

**经验**:
- milestone/RC 依赖有风险,能用 GA 就用 GA;引入前先查它的版本对齐。
- 阿里百炼(DashScope)支持 OpenAI 兼容接口,不必依赖专用 starter。

---

## 2. Windows 上 npx 找不到(MCP stdio)

**现象**:
```
Cannot run program "npx": CreateProcess error=2, 系统找不到指定的文件
```

**根因**:Windows 上 npm 提供的是 `npx.cmd`(不是 `npx.exe`),Java `ProcessBuilder` 直接执行 `npx` 找不到。

**解决**:用 `cmd /c` 包装:
```yaml
command: cmd
args: ["/c", "npx", "-y", "tavily-mcp@latest"]
```

**经验**:Windows 上 MCP stdio 命令(`npx`/`node` 等)都要 `cmd /c` 包装;Linux/macOS 不需要。

---

## 3. MCP 客户端初始化超时(首次下载慢)

**现象**:
```
Client failed to initialize by explicit API call
Caused by: TimeoutException: Did not observe any item ... within 20000ms
```

**根因**:`npx` 首次运行要**下载包**,超过 MCP 客户端默认 20s 初始化超时。注意日志里 server 其实已启动(`Tavily MCP server running on stdio`),只是慢了一步。

**解决**:
1. 加大超时:`spring.ai.mcp.client.request-timeout: 120s`
2. 先手动预缓存:`npx -y tavily-mcp@latest`(跑起来后 Ctrl+C,包已缓存)

**经验**:MCP server 首次启动慢(下载/编译),给足超时 + 预缓存;国内可配 npm 镜像 `npm config set registry https://registry.npmmirror.com`。

---

## 4. Redis 向量库需要 Jedis(不是 Lettuce)

**现象**:
```
required a bean of type 'JedisConnectionFactory' that could not be found
```

**根因**:Spring AI 的 `RedisVectorStore` 底层用 **Jedis**(`JedisPooled`),但 Spring Boot 默认配 **Lettuce**。

**解决**:加 Jedis 依赖 + 显式切换:
```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
</dependency>
```
```yaml
spring:
  data:
    redis:
      client-type: jedis
```

**经验**:Spring AI 的 Redis 向量库用 Jedis,不是 Lettuce;记得加 jedis 依赖并切换 client-type。

---

## 5. Windows 本地 Redis 无向量搜索模块

**现象**:
```
ERR unknown command 'FT._LIST'
```

**根因**:向量搜索依赖 **RediSearch 模块**,而 RediSearch **没有 Windows 版**。Windows 本地装的 Redis(即使是 8.x)用不了 `FT.*` 命令。

**解决**:Linux 虚拟机 + Docker 跑 Redis Stack(自带 RediSearch):
```bash
docker run -d --name redis-stack -p 6379:6379 \
  -e REDIS_ARGS="--requirepass 1234" redis/redis-stack-server:latest
```

**经验**:
- 向量搜索必须 **Redis Stack**(或手动加载 RediSearch),Windows 本地不行。
- 用 `redis-stack-server`(纯服务版),别用 `redis-stack`(带 Web UI,大很多)。
- 验证:`redis-cli MODULE LIST` 里看到 `search` 模块才 OK。

---

## 6. Docker 端口被占用

**现象**:
```
Error starting userland proxy: listen tcp4 0.0.0.0:6379: bind: address already in use
```

**根因**:虚拟机里已有旧的 Redis 服务占用 6379。

**解决**:先查占用再处理:
```bash
ss -tlnp | grep 6379          # 查占用者
systemctl stop redis          # 停旧服务(服务名以实际为准)
docker rm -f redis-stack && docker run ...   # 重新起容器
```
或换端口映射 `-p 6380:6379`。

**经验**:起容器前先 `ss -tlnp` 查端口占用。

---

## 7. 两个 ChatModel 冲突(DeepSeek + OpenAI)

**现象**:
```
expected single matching bean but found 2: deepSeekChatModel, openAiChatModel
```

**根因**:为了 embedding 复用了 openai starter,它**顺带创建了 OpenAI 对话模型**,和 DeepSeek 对话模型冲突,ChatClient 不知道该用哪个。

**解决**:排除 OpenAI 的对话自动配置(注意 `chat.enabled=false` **不生效**,必须 exclude):
```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
```

**经验**:复用 openai starter 只做 embedding 时,要排除它的 chat 自动配置;`chat.enabled=false` 在 Spring AI 2.0 里可能不生效。

---

## 8. Jackson 3 的 JsonNode 抽象类型无法反序列化

**现象**(工具调用时报错):
```
Type definition error: [simple type, class com.fasterxml.jackson.databind.JsonNode]
Cannot construct instance of com.fasterxml.jackson.databind.JsonNode (abstract types...)
```

**根因**:**Spring Boot 4 全面拥抱 Jackson 3**,核心包从 `com.fasterxml.jackson` 迁到 `tools.jackson`。代码里 `import com.fasterxml.jackson.databind.JsonNode`(Jackson 2 的抽象类),而 RestClient 用的是 Jackson 3 的 converter,反序列化不了 Jackson 2 的类型。

**解决**:改 import 到 Jackson 3 的包:
```java
import tools.jackson.databind.JsonNode;   // 不是 com.fasterxml.jackson.databind.JsonNode
```

**经验**:
- Spring Boot 4 默认 Jackson 3,包名 `com.fasterxml` → `tools.jackson`(注解 `@JsonProperty` 等仍在 `com.fasterxml.jackson.annotation`,别全局替换)。
- 接收 JSON 用 Jackson 3 的 `tools.jackson.databind.JsonNode`,或干脆用 `Map<String,Object>` / `String` 最稳。

---

## 9. 和风天气 Invalid Host(架构变更)

**现象**:
```json
{"error":{"status":403,"title":"Invalid Host","detail":"An invalid or unauthorized API Host."}}
```

**根因**:和风 2026 年改了架构,**每个开发者有专属 API Host**(形如 `xxx.re.qweatherapi.com`),旧的公共 Host(`devapi.qweather.com`/`api.qweather.com`)失效。

**解决**:登录控制台 `console.qweather.com/setting?lang=zh` 查专属 Host,配置到代码:
```yaml
qweather:
  api-key: ${QWEATHER_API_KEY}
  api-host: https://na6fr9wd4y.re.qweatherapi.com   # 你的专属 Host
```

**经验**:
- 和风已改为「专属 Host」,不再用统一公共 Host。
- API Key 目前还能用,官方计划 2027 年迁到 JWT(Ed25519 签名),后续可能要适配。

---

## 10. 和风 city lookup 空响应(改用高德经纬度)

**现象**:`v2/city/lookup` 接口返回空(两个 Host 都空),城市查询不稳定。

**根因**:和风架构变更后,城市查询接口行为异常/废弃。

**解决**:绕开 city lookup,改用**高德地理编码拿经纬度**,和风按经纬度查天气(和风的 `location` 参数支持 `lng,lat`):
```
城市名 → 高德 /geocode/geo 拿经纬度 → 和风 /v7/weather/3d?location=116.41,39.92
```

**经验**:
- 和风天气 `location` 支持经纬度,可以绕开不稳定的城市查询。
- 工具之间可以互相补位(高德地理编码 + 和风天气),比死磕单一接口强。

---

## 11. Jackson 3 基本类型 null 报错(结构化输出)

**现象**:LLM 结构化输出到 record 时,反序列化报 `MismatchedInputException: Cannot map null into type int`。

**根因**:Jackson 3 **无法将 null 反序列化到基本类型**(`int`/`double`)。LLM 生成的 JSON 偶发漏字段,基本类型字段就炸。

**解决**:结构化输出的 record 里,**数值字段用包装类型**(`Integer`/`Double`),日期用 `String`:
```java
public record Itinerary(
    String destination,
    List<DayPlan> days,
    Double totalCost,      // 不是 double
    List<String> alerts
) {}
```

**经验**:给 LLM 结构化输出用的 record,数值字段一律包装类型,给 null 留余地,避免偶发漏字段导致整体失败。

---

## 12. 前端 markdown 渲染乱 + 温度横线 + XSS 风险 + 流式卡顿

**现象**:AI 回复的 markdown(天气表格、行程)显示乱;温度区间 `20~30℃` 的 `~` 被误判为删除线,文字被横线覆盖;`v-html` 直接注入 LLM 输出无消毒;流式输出长文本时逐 chunk 全文重解析导致卡顿。

**根因**(4 个叠加):
1. `marked` 对表格/删除线处理不理想,之前用 `renderer.del` 覆盖只是"打补丁"。
2. LLM 输出是不可信输入(可被提示注入构造 `[x](javascript:...)`),但前端没消毒。
3. 每来一个 SSE chunk 就全文 `marked.parse` + 重新赋值,长文本越滚越慢。
4. 前端 CSS 没针对 markdown 表格优化。

**解决**:换 `markdown-it` + `DOMPurify`,并加流式节流。

```js
// ChatMessage.vue 核心
import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';

const md = new MarkdownIt({ html: false, linkify: true, breaks: false });
md.renderer.rules.s_open = () => '';   // 删除线开标签清空
md.renderer.rules.s_close = () => '';  // 删除线闭标签清空

const html = computed(() =>
    props.role === 'assistant'
        ? DOMPurify.sanitize(md.render(props.content || ''))
        : ''
);
```

```js
// App.vue:rAF 节流,合并同一帧内多次 SSE 更新
const scheduleRender = () => {
    if (rafId == null) rafId = requestAnimationFrame(flush);
};
// 流结束取消未执行的帧,立即渲染最终完整内容
```

**经验**:
- markdown 渲染标准管道:`parse → DOMPurify.sanitize → v-html`,LLM 输出必须当不可信输入。
- markdown-it 关删除线:覆盖 `s_open`/`s_close` 为空比 `disable('strikethrough')` 干净——后者会残留字面 `~~`。
- 流式用 `requestAnimationFrame` 节流,别每 chunk 全文重渲染。
- **选型结论**:轻量场景(无公式/图表)选 markdown-it + DOMPurify;`markdown-it-vue3`(带 mermaid/katex)和 `md-editor-v3`(本质编辑器)体积大、用不上;后端结构化 JSON 是终极方案但工作量大,暂不做。

---

## 13. LLM 工具调用无限循环(前端一直"思考中"/卡住)

**现象**:一次请求里模型反复调工具不收敛——日志里 `getWeather` 重复 2 次、`searchPoi` 连调 5 次、`getRoute` 1 次,持续数秒仍不产出最终回答。日志特征:`Executing tool call` / `Successful execution` 反复刷,间隔几百 ms 到几 s。

**根因**(3 个叠加):
1. **Spring AI 2.0 工具循环无内建迭代上限**(最关键):2.0 移除了 `internalToolExecutionEnabled`,工具执行外置到 `ToolCallingAdvisor`;它是递归 advisor,**唯一停止条件是"模型不再请求工具"**,没有 `maxIterations`/`toolCallBudget` 之类配置。模型一直决定"再查一次"就无限空转。
2. **deepseek-v4-flash 收敛性差**:轻量模型 tool-use 时"该停了"的判断弱。
3. **工具全挂**:全局 ChatClient 把 mapTool + weatherTool + mcpTools(Tavily)全挂,每个意图模型手里都有 3 类工具,反复横跳。

**解决**:按意图拆分 ChatClient,缩小每个场景的工具集。

```java
// ChatClientConfig.java:拆 3 个 bean
@Bean ChatClient chatClient(...)          // 基础:无工具(行程呈现、规划结构化输出)
@Bean ChatClient realtimeChatClient(...)  // 天气 + 搜索(实况)
@Bean ChatClient qaChatClient(...)        // 搜索 + 地图(问答)
```

```java
// TravelAssistantController.java:按意图路由
ChatClient client = intent == IntentType.QA ? qaChatClient : realtimeChatClient;
```

**关键认知**:
- **PLAN 场景的 LLM 根本不调工具**:`generate/revise` 用 `.entity(Itinerary.class)` 结构化输出,而结构化输出与工具调用互斥,所以循环发生在流式 normalChat(QA/REALTIME),不在规划。之前 PLANNER_SYSTEM 里"可调用天气/地图工具"是误导,已删。
- 连带修复:拆 3 个 bean 后,`WeatherController` 里无 `@Qualifier` 的 `ChatClient` 参数会注入歧义,需补 `@Qualifier("chatClient")`。

**经验**:
- Spring AI 2.0 工具循环**没有内建上限**,防循环靠:① 缩小工具集(治本) ② 若不够,自己写带计数上限的循环(GitHub issue #3333 的 while+count 模式)。
- 想让 LLM 调工具就别用 `.entity()` 结构化输出,两者互斥。
- 轻量模型做 agentic tool-use 收敛差,必要时换更强模型(deepseek-chat)。

---

## 14. 命令行 mvn 编译失败:JDK 版本不符

**现象**:命令行 `mvn compile` 报 `不支持发行版本 21`(中文环境可能乱码 `��: ��֧�ַ��а汾 21`)。

**根因**:项目要 Java 21,但命令行 `java -version` 是 **JDK 17**(`JAVA_HOME=D:\jdk17.1\jdk-17.0.11`)。IDEA 里配置的是 JDK 21,所以 IDEA 编译正常。

**解决**:命令行编译前切到 JDK 21(本机 JDK 21 在 `D:\1develop\jdk21`):
```bash
export JAVA_HOME="/d/1develop/jdk21" && export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -DskipTests compile
```
或直接在 IDEA 里编译。

**经验**:命令行 JDK(17)和项目 JDK(21)不一致时,`mvn compile` 报"不支持发行版本"不是代码错——`export JAVA_HOME` 指向 JDK 21 即可命令行编译,不必非得 IDEA。

---

## 15. 前端 SSE 解析丢换行 → markdown 表格渲染失败

**现象**:AI 回复里的 markdown 表格(天气、行程)挤成一行,`markdown-it`/`marked` 识别不了表格,原样显示 `|日期|天气|...|---|---|...`。

**根因**:Spring WebFlux 把多行内容编码成多个 `data:` 行(每行一个 `data:` 前缀)。前端手动解析 SSE 时逐行 `fullText += line.slice(5)` 拼接,但**没在行之间加 `\n`**,导致换行全部丢失。

**解决**:SSE 事件内的多个 `data:` 行用 `\n` 连接:

```js
const data = event
    .split('\n')
    .filter((l) => l.startsWith('data:'))
    .map((l) => l.slice(5).replace(/^ /, ''))
    .join('\n');   // 关键:行间用 \n 连接,保留 markdown 换行
```

**经验**:
- 手写 SSE 解析时,一个事件的多个 `data:` 行要用 `\n` 连接,不能直接 `+=` 拼接。
- 这个 bug 是之前"天气表格一直乱"的**真正根因**——不是渲染引擎(先换 markdown-it 也没用),是换行在传输/解析层就丢了。
- **排查「显示乱」的通用方法**:先分清是「内容丢了」还是「换行丢了」——内容完整但挤成一行 → 传输/解析层丢换行(查 SSE 解析);内容残缺/结构错乱 → 才考虑渲染引擎或 LLM 输出问题。前者换渲染引擎是白费功夫。

---

## 16. RAG 检索不到相关内容时直接拒绝回答

**现象**:问知识库里没有的目的地(如「湘潭有什么景点」),LLM 直接说「我掌握的资料是关于北京天坛的,里面没有湘潭的信息,没法回答」。

**根因**:Spring AI 的 `QuestionAnswerAdvisor` 默认 prompt 模板结尾强制「答案不在上下文里就告诉用户你无法回答」("...If the answer is not in the context, inform the user that you can't answer the question"),且要求 "not prior knowledge"(不用先验知识)。知识库只有几条种子数据,检索不到湘潭时,LLM 就忠实执行「无法回答」,而不是用自身知识 + 工具(地图搜湘潭景点、查天气)回答。

**解决**:不用 `QuestionAnswerAdvisor`,改成**手动 RAG**——检索到相关内容就拼进 prompt(标注"仅供参考,无关请忽略"),检索不到就不注入,LLM 自然用自身知识 + 工具回答:

```java
List<Document> docs = vectorStore.similaritySearch(
    SearchRequest.builder().query(query).topK(3).similarityThreshold(0.5).build());
// 拼到 system prompt；若无结果则 context 为空，不注入
```

**经验**:
- `QuestionAnswerAdvisor` 默认模板会「强制拒绝无答案」,做面向 C 端助手时要自定义模板或改手动 RAG 控制 fallback。
- **RAG 是「增强」不是「限制」**:检索结果仅供参考,检索不到应 fallback 到 LLM 自身知识 + 工具,而不是拒绝。
- `similarityThreshold(0.5)` 是经验值(cosine 相似度,越高越相关),按实际检索效果调:相关也检索不到就调低,无关也检索到就调高。

---

## 17. 流式输出不彻底(前端打字机平滑 + 光标)

**现象**:问答/实况的流式输出「一开始几个字是流式,然后卡住,又一下子输出一大片」,体验生硬。

**根因**:后端输出是**突发**的——LLM 生成/工具调用期间会暂停(SSE 无数据),工具返回后一次性吐一大段。前端 rAF 节流如实反映了这个突发。

**解决**:前端不逐段直出,改用「打字机」平滑:
- rAF 逐字推进显示游标,把突发的一大片拆成连续逐字;
- 积压多时自动加速(backlog>200 字时每帧跳 10 字),不拖慢;
- 流未结束时末尾显示闪烁光标,缓冲耗尽(后端暂停)时光标继续闪,表示「还在生成」,不生硬。

**经验**:
- LLM 流式输出不是匀速的,别指望后端平滑;前端用「打字机 + 动态步长 + 光标」平滑,比直接渲染体验好。
- 结构化输出(行程卡片)不适合逐字流式,用「思考动画」兜底等待期即可。

---

## 18. 高德 JS API 地图空白(key 误填成安全密钥)

**现象**:前端高德地图页面空白(容器有、但无底图瓦片),F12 控制台可能报 `USERKEY_PLAT_NOMATCH`(key 类型不对)或 `INVALID_USER_KEY`。

**根因**:高德 JS API 有两个容易混的东西——**JS key** 和 **安全密钥(securityJsCode)**。把「安全密钥」当成 JS key 填进 SDK 脚本,地图就空白。另外 key 必须配「Web端(JS API)」类型,后端用的 Web 服务 key 不能用于前端地图 SDK。

**解决**:JS key 和 安全密钥分开填:
- JS key(如 `77c75a...`)→ SDK 脚本的 `key` 参数 + `.env` 的 `VITE_AMAP_JS_KEY`;
- 安全密钥(如 `2903989...`)→ `_AMapSecurityConfig.securityJsCode` + `.env` 的 `VITE_AMAP_JS_SCODE`。

**经验**:
- **安全域名白名单不是本地跑通的前置条件**——不填白名单地图也能正常显示(之前误判「空白=安全域名没配」,真因是 key 填成了安全密钥)。白名单是「上线公网后的防护」,不是「本地能跑」的必要项。
- 前端 JS key 藏不住,安全靠「安全域名白名单」而非隐藏 key。
- 地图初始化要 try-catch 包起来;返回按钮等 UI 要给 `z-index`,防被地图覆盖层挡住。

---

## 19. TripPlanner 规划流水线的 3 个隐患(共用记忆 / 抽取无降级 / revise 用 toString)

**现象/隐患**(review `TripPlanner.java` 发现):
1. 规划的 3 个内部 LLM 调用(抽取需求/生成行程/校验后修正)都拿 `conversationId` 做 ChatMemory 的 memoryId,把抽取的 JSON、行程草稿 JSON 写进主会话记忆。规划完再问「明天天气」,上下文混进一坨内部 JSON。
2. `extractRequest` 用 `.entity(TripRequest.class)` 反序列化,LLM 偶发返回非法 JSON 就抛异常 → 整个规划 500。
3. `revise` 把行程 record 直接 `toString()`(`Itinerary[destination=..., days=[...]]`)喂给 LLM,不是干净 JSON,模型易误解。
4. (附带)追问字段名是英文(`destination、days、budget`),前端直接显示英文。

**解决**:
1. 隔离记忆:extract 用独立 memoryId `conversationId + ":extract"`;generate/revise 不带记忆(它们只吃当前输入)。
2. extract 包 try-catch 返回 null,`planInteractive` 里 null 时兜底追问,不再 500。
3. revise 用 ObjectMapper 把行程/问题序列化成干净 JSON 再喂。
4. `missingRequiredFields()` 返回中文(目的地/天数/预算)。

**经验**:
- 编排流水线里的多个内部 LLM 调用,**别共用**主会话 ChatMemory——要么独立 memoryId,要么不带记忆,否则内部中间态污染用户可见上下文。
- 结构化输出(`.entity()`)遇到非法 JSON 不会自动降级,要自己 try-catch 兜底。

---

## 20. Spring AI 2.0 ChatMemory 换 Redis 的两个坑

**现象**:想给 ChatMemory 换 Redis 实现时,照旧版 API 写 `get(conversationId, lastN)` 编不过。

**根因**:Spring AI 2.0 改了接口 + 存储抽象:
1. `ChatMemory.get(String)` **不再带 `lastN`** 参数(旧版是 `get(conversationId, lastN)`)。
2. `MessageWindowChatMemory` 内部改用 `ChatMemoryRepository`(存储抽象),窗口滑动逻辑在它自己(`process`),不暴露 lastN。
3. `AbstractMessage` **没有** `@JsonTypeInfo`/`@JsonSubTypes` 注解,Message 不能直接用 Jackson 多态反序列化。

**解决**:
1. 不实现 `ChatMemory`,改实现 `ChatMemoryRepository`(4 个方法:findConversationIds / findByConversationId / saveAll / deleteByConversationId),窗口滑动由 `MessageWindowChatMemory` 自动复用。
2. 序列化自己定义格式:只存 `messageType + text`,反序列化按 type 还原(`USER→UserMessage` / `ASSISTANT→AssistantMessage` / `SYSTEM→SystemMessage`,TOOL 跳过)。

```java
@Bean
ChatMemory chatMemory(RedisChatMemoryRepository repo) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(repo)   // 注入 Redis 存储,窗口逻辑自动复用
        .maxMessages(20)
        .build();
}
```

**经验**:
- 升级 Spring AI 大版本先 `javap` 接口签名再照旧文档写,别想当然(2.0 的 `get(lastN)` 就没了)。
- 换存储不必重写窗口逻辑——Spring AI 已把存储抽象成 `ChatMemoryRepository`,只实现存取即可。
- Message 没有现成多态序列化,自己存 `type + text` 最省事;TOOL 消息(工具返回)不需要记忆,跳过。

---

## 21. MCP 的 sampling/elicitation WARN + 何时该放弃 MCP

**现象**:启动日志刷两条 WARN:
```
SyncMcpSamplingProvider : No sampling methods found
SyncMcpElicitationProvider : No elicitation methods found
```

**根因**:Spring AI 启动时探测 MCP server 的 sampling(服务端请求 LLM 采样)和 elicitation(服务端反向提问)能力,Tavily MCP 只提供 tool 调用、不支持这两个,所以打印 "No methods found" 跳过——**无害**。

**解决/取舍**:做「搜索即沉淀」时决定放弃 MCP Tavily,自己写 `SearchAndIndexTool` 直接 HTTP 调 Tavily:
- MCP stdio 要起 `npx` 子进程、走 stdio 通信,比自己直接 HTTP 慢且多一层;
- 搜索 + 向量化沉淀要内聚在一个工具里,自己写最顺;
- Tavily 本质就是个搜索 API,HTTP 直调没有功能损失。
移除 MCP(pom 依赖 + 配置 + 工具引用)后,那两条 WARN 也一并消失。

**经验**:
- 这两条 WARN 无害,是 Spring AI 对 MCP 能力的固定探测日志,不影响工具调用。
- MCP 的价值在「复杂/多工具/有状态」的 server;对「一个搜索 API」这种简单场景,直接 HTTP 更简单高效。别为了用 MCP 而用 MCP。

---

## 22. 去掉 .advisors() ≠ 不带记忆(conversationId 必填报错)

**现象**:规划接口报 `java.lang.IllegalArgumentException: conversationId cannot be null`,堆栈在 `TripPlanner.generate()` 的 `.entity()` → `MessageChatMemoryAdvisor.before()`。

**根因**:`chatClient` bean 的 `defaultAdvisors` 挂了 `MessageChatMemoryAdvisor`,它**强制 conversationId 非 null**(`Assert.notNull`)。之前「问题1修复」给 generate/revise 去掉了 `.advisors(a -> a.param(CONVERSATION_ID, ...))`,以为这样就不带记忆了——但 `defaultAdvisors` 还在,调用时拿不到 conversationId 就抛异常。

**解决**:给规划流水线单独建一个**不挂记忆的 ChatClient**(`planningChatClient = builder.build()`,无 `defaultAdvisors`),抽取/生成/修正全用它;主会话记忆仍由 `chatClient` 承担。

**经验**:
- `.advisors(...)` 只能**追加** advisor,不能移除 `defaultAdvisors`;想「不带记忆」必须用**不挂 `MessageChatMemoryAdvisor` 的独立 ChatClient**。
- 去掉显式 advisor ≠ 去掉 defaultAdvisors,两者是叠加关系。

---

## 通用经验总结

1. **Spring Boot 4 的两大坑**:Jackson 3(包名变了)+ 基本类型 null 报错,写数据接收/结构化输出时先想到这两点。
2. **Windows 开发的坑**:npx 要 `cmd /c`;Redis 向量搜索只能靠虚拟机/Docker 的 Redis Stack。
3. **第三方 API 会变**:和风 2026 改专属 Host + 推 JWT,接入前先 curl 验证接口通不通,别照搬旧文档。
4. **milestone 依赖慎用**:能用 GA 就用 GA;阿里百炼可用 OpenAI 兼容接口绕开专用 starter。
5. **调试方法论**:逐层排查(依赖 → 配置 → 接口 → 序列化),用 curl 直接验证第三方 API,用日志级别放大工具调用链路。
6. **前端渲染 LLM 输出**:markdown 走 `parse → DOMPurify.sanitize → v-html`,LLM 输出当不可信输入;流式用打字机平滑(rAF 逐字 + 动态步长);关删除线覆盖 markdown-it 的 `s_open`/`s_close`。
7. **Spring AI 2.0 工具调用**:无内建迭代上限,靠按意图缩小工具集防循环;`.entity()` 结构化输出与工具调用互斥(规划 LLM 不调工具,路线校验 Java 直调)。
8. **手写 SSE 解析**:一个事件的多个 `data:` 行要用 `\n` 连接,别直接 `+=` 拼接(否则 markdown 换行丢失,表格失效)。
9. **RAG 是增强不是限制**:检索结果仅供参考,检索不到就 fallback 到 LLM 自身知识 + 工具,别用会强制拒绝的默认模板。
10. **LLM 流式输出不匀速**:后端突发(工具调用/模型暂停)是常态,前端用打字机 + 闪烁光标平滑,别指望后端匀速吐字。
