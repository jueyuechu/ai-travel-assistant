<script setup>
import { computed } from 'vue';
import { renderMarkdown } from '../lib/markdown.js';

const props = defineProps({
    role: { type: String, required: true },
    content: { type: String, default: '' },
    streaming: { type: Boolean, default: false },
});

// 助手消息渲染 markdown，用户消息纯文本
const html = computed(() => {
    if (props.role !== 'assistant') return '';
    return renderMarkdown(props.content);
});
</script>

<template>
    <div class="message" :class="role">
        <div v-if="role === 'user'" class="text">{{ content }}</div>
        <div v-else class="markdown" v-html="html"></div>
        <!-- 流未结束时光标闪烁，表示"还在生成" -->
        <span v-if="role === 'assistant' && streaming" class="typing-cursor"></span>
    </div>
</template>
