package com.itjyc.travel.tool;

import tools.jackson.databind.JsonNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 搜索即沉淀：联网搜索旅游攻略，结果自动向量化写入 Redis 知识库（两周过期）。
 * 替代原先 MCP Tavily 接入——直接 HTTP 调 Tavily，把「搜索 + 沉淀」内聚在一个工具里。
 */
@Component
public class SearchAndIndexTool {

    private final RestClient restClient;
    private final String apiKey;
    private final VectorStore vectorStore;

    public SearchAndIndexTool(@Value("${tavily.api-key}") String apiKey, VectorStore vectorStore) {
        this.apiKey = apiKey;
        this.vectorStore = vectorStore;
        this.restClient = RestClient.builder().baseUrl("https://api.tavily.com").build();
    }

    @Tool(description = "联网搜索旅游攻略、景点、门票、交通等实时资讯，并把结果自动沉淀到知识库供后续查询")
    public String searchWeb(@ToolParam(description = "搜索关键词，如 北京故宫门票预约") String query) {
        try {
            JsonNode body = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "api_key", apiKey,
                            "query", query == null ? "" : query,
                            "search_depth", "basic",
                            "max_results", 5,
                            "include_answer", true))
                    .retrieve().body(JsonNode.class);

            String answer = body == null ? "" : body.path("answer").asText("");
            JsonNode results = body == null ? null : body.path("results");
            String indexedAt = String.valueOf(System.currentTimeMillis());

            List<Document> docs = new ArrayList<>();
            StringBuilder reply = new StringBuilder();
            if (!answer.isBlank()) {
                reply.append(answer).append("\n\n");
            }
            if (results != null && results.isArray()) {
                for (JsonNode r : results) {
                    String title = r.path("title").asText("");
                    String content = r.path("content").asText("");
                    String url = r.path("url").asText("");
                    if (content.isBlank()) continue;
                    docs.add(new Document(
                            title + "：" + content,
                            Map.of("source", "tavily", "indexedAt", indexedAt,
                                    "query", query == null ? "" : query,
                                    "title", title, "url", url)));
                    reply.append("【").append(title).append("】").append(content);
                    if (!url.isBlank()) reply.append("（").append(url).append("）");
                    reply.append("\n");
                }
            }

            // 异步沉淀，不阻塞搜索响应；沉淀失败不影响本次回答
            if (!docs.isEmpty()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        vectorStore.add(docs);
                    } catch (Exception ignored) {
                    }
                });
            }

            String text = reply.toString().trim();
            return text.isBlank() ? "搜索无结果" : text;
        } catch (Exception e) {
            return "搜索失败：" + e.getMessage();
        }
    }
}
