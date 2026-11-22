import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

// Served from GitHub Pages under /face-blur/ (relative base works anywhere).
export default defineConfig({
  base: './',
  plugins: [preact()],
  // The build's number on GitHub Actions (shown in the footer), "dev" locally.
  define: { __BUILD__: JSON.stringify(process.env.GITHUB_RUN_NUMBER ?? 'dev') },
  worker: { format: 'es' },
  build: { target: 'es2022', chunkSizeWarningLimit: 2000 },
  optimizeDeps: { exclude: ['onnxruntime-web'] },
})
