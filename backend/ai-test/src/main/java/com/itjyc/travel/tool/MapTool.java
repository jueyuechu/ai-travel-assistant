package com.itjyc.travel.tool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tools.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 高德地图工具（官方 Web 服务 API v3）。
 */
@Component
public class MapTool {

    private final RestClient restClient;
    private final String apiKey;

    /**
     * 地点名 → "lng,lat" 缓存，供 resolveLocation 复用，避免对同一景点重复解析坐标、规避高德 QPS 限制。
     * key 含城市（city:name），跨城市同名景点不会错配；不传城市时退化为只按 name。
     */
    private final Cache<String, String> locationCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    public MapTool(@Value("${amap.api-key}") String apiKey) {
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().baseUrl("https://restapi.amap.com/v3").requestFactory(factory).build();
    }

    /** 搜索城市周边的景点/餐饮 POI，第③步检索用。 */
    @Tool(description = "搜索城市周边的景点/餐饮 POI，返回名称、坐标、地址")
    public List<Poi> searchPoi(@ToolParam(description = "关键词，如 故宫/川菜") String keyword,
                               @ToolParam(description = "城市，如 北京") String city) {
        JsonNode body = restClient.get()
                .uri(u -> u.path("/place/text")
                        .queryParam("key", apiKey)
                        .queryParam("keywords", keyword)
                        .queryParam("city", city)
                        .queryParam("offset", 10)
                        .build())
                .retrieve().body(JsonNode.class);

        // 高德 API 错误（配额超限/key 无效等）也返回 HTTP 200，需检查 status，否则空结果被误当「无结果」缓存
        String status = body == null ? "" : body.path("status").asText("");
        if (!"1".equals(status)) {
            throw new IllegalStateException("高德 API 错误：status=" + status + "，" + (body == null ? "" : body.path("info").asText("")));
        }

        List<Poi> pois = new ArrayList<>();
        JsonNode list = body == null ? null : body.path("pois");
        if (list != null && list.isArray()) {
            for (JsonNode p : list) {
                String[] ll = p.path("location").asText("").split(",");
                if (ll.length < 2) continue;
                pois.add(new Poi(
                        p.path("name").asText(),
                        Double.parseDouble(ll[0]),
                        Double.parseDouble(ll[1]),
                        p.path("address").asText()
                ));
            }
        }
        return pois;
    }

    /** 两个地点之间的驾车通勤时间/距离，第⑤步校验用。 */
    @Tool(description = "查询两个地点之间的驾车耗时和距离（传地点名即可，内部自动定位）")
    public RouteInfo getRoute(@ToolParam(description = "起点，如 故宫") String from,
                              @ToolParam(description = "终点，如 长城") String to,
                              @ToolParam(description = "城市（可选，用于区分同名地点），如 北京") String city) {
        String origin = resolveLocation(from, city);
        String destination = resolveLocation(to, city);

        JsonNode body = restClient.get()
                .uri(u -> u.path("/direction/driving")
                        .queryParam("key", apiKey)
                        .queryParam("origin", origin)
                        .queryParam("destination", destination)
                        .queryParam("extensions", "base")
                        .build())
                .retrieve().body(JsonNode.class);

        JsonNode path = body == null ? null : body.path("route").path("paths").path(0);
        int durationSec = path == null ? 0 : path.path("duration").asInt(0);
        double distanceM = path == null ? 0 : path.path("distance").asDouble(0);
        int durationMin = (int) Math.round(durationSec / 60.0);
        double distanceKm = Math.round(distanceM / 100.0) / 10.0;
        return new RouteInfo(durationMin, distanceKm);
    }

    /** 地点名 → "lng,lat"。先查缓存，未命中再 POI 搜索，搜不到再地理编码兜底。缓存 key 含城市，避免同名错配。 */
    private String resolveLocation(String name, String city) {
        String key = (city == null || city.isBlank() ? "" : city + ":") + name;
        try {
            return locationCache.get(key, k -> doResolveLocation(name, city));
        } catch (Exception e) {
            return "";  // 解析失败（无结果），返回空由上层决定
        }
    }

    /**
     * resolveLocation 的加载函数：先 POI 搜索，搜不到再地理编码兜底。
     * 结果为空时抛异常——Caffeine 对抛异常的加载不缓存，避免缓存住无效的空结果。
     */
    private String doResolveLocation(String name, String city) {
        JsonNode body = restClient.get()
                .uri(u -> {
                    var b = u.path("/place/text")
                            .queryParam("key", apiKey)
                            .queryParam("keywords", name)
                            .queryParam("offset", 1);
                    if (city != null && !city.isBlank()) b = b.queryParam("city", city);
                    return b.build();
                })
                .retrieve().body(JsonNode.class);
        JsonNode first = body == null ? null : body.path("pois").path(0);
        if (first != null && !first.isMissingNode()) {
            String loc = first.path("location").asText();
            if (!loc.isBlank()) {
                return loc;
            }
        }
        // 兜底：地理编码
        JsonNode geo = restClient.get()
                .uri(u -> u.path("/geocode/geo")
                        .queryParam("key", apiKey)
                        .queryParam("address", name)
                        .build())
                .retrieve().body(JsonNode.class);
        JsonNode loc = geo == null ? null : geo.path("geocodes").path(0).path("location");
        String result = loc == null ? "" : loc.asText();
        if (result.isBlank()) {
            throw new IllegalArgumentException("无法解析地点坐标：" + name);
        }
        return result;
    }

    public record RouteInfo(int durationMin, double distanceKm) {}

    public record Poi(String name, double longitude, double latitude, String address) {}
}
