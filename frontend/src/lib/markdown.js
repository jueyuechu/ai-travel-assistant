import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';

// markdown-it 实例：只渲染安全子集，关闭 HTML 透传（源头防 XSS）
const md = new MarkdownIt({
    html: false,      // 不解析内联 HTML，LLM 若输出 <script> 会被转义成文本
    linkify: true,    // 自动识别纯 URL 为链接
    breaks: false,    // 单换行不强制 <br>，保持 markdown 语义
});

// 删除线：LLM 常输出 `20~30℃` 温度区间，被误判为删除线导致横线覆盖。
// markdown-it 用 <s> 标签渲染删除线（token 类型 s_open / s_close），把标签渲染为空，
// 保留中间文本、去掉横线，也不残留 `~~`。
md.renderer.rules.s_open = () => '';
md.renderer.rules.s_close = () => '';

// markdown-it 解析 → DOMPurify 消毒。LLM 输出是不可信输入，消毒这一步不可省略。
export function renderMarkdown(text) {
    return DOMPurify.sanitize(md.render(text || ''));
}
