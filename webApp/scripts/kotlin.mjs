// Builds the Kotlin core library for the web app: `node scripts/kotlin.mjs development|production`.
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const mode = process.argv[2] === 'development' ? 'Development' : 'Production';
const root = fileURLToPath(new URL('../..', import.meta.url));
const windows = process.platform === 'win32';
const gradlew = windows ? `"${join(root, 'gradlew.bat')}"` : join(root, 'gradlew');
const result = spawnSync(gradlew, [`:web:jsBrowser${mode}LibraryDistribution`, '--quiet'], {
  cwd: root,
  stdio: 'inherit',
  // Node runs .bat files only through a shell.
  shell: windows,
});
process.exit(result.status ?? 1);
