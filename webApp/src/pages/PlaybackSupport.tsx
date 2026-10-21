import { useState } from 'react';
import type { PlaybackSupportPage, PlaybackSupportView } from 'current-core';
import { Page, Progress } from '../components';
import { useObservable, usePage } from '../hooks';
import { useCore } from '../nav';
import { webStrings } from '../strings';

export function PlaybackSupport() {
  const core = useCore();
  const page = usePage(() => core.playbackSupportPage(), [core]);
  return (
    <Page
      title={webStrings.playbackSupportTitle}
      subtitle="What this browser plays itself, from its probes. The server remuxes or transcodes the rest."
    >
      {page && <PlaybackSupportReport page={page} />}
    </Page>
  );
}

function PlaybackSupportReport({ page }: { page: PlaybackSupportPage }) {
  const state = useObservable(page.state);
  if (state.probing) return <Progress>Probing…</Progress>;
  return (
    <>
      <section>
        <h2>Direct play</h2>
        {state.direct.length === 0 ? <p className="muted">No container plays directly.</p> : (
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
        <p className="muted">In the format of <code>docs/web-probes/</code>, to record this browser as a test fixture.</p>
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
  return <button type="button" className="button outlined" onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>;
}

const list = (items: string[]) => (items.length ? items.join(', ') : 'none');
