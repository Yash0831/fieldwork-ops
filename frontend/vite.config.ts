import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Dev server: proxies /api to the Spring Boot backend so the browser
 * never needs CORS handling during local development. The target can be
 * overridden with VITE_API_PROXY_TARGET (e.g. a staging host).
 *
 * In production, serve the built app from the same origin as the API
 * (or set VITE_API_BASE_URL at build time) — see README.md.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
