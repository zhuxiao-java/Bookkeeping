import { fileURLToPath, URL } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
// https://vitejs.dev/config/
export default defineConfig(({ mode }) => ({
    plugins: [vue()],
    // Electron 生产环境通过 file:// 协议加载 dist，必须使用相对路径
    base: './',
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url))
        }
    },
    server: {
        host: '127.0.0.1',
        port: 5173,
        proxy: {
            // 开发环境代理到本地 Java 后端（后端未配置 CORS，必须走代理）
            '/api': {
                target: loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)), 'VITE_').VITE_BACKEND_TARGET || 'http://127.0.0.1:8080',
                changeOrigin: true,
                timeout: 160000,
                proxyTimeout: 160000
            }
        }
    },
    build: {
        outDir: 'dist',
        chunkSizeWarningLimit: 2048
    }
}));
