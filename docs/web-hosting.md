# Hosting the web app

`npm --prefix webApp run build` writes a static site to `webApp/build/vite`: `index.html`, hashed files under `assets/`, the icons and
`manifest.webmanifest`. Any static host serves it. This note says what the host must add; deploying it is not set up yet.

## SPA fallback
Every page has its own URL (ADR 0011), and a reload or a shared link asks the host for that path. The host must answer every unknown
path with `index.html` and status 200, so the app's router shows the page (or its own Not found). Keep real 404s for `/assets/*` and
other files, so a stale hashed file fails loudly instead of loading `index.html` as a script. `vite preview` does the same.

## Response headers
Send these with `index.html` (and harmlessly with everything else):

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; connect-src 'self' http: https:; media-src 'self' http: https: blob:; img-src 'self' http: https: data:; frame-ancestors 'none'
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
```

- Why it is strict: sign-ins are kept in `localStorage`, which any script on the origin can read (ADR 0017). The policy is the condition
  for keeping them there, so no inline or foreign script may run.
- Why `connect-src`, `media-src` and `img-src` are open to `http:` and `https:`: the user picks their own servers, at any address.
  `blob:` is for the player's Media Source Extensions; `data:` for small inlined images.
- `frame-ancestors 'none'` keeps the app out of other sites' frames (clickjacking). It works only as a header: the CSP specification ignores it in a
  `<meta>` policy.
- `Referrer-Policy: no-referrer` keeps the app's address out of the user's servers' logs.

The production `index.html` already carries the same policy without `frame-ancestors`, as `<meta http-equiv="Content-Security-Policy">`
(added by `webApp/vite.config.ts`), and `<meta name="referrer" content="no-referrer">`. They protect the app on hosts that cannot set
headers; the headers add framing protection. Change the policy in both places together.

The dev server (`npm run dev`) has no policy: Vite injects inline code for React Fast Refresh, which `script-src 'self'` would block.

## https and plain-HTTP servers
From an https page, browsers block requests to `http://` servers (mixed content), and ask before a public page reaches the local
network (docs/platform-notes.md, "Local network and mixed content: browsers"). A host on the local network over plain http avoids both,
at the cost of the clipboard and other secure-context APIs.
