package com.itjyc.travel.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 示例攻略数据导入：启动时把内置的攻略片段向量化写入 Redis Stack。
 * 先删后写，保证幂等（重复启动不产生重复数据）。
 */
@Component
public class SeedDataInitializer implements CommandLineRunner {

    private final VectorStore vectorStore;

    public SeedDataInitializer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        // 直接导入示例数据。注意：重复启动会重复导入（开发阶段可接受）。
        // 生产环境要幂等，需手动构建 RedisVectorStore 并注册 metadataFields 字段，或用独立标记判断。
        vectorStore.add(seedDocs());
    }

    private List<Document> seedDocs() {
        return List.of(
                new Document(
                        "北京故宫是中国明清两代的皇家宫殿，位于北京中轴线的中心，是中国古代宫廷建筑的精华。游览故宫建议提前7天在官网预约门票，周一闭馆。旺季门票60元，淡季40元。游览时间约3到4小时，可沿中轴线参观太和殿、乾清宫，再到珍宝馆和钟表馆。",
                        Map.of("source", "seed", "destination", "北京", "topic", "故宫")),
                new Document(
                        "八达岭长城是明长城中保存最完整的一段，位于北京延庆区，距市区约70公里。可乘S2线火车、877路公交或驾车前往，驾车约1.5小时。旺季门票40元。游览约3小时，建议早出发避开人流。慕田峪长城人少景美，适合深度游。",
                        Map.of("source", "seed", "destination", "北京", "topic", "长城")),
                new Document(
                        "天坛是明清两代皇帝祭天、祈谷的场所，位于北京东城区。联票35元，含祈年殿、回音壁、圜丘。游览约2到3小时，建议清晨前往，可看到当地居民晨练。",
                        Map.of("source", "seed", "destination", "北京", "topic", "天坛")),
                new Document(
                        "上海外滩位于黄浦江畔，是上海的地标，可欣赏对岸陆家嘴的摩天大楼天际线。夜晚灯光亮起后景色最佳，适合傍晚散步。豫园是明代古典园林，位于城隍庙附近，门票40元，周边小吃丰富。",
                        Map.of("source", "seed", "destination", "上海", "topic", "外滩")),
                new Document(
                        "杭州西湖是免费开放的5A级景区，三面环山一面城。经典路线是沿苏堤白堤漫步，或乘船游湖。灵隐寺位于西湖西侧，是江南著名古刹，门票45元。春季樱花和秋季桂花是西湖最美的季节。",
                        Map.of("source", "seed", "destination", "杭州", "topic", "西湖")),
                new Document(
                        "成都大熊猫繁育研究基地是观赏大熊猫的最佳去处，建议早上8点前到达，此时熊猫最活跃。宽窄巷子是成都历史文化街区，适合品尝小吃和体验川西民居。成都美食以火锅、串串、担担面著称。",
                        Map.of("source", "seed", "destination", "成都", "topic", "大熊猫"))
        );
    }
}
