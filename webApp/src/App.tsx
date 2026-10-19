import { createContext, useContext, useEffect, useState, type MouseEvent, type ReactNode } from 'react';
import type { CurrentWeb, PlaybackSupportPage, PlaybackSupportView } from 'current-core';
import { useObservable, usePage } from './hooks';

const Core = createContext<CurrentWeb>(null!);
const useCore = () => useContext(Core);

const titles: Record<string, string> = { home: 'Home', playbackSupport: 'Playback support', notFound: 'Not found' };

export function App({ core }: { core: CurrentWeb }) {
  const route = useObservable(core.route);
  useEffect(() => {
    document.title = `${titles[route.kind] ?? ''} · Current`;
  }, [route.kind]);
  let page: ReactNode;
  switch (route.kind) {
    case 'home': page = <Home />; break;
    case 'playbackSupport': page = <PlaybackSupport />; break;
    default: page = <NotFound />;
  }
  return (
    <Core.Provider value={core}>
      <header className="header">
        <Link path="/" className="brand">Current</Link>
        <nav>
          <Link path="/settings/playback" current={route.kind === 'playbackSupport'}>Playback support</Link>
        </nav>
      </header>
      <main className="page">{page}</main>
    </Core.Provider>
  );
}

/** A real link: middle-click, new tab and copy link work; a plain click navigates inside the app. */
function Link({ path, className, current, children }: { path: string; className?: string; current?: boolean; children: ReactNode }) {
  const core = useCore();
  const onClick = (e: MouseEvent) => {
    if (core.onLinkClick(path, e.button, e.ctrlKey, e.metaKey, e.shiftKey, e.altKey)) e.preventDefault();
  };
  return <a href={path} className={className} aria-current={current ? 'page' : undefined} onClick={onClick}>{children}</a>;
}

function Home() {
  return (
    <>
      <h1>Home</h1>
      <p className="message">Nothing here yet: signing in to Jellyfin and browsing your libraries come next.</p>
    </>
  );
}

function NotFound() {
  return (
    <>
      <h1>Not found</h1>
      <p className="message">There is no page at this address. <Link path="/">Go home</Link></p>
    </>
  );
}

function PlaybackSupport() {
  const core = useCore();
  const page = usePage(() => core.playbackSupportPage(), [core]);
  return (
    <>
      <h1>Playback support</h1>
      <p className="message">What this browser plays itself, from its probes. The server remuxes or transcodes the rest.</p>
      {page && <PlaybackSupportReport page={page} />}
    </>
  );
}

function PlaybackSupportReport({ page }: { page: PlaybackSupportPage }) {
  const state = useObservable(page.state);
  if (state.probing) return <p className="message" role="status">Probing…</p>;
  return (
    <>
      <section>
        <h2>Direct play</h2>
        {state.direct.length === 0 ? <p className="message">No container plays directly.</p> : (
          <table>
            <thead><tr><th>Container</th><th>Video</th><th>Audio</th></tr></thead>
            <tbody>
              {state.direct.map((c) => (
                <tr key={c.container}><td>{c.container}</td><td>{list(c.video)}</td><td>{list(c.audio)}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <section>
        <h2>Video</h2>
        <table>
          <thead><tr><th>Codec</th><th>Dynamic ranges</th></tr></thead>
          <tbody>
            {state.ranges.map((r) => <tr key={r.codec}><td>{r.codec}</td><td>{list(r.ranges)}</td></tr>)}
          </tbody>
        </table>
        <Facts state={state} />
      </section>
      <details>
        <summary>Probe answers</summary>
        <p className="message">In the format of <code>docs/web-probes/</code>, to record this browser as a test fixture.</p>
        <CopyButton text={state.answersJson} />
        <pre>{state.answersJson}</pre>
      </details>
    </>
  );
}

function Facts({ state }: { state: PlaybackSupportView }) {
  return (
    <dl>
      <dt>HDR display</dt><dd>{state.hdrDisplay ? 'yes' : 'no'}</dd>
      <dt>10-bit</dt><dd>{list(state.tenBit)}</dd>
      <dt>2160p</dt><dd>{list(state.uhd)}</dd>
      <dt>Audio channels</dt><dd>{state.maxAudioChannels}</dd>
      <dt>Server HLS, copied</dt><dd>video {list(state.hlsVideo)}; audio {list(state.hlsAudio)}</dd>
      <dt>HLS through</dt><dd>{state.mse ? 'Media Source Extensions' : state.nativeHls ? 'the browser' : 'nothing'}</dd>
    </dl>
  );
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () => navigator.clipboard.writeText(text).then(() => setCopied(true), () => setCopied(false));
  return <button type="button" onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>;
}

const list = (items: string[]) => (items.length ? items.join(', ') : 'none');
