/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The SPA is served by ops-console from "/" and calls the API under /api/v1 on the same origin.
// In development the Vite server proxies /api (including the SSE stream) to a local ops-console.
export default defineConfig({
  base: '/',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'dist',
    target: 'es2022',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.MAOP_API_TARGET ?? 'http://localhost:8080',
        // A remote target (for example a cluster route) is matched by Host header, so rewrite it then.
        changeOrigin: !!process.env.MAOP_API_TARGET,
        // Server-Sent Events: keep the upstream response streaming (no buffering, no timeout).
        timeout: 0,
        proxyTimeout: 0,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    restoreMocks: true,
  },
});
