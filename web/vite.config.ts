import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 后端固定绑定 loopback:8790（见 docs/api-contract.md）。
const BACKEND = 'http://127.0.0.1:8790'

export default defineConfig({
  // 产物由 Spring Boot 从 classpath 托管，必须是相对路径。
  base: './',
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: BACKEND,
        changeOrigin: false,
        ws: false,
        // 契约规定：写请求带 Origin 且不是 127.0.0.1:8790 / localhost:8790 时返回 403 ORIGIN_REJECTED。
        // 开发期页面跑在 5173，浏览器会给 POST/PUT/DELETE 带上 5173 的 Origin，
        // 所以在代理层把 Origin 改写成后端允许的值（两种写法都保留，兼容 http-proxy 行为差异）。
        headers: { origin: BACKEND },
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('origin', BACKEND)
          })
        },
      },
    },
  },
})
