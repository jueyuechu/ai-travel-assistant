package com.itjyc.travel.orchestrator;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.itjyc.travel.domain.Itinerary;
import com.itjyc.travel.domain.TripRequest;
import com.itjyc.travel.domain.ValidationIssue;
import com.itjyc.travel.tool.MapTool;
import com.itjyc.travel.tool.WeatherTool;
import com.itjyc.travel.util.DateUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 规划流水线：② 抽取需求 → ④ 生成 → ⑤ 校验 → ⑥ 修正 → ⑦ 输出。
 * 支持多轮追问：缺字段时累积需求，攒齐再生成。
 *
 * 注意：generate/revise 用结构化输出（entity），LLM 不调用工具，天气是「猜」的；
 * 所以最后用 WeatherTool 查真实天气回填（enrich）。路线通勤校验由 validate 用 MapTool 直接查。
 */
@Component
public class TripPlanner {

    private static final String PLANNER_SYSTEM = """
            你是一个专业的旅游行程规划师。
            根据用户的旅行需求，规划一份合理、可执行的行程。
            直接基于你的知识生成行程，不要调用任何工具。
            每个景点（stop）的 type 字段必须是以下英文之一：attraction（景点）、food（餐饮）、hotel（酒店）、transport（交通），不要用中文。
            每天（day）字段从 1 开始连续递增，不要跳号。
            最终输出必须是符合要求的 JSON，不要用 markdown 代码块包裹。
            """;

    private final ChatClient planningChatClient;
    private final ChatMemory chatMemory;
    private final MapTool mapTool;
    private final WeatherTool weatherTool;
    private final ObjectMapper objectMapper;
    /** 会话级的需求累积：conversationId → 尚未收集完整的需求（带时间戳）。 */
    private final Map<String, PendingEntry> pending = new ConcurrentHashMap<>();
    /** 景点经纬度缓存：city:name → POI。Caffeine 原子加载 + TTL，异常不缓存、无结果用 POI_NOT_FOUND 占位。 */
    private final Cache<String, MapTool.Poi> poiCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofHours(6))
            .build();
    /** 查不到（无结果）的占位值。 */
    private static final MapTool.Poi POI_NOT_FOUND = new MapTool.Poi("", 0, 0, "");

    /** 未完成需求的最大存活时间（毫秒），超时视为放弃，避免内存累积。 */
    private final long pendingTtlMs;
    /** 相邻景点通勤时长告警阈值（分钟）。 */
    private final int routeThresholdMin;
    /** 高德调用并行线程池：有界，避免并发过高触发高德 QPS 限制。 */
    private final ExecutorService amapExecutor;

    private record PendingEntry(TripRequest request, long ts) {}

    /** 校验用的相邻点对：day → from 景点 → to 景点。day 用包装类型，避免 LLM 漏字段时拆箱 NPE。 */
    private record RoutePair(Integer day, String from, String to, String city) {}

    public TripPlanner(@Qualifier("planningChatClient") ChatClient planningChatClient, ChatMemory chatMemory, MapTool mapTool, WeatherTool weatherTool, ObjectMapper objectMapper,
                       @Value("${trip.pending-ttl-min:30}") long pendingTtlMin,
                       @Value("${trip.route-threshold-min:90}") int routeThresholdMin,
                       @Value("${trip.amap-pool-size:8}") int amapPoolSize) {
        this.planningChatClient = planningChatClient;
        this.chatMemory = chatMemory;
        this.mapTool = mapTool;
        this.weatherTool = weatherTool;
        this.objectMapper = objectMapper;
        this.pendingTtlMs = pendingTtlMin * 60 * 1000L;
        this.routeThresholdMin = routeThresholdMin;
        // 有界队列 + CallerRunsPolicy：队列满时由调用线程执行（背压），避免请求堆积时队列无界膨胀
        this.amapExecutor = new ThreadPoolExecutor(
                amapPoolSize, amapPoolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(amapPoolSize * 4),
                r -> {
                    Thread t = new Thread(r, "amap-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdown() {
        amapExecutor.shutdownNow();
    }

    /** 交互式规划：多轮累积需求，缺字段返回追问，完整则生成行程。 */
    public PlanResult planInteractive(String message, String conversationId) {
        long now = System.currentTimeMillis();
        // 清理超时未完成的需求（默认 30 分钟视为放弃）
        pending.entrySet().removeIf(e -> now - e.getValue().ts() > pendingTtlMs);

        TripRequest extracted = extractRequest(message, conversationId);
        PendingEntry existing = pending.get(conversationId);

        // 抽取失败（LLM 返回非法 JSON / 网络抖动）：不打断，保留已有累积继续追问
        if (extracted == null) {
            TripRequest base = existing == null ? null : existing.request();
            return PlanResult.pending(base == null
                    ? "请告诉我您的旅行目的地、天数、预算等需求"
                    : String.join("、", base.missingRequiredFields()));
        }

        TripRequest merged = existing == null ? extracted : existing.request().merge(extracted);

        if (!merged.isComplete()) {
            pending.put(conversationId, new PendingEntry(merged, now));
            return PlanResult.pending(String.join("、", merged.missingRequiredFields()));
        }
        pending.remove(conversationId);
        Itinerary itinerary = plan(merged);
        return PlanResult.done(itinerary);
    }

    /** ② 从用户输入抽取旅行需求。读主会话历史作为上下文（目的地可能在上一句），但不写记忆避免污染。 */
    private TripRequest extractRequest(String message, String conversationId) {
        try {
            StringBuilder system = new StringBuilder(
                    "你是旅行需求分析助手。从用户的话里抽取目的地、天数、人数、预算、偏好、节奏等字段。直接输出 JSON，不要用 markdown 代码块。\n今天是 "
                            + DateUtil.today() + "。");

            // 读主会话历史（前几轮用户说的，如「我想去北京」），让抽取能拿到上下文
            List<Message> history = chatMemory.get(conversationId);
            if (history != null && !history.isEmpty()) {
                system.append("\n\n以下是之前的对话历史，目的地等关键信息可能在历史里，请结合抽取：\n");
                for (Message m : history) {
                    if (m == null || m.getText() == null || m.getText().isBlank()) continue;
                    String role = m.getMessageType() == MessageType.USER ? "用户" : "助手";
                    system.append(role).append("：").append(m.getText()).append("\n");
                }
            }

            return planningChatClient.prompt()
                    .system(system.toString())
                    .user(message)
                    .call()
                    .entity(TripRequest.class);
        } catch (Exception e) {
            // 抽取失败：返回 null，由 planInteractive 降级为追问，不让规划整体 500
            return null;
        }
    }

    /** 规划入口：④ 生成 → ⑤ 校验 → ⑥ 修正 → ⑦ 输出（并回填真实天气 + 预算校验）。 */
    private Itinerary plan(TripRequest request) {
        Itinerary draft = generate(request);
        List<ValidationIssue> issues = validate(draft);
        Itinerary result = issues.isEmpty() ? draft : revise(draft, issues);
        result = enrichWeather(result);
        result = enrichLocations(result);
        return checkBudget(request, result);
    }

    /** ④ 生成行程草稿（结构化输出，LLM 不调用工具）。 */
    private Itinerary generate(TripRequest request) {
        return planningChatClient.prompt()
                .system(PLANNER_SYSTEM + "\n今天是 " + DateUtil.today() + "。")
                .user("旅行需求：" + request)
                .call()
                .entity(Itinerary.class);
    }

    /** ⑤ 校验：用地图查相邻景点真实通勤时间，超过阈值标记问题。并行查，避免串行 N 次高德调用。 */
    private List<ValidationIssue> validate(Itinerary itinerary) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (itinerary == null || itinerary.days() == null) return issues;

        // 收集所有相邻点对
        String city = itinerary.destination();
        List<RoutePair> pairs = new ArrayList<>();
        for (Itinerary.DayPlan day : itinerary.days()) {
            List<Itinerary.Stop> stops = day == null ? null : day.stops();
            if (stops == null) continue;
            for (int i = 0; i < stops.size() - 1; i++) {
                Itinerary.Stop a = stops.get(i);
                Itinerary.Stop b = stops.get(i + 1);
                if (a == null || b == null || a.name() == null || b.name() == null) continue;
                pairs.add(new RoutePair(day.day(), a.name(), b.name(), city));
            }
        }

        // 并行查通勤，收集问题
        List<CompletableFuture<ValidationIssue>> futures = pairs.stream()
                .map(p -> CompletableFuture.supplyAsync(() -> checkRoute(p), amapExecutor))
                .toList();
        for (CompletableFuture<ValidationIssue> f : futures) {
            ValidationIssue issue = f.join();  // checkRoute 已内部捕获异常，不会抛
            if (issue != null) issues.add(issue);
        }
        return issues;
    }

    /** 单个点对的通勤校验，返回问题或 null；异常在此吞掉，不影响整体。 */
    private ValidationIssue checkRoute(RoutePair p) {
        try {
            MapTool.RouteInfo route = mapTool.getRoute(p.from(), p.to(), p.city());
            if (route.durationMin() > routeThresholdMin) {
                return new ValidationIssue(p.day(), p.to(), "route_time",
                        p.from() + " → " + p.to() + " 通勤 " + route.durationMin() + " 分钟，过长",
                        "调整景点顺序或替换为更近的景点");
            }
        } catch (Exception ignored) {
            // 单点查询失败不阻塞整体校验
        }
        return null;
    }

    /** ⑥ 根据校验问题修正行程。 */
    private Itinerary revise(Itinerary draft, List<ValidationIssue> issues) {
        return planningChatClient.prompt()
                .system(PLANNER_SYSTEM + "\n今天是 " + DateUtil.today() + "。")
                .user("原行程（JSON）：" + toJson(draft) + "\n\n请修正以下问题后重新输出行程：\n" + toJson(issues))
                .call()
                .entity(Itinerary.class);
    }

    /** 序列化成 JSON 给 LLM；失败回退 toString（record 默认格式 LLM 也能读，只是不美观）。 */
    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /** 用真实天气回填每天的 weather 字段（结构化输出不查工具，天气是猜的）。 */
    private Itinerary enrichWeather(Itinerary itinerary) {
        if (itinerary == null || itinerary.destination() == null
                || itinerary.days() == null || itinerary.days().isEmpty()) {
            return itinerary;
        }
        List<WeatherTool.WeatherDay> weather;
        try {
            int weatherDays = itinerary.days().size() <= 3 ? 3 : 7;
            weather = weatherTool.getWeatherData(itinerary.destination(), weatherDays);
        } catch (Exception e) {
            return itinerary;  // 天气查不到，保留 LLM 原值
        }
        if (weather == null || weather.isEmpty()) return itinerary;

        List<Itinerary.DayPlan> newDays = new ArrayList<>();
        for (int i = 0; i < itinerary.days().size(); i++) {
            Itinerary.DayPlan day = itinerary.days().get(i);
            if (day == null) continue;
            WeatherTool.WeatherDay wd = matchWeather(day.date(), weather, i);
            String weatherText = wd == null ? day.weather() : formatWeather(wd);
            newDays.add(new Itinerary.DayPlan(newDays.size() + 1, day.date(), weatherText, day.stops(), day.dayCost()));
        }
        return new Itinerary(itinerary.destination(), newDays, itinerary.totalCost(), itinerary.alerts());
    }

    /** 用高德 POI 搜索拿每个景点的真实经纬度（LLM 生成的不准）。先去重再并行查，同名景点只打一次高德。 */
    private Itinerary enrichLocations(Itinerary itinerary) {
        if (itinerary == null || itinerary.destination() == null || itinerary.days() == null) {
            return itinerary;
        }
        String city = itinerary.destination();

        // 1. 收集所有需要查的景点名（去重，跳过 null/空）
        Set<String> names = new LinkedHashSet<>();
        for (Itinerary.DayPlan day : itinerary.days()) {
            if (day == null || day.stops() == null) continue;
            for (Itinerary.Stop s : day.stops()) {
                if (s != null && s.name() != null && !s.name().isBlank()) {
                    names.add(s.name());
                }
            }
        }

        // 2. 并行解析唯一景点 → 坐标（name → Poi，查不到不放入）
        Map<String, MapTool.Poi> resolved = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = names.stream()
                .map(name -> CompletableFuture.runAsync(() -> {
                    MapTool.Poi poi = resolvePoi(city, name);
                    if (poi != null) resolved.put(name, poi);
                }, amapExecutor))
                .toList();
        for (CompletableFuture<Void> f : futures) {
            f.join();
        }

        // 3. 回填到每个 stop
        List<Itinerary.DayPlan> newDays = new ArrayList<>();
        for (Itinerary.DayPlan day : itinerary.days()) {
            if (day == null) continue;
            if (day.stops() == null) {
                newDays.add(new Itinerary.DayPlan(newDays.size() + 1, day.date(), day.weather(), null, day.dayCost()));
                continue;
            }
            List<Itinerary.Stop> newStops = new ArrayList<>(day.stops().size());
            for (Itinerary.Stop s : day.stops()) {
                Itinerary.Stop enriched = s;
                if (s != null && s.name() != null) {
                    MapTool.Poi poi = resolved.get(s.name());
                    if (poi != null) {
                        enriched = new Itinerary.Stop(s.time(), s.type(), s.name(),
                                poi.longitude(), poi.latitude(), s.address(),
                                s.durationMin(), s.cost(), s.note());
                    }
                }
                newStops.add(enriched);
            }
            newDays.add(new Itinerary.DayPlan(newDays.size() + 1, day.date(), day.weather(), newStops, day.dayCost()));
        }
        return new Itinerary(itinerary.destination(), newDays, itinerary.totalCost(), itinerary.alerts());
    }

    private MapTool.Poi resolvePoi(String city, String name) {
        String key = city + ":" + name;
        MapTool.Poi poi;
        try {
            // Caffeine.get 原子 single-flight：并发下同 key 只加载一次；loader 抛异常不缓存（下次重试）
            poi = poiCache.get(key, k -> {
                try {
                    List<MapTool.Poi> pois = mapTool.searchPoi(name, city);
                    return pois.isEmpty() ? POI_NOT_FOUND : pois.get(0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            return null;  // 临时故障不缓存，返回 null 由上层兜底
        }
        return poi == POI_NOT_FOUND ? null : poi;
    }

    /** 预算校验：总花费超预算时加一条提醒。 */
    private Itinerary checkBudget(TripRequest request, Itinerary itinerary) {
        if (request == null || request.budget() == null
                || itinerary == null || itinerary.totalCost() == null) {
            return itinerary;
        }
        if (itinerary.totalCost() > request.budget().doubleValue()) {
            List<String> alerts = itinerary.alerts() == null ? new ArrayList<>() : new ArrayList<>(itinerary.alerts());
            long total = Math.round(itinerary.totalCost());
            alerts.add("预算 " + request.budget() + " 元，行程总花费约 " + total + " 元，已超出预算");
            return new Itinerary(itinerary.destination(), itinerary.days(), itinerary.totalCost(), alerts);
        }
        return itinerary;
    }

    /** 按日期匹配天气，匹配不到就按顺序 fallback。 */
    private WeatherTool.WeatherDay matchWeather(String date, List<WeatherTool.WeatherDay> weather, int index) {
        if (date != null && date.length() >= 5) {
            String mmdd = date.substring(5);  // "2026-09-16" -> "09-16"
            for (WeatherTool.WeatherDay wd : weather) {
                if (mmdd.equals(wd.date())) return wd;
            }
        }
        return index < weather.size() ? weather.get(index) : null;
    }

    private String formatWeather(WeatherTool.WeatherDay wd) {
        return wd.weather() + " " + wd.tempMin() + "~" + wd.tempMax() + "℃ " + wd.wind();
    }

    /** 交互式规划的结果：要么还没收集完（缺字段），要么已完成（含行程）。 */
    public record PlanResult(boolean complete, String missing, Itinerary itinerary) {
        static PlanResult pending(String missing) {
            return new PlanResult(false, missing, null);
        }

        static PlanResult done(Itinerary itinerary) {
            return new PlanResult(true, null, itinerary);
        }
    }
}
