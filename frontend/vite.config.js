import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
    plugins: [vue()],
    server: {
        host: true,     // 监听所有接口，手机可通过局域网 IP 访问
        port: 3000,
        proxy: {
            // 把 /api 请求代理到后端，前端无跨域问题
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
});
