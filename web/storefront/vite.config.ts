import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, '.', 'VITE_')

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target: environment.VITE_GATEWAY_BASE_URL || 'http://localhost:8080',
        },
      },
    },
  }
})
