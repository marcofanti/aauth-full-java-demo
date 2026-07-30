import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 3050,
    strictPort: true,
    allowedHosts: ['portal.uma.lab'],
  },
})
