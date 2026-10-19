import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const path = (relative: string) => fileURLToPath(new URL(relative, import.meta.url));

// The Kotlin core is Gradle's ES module library (`:web`, ADR 0011): `npm run dev` builds the development one (faster), `npm run build`
// the production one; scripts/kotlin.mjs runs Gradle first.
export default defineConfig(({ command }) => {
  const library = path(`../web/build/dist/js/${command === 'serve' ? 'developmentLibrary' : 'productionLibrary'}`);
  return {
    plugins: [react()],
    // Unknown paths get index.html, so every History API URL loads the app (dev server and preview).
    appType: 'spa',
    resolve: {
      alias: {
        'current-core': `${library}/current-core.mjs`,
        // Ktor's JS client imports the Node-only "ws" package, which Kotlin's webpack setup hides and Vite does not.
        ws: path('./src/ws-stub.ts'),
      },
    },
    server: {
      port: 5173,
      strictPort: true,
      // The library lives outside this folder; `gradlew :web:jsBrowserDevelopmentLibraryDistribution --continuous` rebuilds it.
      fs: { allow: ['.', library] },
    },
    preview: { port: 4173, strictPort: true },
    build: { outDir: 'build/vite', emptyOutDir: true },
  };
});
