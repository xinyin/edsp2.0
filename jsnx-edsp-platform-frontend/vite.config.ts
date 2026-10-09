import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  cacheDir: '.vite-cache',
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/vendor-api': {
        target: 'http://127.0.0.1:8090',
        rewrite: path => path.replace(/^\/vendor-api/, '')
      },
      '/esb': {
        target: 'http://127.0.0.1:8084'
      }
    }
  }
})
