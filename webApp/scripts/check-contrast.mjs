// WCAG AA for the CSS colour tokens in src/styles.css, dark and light: the same pairs as ContrastTest in core:designsystem.
// Text 4.5:1 and borders 3:1 against every background they sit on. Run with `npm run contrast`.
import { readFileSync } from 'node:fs';

const css = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8');

/** The `--name: #rrggbb` tokens of the first `:root { ... }` block found after `marker`. */
function tokens(marker) {
  const start = css.indexOf(marker);
  if (start < 0) throw new Error(`styles.css: ${marker} not found`);
  const block = css.slice(css.indexOf(':root', start), css.indexOf('}', start));
  return Object.fromEntries([...block.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6})\b/gi)].map(([, name, hex]) => [name, hex]));
}

const dark = tokens('/* The desktop shell');
const themes = { dark, light: { ...dark, ...tokens('@media (prefers-color-scheme: light)') } };

function luminance(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a, b) {
  const [low, high] = [luminance(a), luminance(b)].sort((x, y) => x - y);
  return (high + 0.05) / (low + 0.05);
}

const backgrounds = { page: 'background', sidebar: 'surface', panel: 'surface-raised' };
const checks = [
  ...['text', 'text-muted', 'primary', 'error', 'warning'].flatMap((text) =>
    Object.entries(backgrounds).map(([on, background]) => [text, background, on, 4.5])),
  ...Object.entries(backgrounds).map(([on, background]) => ['outline', background, on, 3]),
  ['on-selected', 'selected', 'selected entry', 4.5],
  ['primary', 'selected', 'selected entry (icon)', 3],
  ['on-primary', 'primary', 'button', 4.5],
];

let failed = false;
for (const [theme, colors] of Object.entries(themes)) {
  for (const [name, background, on, minimum] of checks) {
    if (!colors[name] || !colors[background]) throw new Error(`${theme}: --${name} or --${background} is not a #rrggbb token`);
    const ratio = contrast(colors[name], colors[background]);
    const ok = ratio >= minimum;
    failed ||= !ok;
    if (!ok) console.log(`FAIL ${theme}: --${name} on ${on}: ${ratio.toFixed(2)}:1, needs ${minimum}:1`);
  }
}
console.log(failed ? 'Contrast check failed.' : `Contrast check passed: ${checks.length} pairs in dark and light.`);
process.exit(failed ? 1 : 0);
