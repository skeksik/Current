import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

// The Kotlin core comes from Gradle's ES module output: `:spikes:web-ts:jsBrowserProductionLibraryDistribution`
// (or the development one with KOTLIN_DEV=1, which builds faster).
const library = process.env.KOTLIN_DEV ? 'developmentLibrary' : 'productionLibrary';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      'current-core': fileURLToPath(new URL(`./build/dist/js/${library}/current-core.mjs`, import.meta.url)),
      // Kotlin's webpack setup hides this Node-only import; Vite needs it resolved.
      ws: fileURLToPath(new URL('./ui/ws-stub.ts', import.meta.url)),
    },
  },
  server: { port: 5173, strictPort: true },
  preview: { port: 4173, strictPort: true },
  build: { outDir: 'build/vite', emptyOutDir: true },
});
