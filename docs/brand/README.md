# Brand

`current-icon.svg` is the app icon's source: three waves in the line-icon style (`CurrentIcons`, `webApp/src/icons.tsx`), in the
accent on the dark background token. It is a placeholder the owner may replace; every app's icons are drawn from it.

- Web (`webApp/public`): `favicon.svg` is a copy; `icon-192.png`, `icon-512.png` (rounded corners, transparent outside),
  `icon-maskable-512.png` and `apple-touch-icon.png` (180 px; full bleed, the platform masks them) are rendered from it at those sizes in
  a browser. The waves stay inside the maskable safe zone, a centred circle of 40% radius.

Re-render the PNGs whenever the SVG changes.
