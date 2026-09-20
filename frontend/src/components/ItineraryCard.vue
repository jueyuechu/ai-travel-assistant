<script setup>
import { computed } from 'vue';
import { renderMarkdown } from '../lib/markdown.js';

const props = defineProps({
    itinerary: { type: Object, required: true },
    advice: { type: String, default: '' },
});

const emit = defineEmits(['view-map']);

const typeMeta = {
    attraction: { icon: '🏛', label: '景点' },
    food: { icon: '🍜', label: '餐饮' },
    hotel: { icon: '🏨', label: '酒店' },
    transport: { icon: '🚇', label: '交通' },
};

function metaOf(type) {
    return typeMeta[type] || { icon: '·', label: type || '' };
}

function costText(v) {
    return v == null ? '' : '¥' + v;
}

const adviceHtml = computed(() => renderMarkdown(props.advice));
</script>

<template>
    <div class="itinerary">
        <header class="itinerary-header">
            <div class="itinerary-dest">{{ itinerary.destination }}</div>
            <div class="itinerary-meta">
                <span v-if="itinerary.days?.length">{{ itinerary.days.length }} 天</span>
                <span v-if="itinerary.totalCost != null">预算约 ¥{{ itinerary.totalCost }}</span>
                <button class="map-link" @click="emit('view-map', itinerary)">查看地图</button>
            </div>
        </header>

        <div v-if="itinerary.alerts?.length" class="itinerary-alerts">
            <div v-for="(a, i) in itinerary.alerts" :key="i" class="alert">⚠ {{ a }}</div>
        </div>

        <section v-for="d in itinerary.days" :key="d.day" class="day-card">
            <header class="day-head">
                <span class="day-no">Day {{ d.day }}</span>
                <span v-if="d.date" class="day-date">{{ d.date }}</span>
                <span v-if="d.weather" class="day-weather">{{ d.weather }}</span>
                <span v-if="d.dayCost != null" class="day-cost">{{ costText(d.dayCost) }}</span>
            </header>

            <ol class="stops">
                <li v-for="(s, i) in d.stops" :key="i" class="stop">
                    <span class="stop-time">{{ s.time }}</span>
                    <span class="stop-type" :title="metaOf(s.type).label">{{ metaOf(s.type).icon }}</span>
                    <div class="stop-body">
                        <div class="stop-name">{{ s.name }}</div>
                        <div v-if="s.address" class="stop-sub">{{ s.address }}</div>
                        <div v-if="s.note" class="stop-note">{{ s.note }}</div>
                    </div>
                    <span v-if="s.cost != null" class="stop-cost">{{ costText(s.cost) }}</span>
                </li>
            </ol>
        </section>

        <div v-if="advice" class="itinerary-advice">
            <div class="advice-title">出行建议</div>
            <div class="advice-body markdown" v-html="adviceHtml"></div>
        </div>
    </div>
</template>
