import { defineConfig } from 'vite'

export default defineConfig({
  server: {
    // Allow Vite to serve files from the repo root (where Mill's out/ lives)
    fs: { allow: ['..'] }
  }
})
