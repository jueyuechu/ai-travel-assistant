<script setup>
import { ref, onMounted } from 'vue';

const props = defineProps({
    itinerary: { type: Object, required: true },
});

const emit = defineEmits(['back']);

const mapEl = ref(null);
const error = ref('');

const TYPE_COLOR = {
    attraction: '#2f5d50',
    food: '#d97706',
    hotel: '#2563eb',
    transport: '#7c3aed',
};
const TYPE_LABEL = {
    attraction: '景点',
    food: '餐饮',
    hotel: '酒店',
    transport: '交通',
};

function collectStops() {
    const stops = [];
    let no = 0;
    for (const day of props.itinerary.days || []) {
        for (const s of day.stops || []) {
            if (s && s.longitude != null && s.latitude != null) {
                no += 1;
                stops.push({ ...s, day: day.day, no });
            }
        }
    }
    return stops;
}

function infoHtml(s) {
    const label = TYPE_LABEL[s.type] || '';
    const parts = [`<strong>${s.name}</strong>`];
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

        // 检测加载失败（如安全域名未配置）：5 秒未 complete 则销毁并提示，避免 SDK 无限重试刷屏
        let loaded = false;
        map.on('complete', () => { loaded = true; });
        setTimeout(() => {
            if (!loaded) {
                try { map.destroy(); } catch (e) {}
                error.value = '地图加载失败：可能是安全域名未配置（去高德控制台给 key 配安全域名）';
            }
        }, 5000);

        // 打点：不同类别不同颜色，点击弹详情
        const markers = stops.map((s) => {
            const color = TYPE_COLOR[s.type] || '#2f5d50';
            const marker = new window.AMap.Marker({
                position: [s.longitude, s.latitude],
                title: s.name,
                content: `<div class="map-marker" style="background:${color}">${s.no}</div>`,
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

        // 同一天内按顺序连线
        for (const day of props.itinerary.days || []) {
            const path = (day.stops || [])
                .filter((s) => s && s.longitude != null && s.latitude != null)
                .map((s) => [s.longitude, s.latitude]);
            if (path.length > 1) {
                map.add(new window.AMap.Polyline({ path, strokeColor: '#2f5d50', strokeWeight: 3, strokeOpacity: 0.8 }));
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
    </div>
</template>
