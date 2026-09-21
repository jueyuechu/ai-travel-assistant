<script setup>
import { ref, computed, onMounted } from 'vue';

const props = defineProps({
    itinerary: { type: Object, required: true },
});

const emit = defineEmits(['back']);

const mapEl = ref(null);
const error = ref('');

// 按天配色（循环使用），让「第几天」一目了然
const DAY_COLORS = [
    '#2f5d50', // Day1 深松绿
    '#d97706', // Day2 琥珀
    '#2563eb', // Day3 蓝
    '#9333ea', // Day4 紫
    '#dc2626', // Day5 红
    '#0891b2', // Day6 青
    '#be185d', // Day7 粉
];
// 起点 / 终点用固定色（地图通用惯例：绿=起点、红=终点）
const START_COLOR = '#16a34a';
const END_COLOR = '#dc2626';

const TYPE_LABEL = {
    attraction: '景点',
    food: '餐饮',
    hotel: '酒店',
    transport: '交通',
};

function dayColor(day) {
    const idx = ((day || 1) - 1) % DAY_COLORS.length;
    return DAY_COLORS[idx];
}

// 有景点坐标的天，用于图例（只显示多天时）
const dayList = computed(() => {
    return (props.itinerary.days || [])
        .filter((d) => d && (d.stops || []).some((s) => s && s.longitude != null && s.latitude != null))
        .map((d) => d.day)
        .filter((d) => d != null);
});

function collectStops() {
    const stops = [];
    for (const day of props.itinerary.days || []) {
        // 当天有坐标的点，按顺序标角色：起 / 途经 / 终（单点则普通序号）
        const valid = (day.stops || []).filter((s) => s && s.longitude != null && s.latitude != null);
        valid.forEach((s, i) => {
            const role = valid.length === 1 ? 'single'
                : i === 0 ? 'start'
                : i === valid.length - 1 ? 'end'
                : 'waypoint';
            stops.push({ ...s, day: day.day, no: i + 1, role });
        });
    }
    return stops;
}

function infoHtml(s) {
    const label = TYPE_LABEL[s.type] || '';
    const parts = [
        `<div class="mi-day" style="color:${dayColor(s.day)}">Day ${s.day}</div>`,
        `<strong>${s.name}</strong>`,
    ];
    if (s.time) parts.push(`<div class="mi-row">⏰ ${s.time}</div>`);
    if (label) parts.push(`<div class="mi-row">🏷 ${label}</div>`);
    if (s.address) parts.push(`<div class="mi-row">📍 ${s.address}</div>`);
    if (s.cost != null) parts.push(`<div class="mi-row">💰 ¥${s.cost}</div>`);
    if (s.note) parts.push(`<div class="mi-row">📝 ${s.note}</div>`);
    return `<div class="map-info">${parts.join('')}</div>`;
}

onMounted(() => {
    if (!window.AMap) {
        error.value = '高德地图加载失败，请检查 JS API key（.env 里的 VITE_AMAP_JS_KEY）';
        return;
    }
    try {
        const stops = collectStops();
        const center = stops.length ? [stops[0].longitude, stops[0].latitude] : [116.397, 39.909];

        const map = new window.AMap.Map(mapEl.value, { zoom: stops.length ? 12 : 11, center });
        if (window.AMap.Scale) map.addControl(new window.AMap.Scale());

        // 检测加载失败：5 秒未 complete 则销毁并提示，避免 SDK 无限重试刷屏
        let loaded = false;
        map.on('complete', () => { loaded = true; });
        setTimeout(() => {
            if (!loaded) {
                try { map.destroy(); } catch (e) {}
                error.value = '地图加载失败：可能是安全域名未配置（去高德控制台给 key 配安全域名）';
            }
        }, 5000);

        // 打点：起点/终点用固定色，途经点按天配色；统一显示「D天-次序」，序号从 1 连续
        const markers = stops.map((s) => {
            const isStart = s.role === 'start';
            const isEnd = s.role === 'end';
            const color = isStart ? START_COLOR : isEnd ? END_COLOR : dayColor(s.day);
            const label = `D${s.day}-${s.no}`;
            const marker = new window.AMap.Marker({
                position: [s.longitude, s.latitude],
                title: `${isStart ? '起点 · ' : isEnd ? '终点 · ' : ''}Day ${s.day} · ${s.name}`,
                content: `<div class="map-marker" style="background:${color}">${label}</div>`,
                anchor: 'center',
            });
            const info = new window.AMap.InfoWindow({
                content: infoHtml(s),
                offset: new window.AMap.Pixel(0, -34),
            });
            marker.on('click', () => info.open(map, marker.getPosition()));
            return marker;
        });
        markers.forEach((m) => map.add(m));

        // 同一天内按顺序连线，颜色与当天标记一致
        for (const day of props.itinerary.days || []) {
            const path = (day.stops || [])
                .filter((s) => s && s.longitude != null && s.latitude != null)
                .map((s) => [s.longitude, s.latitude]);
            if (path.length > 1) {
                map.add(new window.AMap.Polyline({
                    path,
                    strokeColor: dayColor(day.day),
                    strokeWeight: 4,
                    strokeOpacity: 0.85,
                }));
            }
        }

        if (markers.length) map.setFitView(markers);
    } catch (e) {
        error.value = '地图初始化失败：' + (e && e.message ? e.message : e);
    }
});
</script>

<template>
    <div class="map-view">
        <header class="map-header">
            <span class="map-title">{{ itinerary.destination }} · 行程地图</span>
        </header>
        <button class="back-btn" @click="emit('back')" aria-label="返回">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M15 6l-6 6 6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
        </button>
        <div v-if="error" class="map-error">{{ error }}</div>
        <div v-else ref="mapEl" class="map-canvas"></div>

        <!-- 图例：多天时显示每天的配色 -->
        <div v-if="dayList.length > 1" class="map-legend">
            <div v-for="d in dayList" :key="d" class="legend-item">
                <span class="legend-dot" :style="{ background: dayColor(d) }"></span>
                <span class="legend-text">Day {{ d }}</span>
            </div>
        </div>
    </div>
</template>
