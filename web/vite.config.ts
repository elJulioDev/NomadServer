import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// La build cae directo en los assets de la app. La APK se arma sin Node porque el resultado
// queda commiteado; sólo hace falta `npm run build` cuando cambies la UI.
export default defineConfig({
  base: './',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: '../app/src/main/assets/ui',
    emptyOutDir: true,
  },
})
