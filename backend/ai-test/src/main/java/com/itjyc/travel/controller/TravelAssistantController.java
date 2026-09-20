package com.itjyc.travel.controller;

import com.itjyc.travel.domain.IntentType;
import com.itjyc.travel.domain.Itinerary;
import com.itjyc.travel.orchestrator.IntentRouter;
import com.itjyc.travel.orchestrator.TripPlanner;
import com.itjyc.travel.util.DateUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 旅游助手对话入口。
 * - /chat：SSE 流式（问答/实况），按意图路由到不同工具集的 ChatClient；问答手动挂 RAG。
 * - /plan：规划，返回结构化行程（前端渲染卡片），支持多轮追问。
 */
@RestController
@RequestMapping("/api")
public class TravelAssistantController {

    private static final String NORMAL_SYSTEM = """
            你是一个专业的旅游 AI 助手。根据需要使用地图、天气、联网搜索等工具获取实时信息。
            回答用中文，简洁友好。工具返回的表格数据（如天气）请直接原样展示，不要重新排版或转述。
            查询天气请用天气工具而非联网搜索。每次查询只调用必要的工具，拿到结果后立即组织回答，不要反复调用同一个工具。调用工具前，先一句话说明要查什么（如「我来查一下天气」），再调用。
            如果知识库没有相关资料，或检索到的资料与问题不相关，直接用你自己的知识和工具回答，不要因为缺少资料而拒绝回答。
            """;

    /** 搜索沉淀文档的过期时间：两周。种子/无 indexedAt 的文档永久保留。 */
    private static final long DOC_TTL_MS = 14L * 24 * 3600 * 1000;

    private final ChatClient chatClient;            // 基础（无工具）：行程建议/呈现
    private final ChatClient qaChatClient;          // 问答：联网搜索 + 地图
    private final ChatClient realtimeChatClient;    // 实况：天气 + 联网搜索
    private final IntentRouter intentRouter;
    private final TripPlanner tripPlanner;
    private final VectorStore vectorStore;

    public TravelAssistantController(@Qualifier("chatClient") ChatClient chatClient,
                                     @Qualifier("qaChatClient") ChatClient qaChatClient,
                                     @Qualifier("realtimeChatClient") ChatClient realtimeChatClient,
                                     IntentRouter intentRouter,
                                     TripPlanner tripPlanner,
                                     VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.qaChatClient = qaChatClient;
        this.realtimeChatClient = realtimeChatClient;
        this.intentRouter = intentRouter;
        this.tripPlanner = tripPlanner;
        this.vectorStore = vectorStore;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        String convId = request.conversationId() == null ? "default" : request.conversationId();
        IntentType intent = intentRouter.route(request.message());
        if (intent == IntentType.PLAN) {
            return Flux.just(planTrip(request.message(), convId));
        }
        ChatClient client = intent == IntentType.QA ? qaChatClient : realtimeChatClient;
        return normalChat(client, request.message(), convId, intent == IntentType.QA);
    }

    /** 规划：结构化返回，complete=false 追问，true 返回行程 + 出行建议。 */
    @PostMapping("/plan")
    public PlanResponse plan(@RequestBody ChatRequest request) {
        String convId = request.conversationId() == null ? "default" : request.conversationId();
        TripPlanner.PlanResult result = tripPlanner.planInteractive(request.message(), convId);
        if (!result.complete()) {
            return new PlanResponse(false, result.missing(), null, null);
        }
        String advice = generateAdvice(result.itinerary(), convId);
        return new PlanResponse(true, null, result.itinerary(), advice);
    }

    /** 问答类挂 RAG（查攻略）；实况查询不挂 RAG（直接调工具，更快）。 */
    private Flux<String> normalChat(ChatClient client, String message, String convId, boolean withRag) {
        var spec = client.prompt().system(NORMAL_SYSTEM + "\n今天是 " + DateUtil.today() + "。").user(message);
        if (withRag) {
            String context = retrieveContext(message);
            if (!context.isBlank()) {
                spec = spec.system("以下是知识库检索到的参考资料，仅供参考，若与问题无关请忽略：\n" + context);
            }
        }
        return spec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId)).stream().content();
    }

    /** 从向量库检索相关攻略片段；检索不到或低于阈值时返回空串（前端不注入，LLM 用自身知识）。 */
    private String retrieveContext(String query) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .similarityThreshold(0.5)
                        .build());
        long cutoff = System.currentTimeMillis() - DOC_TTL_MS;
        return docs.stream()
                .filter(doc -> isFresh(doc, cutoff))
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
    }

    /** 搜索沉淀的文档两周过期；种子/无 indexedAt 的文档永久保留。 */
    private boolean isFresh(Document doc, long cutoff) {
        Object ts = doc.getMetadata() == null ? null : doc.getMetadata().get("indexedAt");
        if (ts == null) return true;
        try {
            return Long.parseLong(ts.toString()) >= cutoff;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** 规划兜底（/chat 的 PLAN 分支）：返回 markdown 文本。前端规划场景优先走 /plan。 */
    private String planTrip(String message, String convId) {
        TripPlanner.PlanResult result = tripPlanner.planInteractive(message, convId);
        if (!result.complete()) {
            return result.missing();
        }
        return chatClient.prompt()
                .system("把这份行程用清晰友好的中文呈现给用户，可适当补充出行建议。必须用 markdown 文本，禁止输出 JSON 或代码块。\n今天是 " + DateUtil.today() + "。")
                .user(result.itinerary().toString())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .call()
                .content();
    }

    /** 基于行程生成出行建议（穿衣/预约/交通/预算提醒）。 */
    private String generateAdvice(Itinerary itinerary, String convId) {
        return chatClient.prompt()
                .system("你是旅游出行顾问。基于下面的行程摘要，给出 3-5 条简洁实用的出行建议（穿衣、预约、交通、预算提醒等），用中文直接输出要点。禁止输出 JSON，禁止复述行程数据。\n今天是 " + DateUtil.today() + "。")
                .user(summarize(itinerary))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .call()
                .content();
    }

    /** 行程友好摘要（给建议生成用，避免直接喂 JSON 导致 LLM 复述）。 */
    private String summarize(Itinerary it) {
        StringBuilder sb = new StringBuilder("目的地：").append(it.destination());
        sb.append("，").append(it.days() == null ? 0 : it.days().size()).append(" 天");
        if (it.totalCost() != null) sb.append("，总花费约 ").append(it.totalCost()).append(" 元");
        sb.append("。\n");
        for (Itinerary.DayPlan d : it.days()) {
            sb.append("Day ").append(d.day()).append("：");
            List<String> names = d.stops() == null ? List.of()
                    : d.stops().stream().map(Itinerary.Stop::name).collect(Collectors.toList());
            sb.append(String.join("、", names)).append("。\n");
        }
        return sb.toString();
    }

    public record ChatRequest(String message, String conversationId) {}

    /** 规划接口响应：complete=false 时返回追问；true 时返回结构化行程 + 出行建议。 */
    public record PlanResponse(boolean complete, String missing, Itinerary itinerary, String advice) {}
}
