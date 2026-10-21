import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const path = (relative: string) => fileURLToPath(new URL(relative, import.meta.url));

/**
 * The Content-Security-Policy of the built app (ADR 0017: sessions live in localStorage, so no foreign script may run). Servers are
 * user-chosen, so connections, media and images may go to any http or https origin; scripts, styles and everything else only to this
 * one. docs/web-hosting.md has the same policy as response headers, with frame-ancestors, which a meta tag cannot set.
 */
const contentSecurityPolicy = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'self'",
  "connect-src 'self' http: https:",
  // blob: for Media Source Extensions (server HLS) in the player.
  "media-src 'self' http: https: blob:",
  "img-src 'self' http: https: data:",
].join('; ');

/** Adds the policy to the production index.html only: the dev server injects inline code (React Fast Refresh) that it would block. */
function contentSecurityPolicyMeta(): Plugin {
  return {
    name: 'current-csp',
    apply: 'build',
    transformIndexHtml: () => [
      { tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: contentSecurityPolicy }, injectTo: 'head-prepend' },
    ],
  };
}

// The Kotlin core is Gradle's ES module library (`:web`, ADR 0011): `npm run dev` builds the development one (faster), `npm run build`
// the production one; scripts/kotlin.mjs runs Gradle first.
export default defineConfig(({ command }) => {
  const library = path(`../web/build/dist/js/${command === 'serve' ? 'developmentLibrary' : 'productionLibrary'}`);
  return {
    plugins: [react(), contentSecurityPolicyMeta()],
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
