// ADR 0009: npm packages shipped in the web app must be permissive or LGPL-2.1. Build-only tools (devDependencies) are not shipped.
// Checks every production dependency, direct or transitive, as installed by `npm ci`.
import { execSync } from 'node:child_process';

const allowed = new Set([
  'MIT', 'ISC', '0BSD', 'BSD-2-Clause', 'BSD-3-Clause', 'Apache-2.0', 'Zlib', 'Unlicense', 'CC0-1.0',
  'LGPL-2.1', 'LGPL-2.1-only', 'LGPL-2.1-or-later',
]);

/** An SPDX expression passes if each AND term passes and, within it, any OR alternative does. */
function passes(expression) {
  if (typeof expression !== 'string' || expression === '') return false;
  return expression.replace(/[()]/g, '').split(/\s+AND\s+/).every((term) =>
    term.split(/\s+OR\s+/).some((id) => allowed.has(id.trim())));
}

// The query also returns this app itself (location ""), which is not a dependency.
const packages = JSON.parse(execSync('npm query .prod', { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }))
  .filter((p) => p.location !== '');
let failed = false;
for (const p of packages.sort((a, b) => a.name.localeCompare(b.name))) {
  const licence = typeof p.license === 'object' ? p.license?.type : p.license;
  const ok = passes(licence);
  failed ||= !ok;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${p.name}@${p.version} ${licence ?? '(no licence)'}`);
}
if (packages.length === 0) {
  console.error('No production packages found: run `npm ci` first.');
  process.exit(1);
}
if (failed) {
  console.error('A shipped npm package has a licence ADR 0009 does not allow; ask before adding it.');
  process.exit(1);
}
