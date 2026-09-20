<script setup>
import { ref, nextTick, onMounted } from 'vue';
import ChatMessage from './components/ChatMessage.vue';
import ItineraryCard from './components/ItineraryCard.vue';
import MapView from './components/MapView.vue';

const messages = ref([]);
const sessions = ref([]);
const input = ref('');
const inputEl = ref(null);
const sending = ref(false);
const thinking = ref(false);
const thinkingText = ref('');
const sidebarOpen = ref(false);
const sidebarCollapsed = ref(false);
const messagesEl = ref(null);
const view = ref('chat');          // 'chat' | 'map'
const mapItinerary = ref(null);

const STORAGE_KEY = 'travel_conversation_id';
let conversationId = localStorage.getItem(STORAGE_KEY) || newConversationId();
let thinkingTimer = null;

function newConversationId() {
    return 'conv-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
}

// 规划类关键词，与后端 IntentRouter 保持一致
const PLAN_KEYWORDS = ['规划', '行程', '攻略', '路线', '安排', '玩几天', '几天'];
function isPlanIntent(msg) {
    return PLAN_KEYWORDS.some((k) => msg.includes(k));
}

// 空状态快捷问题（点击填入输入框）
const QUICK_ASKS = [
    '帮我规划北京3天行程，预算3000',
    '北京今天天气怎么样',
    '推荐长沙地道美食',
    '美元兑人民币汇率',
];

async function send() {
    const msg = input.value.trim();
    if (!msg || sending.value) return;
    input.value = '';
    messages.value.push({ role: 'user', content: msg });
    sending.value = true;
    thinking.value = true;
    thinkingText.value = isPlanIntent(msg) ? '正在规划行程…' : '正在思考…';
    // 非规划：2 秒后还没出结果，提示"正在查询"（工具调用期间给反馈）
    clearTimeout(thinkingTimer);
    if (!isPlanIntent(msg)) {
        thinkingTimer = setTimeout(() => {
            if (thinking.value) thinkingText.value = '正在查询实时信息…';
        }, 2000);
    }

    try {
        if (isPlanIntent(msg)) {
            await sendPlan(msg);
        } else {
            await sendChat(msg);
        }
    } finally {
        sending.value = false;
        thinking.value = false;
        clearTimeout(thinkingTimer);
        saveSession();  // 对话完成后同步到 Redis（fire-and-forget）
    }
}

/** 规划：调 /api/plan，complete=false 显示追问，true 渲染行程卡片。 */
async function sendPlan(msg) {
    const resp = await fetch('/api/plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: msg, conversationId }),
    });
    if (!resp.ok) {
        messages.value.push({ role: 'assistant', content: '请求失败：HTTP ' + resp.status });
        return;
    }
    const data = await resp.json();
    if (!data.complete) {
        messages.value.push({ role: 'assistant', content: data.missing });
    } else {
        messages.value.push({ role: 'assistant', itinerary: data.itinerary, advice: data.advice });
    }
    await nextTick();
    scrollToBottom();
}

/** 问答/实况：SSE 流式 + 前端打字机平滑，逐字显示，掩盖后端输出的突发/暂停。 */
async function sendChat(msg) {
    const assistant = { role: 'assistant', content: '', streaming: true };
    let pushed = false;
    let fullText = '';
    let displayLen = 0;
    let rafId = null;

    const ensurePushed = () => {
        if (!pushed) {
            messages.value.push(assistant);
            pushed = true;
        }
    };

    const stepTypewriter = () => {
        rafId = null;
        if (displayLen < fullText.length) {
            const backlog = fullText.length - displayLen;
            const step = backlog > 30 ? 3 : (backlog > 10 ? 2 : 1);
            displayLen = Math.min(displayLen + step, fullText.length);
            thinking.value = false;
            ensurePushed();
            assistant.content = fullText.slice(0, displayLen);
            scrollToBottom();
            rafId = requestAnimationFrame(stepTypewriter);
        }
    };
    const kick = () => {
        if (rafId == null) rafId = requestAnimationFrame(stepTypewriter);
    };

    try {
        const resp = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: msg, conversationId }),
        });
        if (!resp.ok || !resp.body) {
            messages.value.push({ role: 'assistant', content: '请求失败：HTTP ' + resp.status });
            return;
        }

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });

            const events = buffer.split('\n\n');
            buffer = events.pop();

            for (const event of events) {
                // 一个 SSE 事件的多个 data 行要用 \n 连接，否则 markdown 表格/列表的换行会丢失
                const data = event
                    .split('\n')
                    .filter((l) => l.startsWith('data:'))
                    .map((l) => l.slice(5).replace(/^ /, ''))
                    .join('\n');
                if (data) fullText += data;
            }
            kick();
        }

        if (rafId != null) {
            cancelAnimationFrame(rafId);
            rafId = null;
        }
        if (fullText) {
            thinking.value = false;
            ensurePushed();
            assistant.content = fullText;
            assistant.streaming = false;
        }
        scrollToBottom();
    } catch (e) {
        messages.value.push({ role: 'assistant', content: '请求出错：' + e.message });
    }
}

/** 同步当前会话到 Redis。 */
async function saveSession() {
    if (messages.value.length === 0) return;
    const first = messages.value.find((m) => m.role === 'user' && m.content);
    const title = first ? first.content.slice(0, 20) : '新对话';
    try {
        await fetch(`/api/sessions/${conversationId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, messages: messages.value }),
        });
        loadSessions();
    } catch (e) {
        // 同步失败忽略
    }
}

async function loadSessions() {
    try {
        const resp = await fetch('/api/sessions');
        if (resp.ok) sessions.value = await resp.json();
    } catch (e) {
        // 列表加载失败忽略
    }
}

async function switchSession(id) {
    conversationId = id;
    localStorage.setItem(STORAGE_KEY, id);
    thinking.value = false;
    try {
        const resp = await fetch(`/api/sessions/${id}`);
        if (resp.ok) {
            const data = await resp.json();
            messages.value = data || [];
        } else {
            messages.value = [];
        }
    } catch (e) {
        messages.value = [];
    }
    sidebarOpen.value = false;
    await nextTick();
    scrollToBottom();
}

async function deleteSession(id) {
    try {
        await fetch(`/api/sessions/${id}`, { method: 'DELETE' });
    } catch (e) {
        // 删除失败忽略
    }
    if (id === conversationId) {
        conversationId = newConversationId();
        localStorage.setItem(STORAGE_KEY, conversationId);
        messages.value = [];
    }
    await loadSessions();
}

function newChat() {
    conversationId = newConversationId();
    localStorage.setItem(STORAGE_KEY, conversationId);
    messages.value = [];
    thinking.value = false;
    sidebarOpen.value = false;
}

function showMap(itinerary) {
    mapItinerary.value = itinerary;
    view.value = 'map';
}

/** 侧边栏主动看地图：合并当前会话所有行程的景点（没有行程就打开空地图）。 */
function openMap() {
    const itineraries = messages.value.filter((m) => m.itinerary).map((m) => m.itinerary);
    if (itineraries.length === 0) {
        mapItinerary.value = { destination: '地图', days: [] };
    } else if (itineraries.length === 1) {
        mapItinerary.value = itineraries[0];
    } else {
        const mergedDays = itineraries.flatMap((it) => it.days || []);
        mapItinerary.value = { ...itineraries[0], destination: '全部行程', days: mergedDays };
    }
    view.value = 'map';
    sidebarOpen.value = false;
}

function backToChat() {
    view.value = 'chat';
}

/** 侧边栏展开/收缩：桌面折叠，移动抽屉。 */
function toggleSidebar() {
    if (window.innerWidth <= 768) {
        sidebarOpen.value = !sidebarOpen.value;
    } else {
        sidebarCollapsed.value = !sidebarCollapsed.value;
    }
}

function onKeydown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        send();
    }
}

/** 空状态快捷问题：填入输入框并聚焦（不自动发送，让用户可改） */
function quickAsk(q) {
    input.value = q;
    inputEl.value?.focus();
}

function scrollToBottom() {
    if (messagesEl.value) {
        messagesEl.value.scrollTop = messagesEl.value.scrollHeight;
    }
}

onMounted(async () => {
    await loadSessions();
    // 刷新后恢复当前会话历史
    try {
        const resp = await fetch(`/api/sessions/${conversationId}`);
        if (resp.ok) {
            const data = await resp.json();
            if (data && data.length) messages.value = data;
        }
    } catch (e) {
        // 恢复失败忽略
    }
});
</script>

<template>
    <MapView v-if="view === 'map'" :itinerary="mapItinerary" @back="backToChat" />
    <div v-else class="app">
        <aside class="sidebar" :class="{ open: sidebarOpen, collapsed: sidebarCollapsed }">
            <button class="new-chat-btn" @click="newChat">＋ 新对话</button>
            <button class="map-btn" @click="openMap">查看地图</button>
            <ul class="session-list">
                <li
                    v-for="s in sessions"
                    :key="s.id"
                    :class="{ active: s.id === conversationId }"
                    @click="switchSession(s.id)"
                >
                    <span class="session-title">{{ s.title }}</span>
                    <button class="session-del" title="删除" @click.stop="deleteSession(s.id)">×</button>
                </li>
                <li v-if="sessions.length === 0" class="session-empty">暂无历史会话</li>
            </ul>
        </aside>

        <div class="chat-container">
            <header class="chat-header">
                <button class="menu-btn" @click="toggleSidebar">☰</button>
                <div class="title">旅游 AI 助手</div>
                <button class="new-chat-btn" @click="newChat">新对话</button>
            </header>

            <main ref="messagesEl" class="messages">
                <div v-if="messages.length === 0" class="welcome">
                    <svg class="brand-logo" viewBox="0 0 48 48" fill="none" aria-hidden="true">
                        <circle cx="24" cy="24" r="20" stroke="currentColor" stroke-width="2.5"/>
                        <path d="M24 11 L30 24 L24 37 L18 24 Z" fill="currentColor"/>
                    </svg>
                    <h1 class="welcome-title">你好，我是你的旅游 AI 助手</h1>
                    <p class="welcome-sub">查天气 · 问景点 · 规划行程 · 看汇率</p>
                    <div class="quick-asks">
                        <button v-for="q in QUICK_ASKS" :key="q" class="quick-chip" @click="quickAsk(q)">{{ q }}</button>
                    </div>
                </div>
                <template v-for="(m, i) in messages" :key="i">
                    <ItineraryCard v-if="m.itinerary" :itinerary="m.itinerary" :advice="m.advice" @view-map="showMap" />
                    <ChatMessage v-else :role="m.role" :content="m.content" :streaming="m.streaming" />
                </template>

                <div v-if="thinking" class="message assistant thinking">
                    <span class="thinking-dots">
                        <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                    </span>
                    <span v-if="thinkingText" class="thinking-text">{{ thinkingText }}</span>
                </div>
            </main>

            <footer class="input-area">
                <textarea
                    ref="inputEl"
                    v-model="input"
                    rows="1"
                    placeholder="输入你的问题，回车发送（Shift+回车换行）"
                    @keydown="onKeydown"
                ></textarea>
                <button class="send-btn" :disabled="sending" @click="send" :aria-label="sending ? '思考中' : '发送'">
                    <svg v-if="!sending" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <path d="M5 12h14M13 6l6 6-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    <span v-else class="send-dots"><span></span><span></span><span></span></span>
                </button>
            </footer>
        </div>
    </div>
</template>
