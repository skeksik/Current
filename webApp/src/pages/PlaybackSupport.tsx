import { useEffect, useRef, useState } from 'react';
import type { PlaybackSupportPage, PlaybackSupportView } from 'current-core';
import { copyText, type CopyResult } from '../clipboard';
import { Page, Progress } from '../components';
import { useObservable, usePage } from '../hooks';
import { useCore } from '../nav';
import { webStrings } from '../strings';

export function PlaybackSupport() {
  const core = useCore();
  const page = usePage(() => core.playbackSupportPage(), [core]);
  return (
    <Page title={webStrings.playbackSupportTitle} subtitle={webStrings.playbackSupportSubtitle}>
      {page && <PlaybackSupportReport page={page} />}
    </Page>
  );
}

function PlaybackSupportReport({ page }: { page: PlaybackSupportPage }) {
  const state = useObservable(page.state);
  const answers = useRef<HTMLPreElement>(null);
  if (state.probing) return <Progress>{webStrings.playbackSupportProbing}</Progress>;
  return (
    <>
      <section>
        <h2>{webStrings.playbackSupportDirect}</h2>
        {state.direct.length === 0 ? <p className="muted">{webStrings.playbackSupportNoDirect}</p> : (
          <table>
            <thead>
              <tr>
                <th>{webStrings.playbackSupportContainer}</th>
                <th>{webStrings.playbackSupportVideo}</th>
                <th>{webStrings.playbackSupportAudio}</th>
              </tr>
            </thead>
            <tbody>
              {state.direct.map((c) => (
                <tr key={c.container}><td>{c.container}</td><td>{list(c.video)}</td><td>{list(c.audio)}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <section>
        <h2>{webStrings.playbackSupportVideo}</h2>
        <table>
          <thead><tr><th>{webStrings.playbackSupportCodec}</th><th>{webStrings.playbackSupportRanges}</th></tr></thead>
          <tbody>
            {state.ranges.map((r) => <tr key={r.codec}><td>{r.codec}</td><td>{list(r.ranges)}</td></tr>)}
          </tbody>
        </table>
        <Facts state={state} />
      </section>
      <details>
        <summary>{webStrings.playbackSupportAnswers}</summary>
        <p className="muted">{webStrings.playbackSupportAnswersHelp}</p>
        <CopyButton text={state.answersJson} fallback={answers} />
        <pre ref={answers}>{state.answersJson}</pre>
      </details>
    </>
  );
}

function Facts({ state }: { state: PlaybackSupportView }) {
  const hlsThrough = state.mse
    ? webStrings.playbackSupportHlsMse
    : state.nativeHls ? webStrings.playbackSupportHlsNative : webStrings.playbackSupportHlsNone;
  return (
    <dl>
      <dt>{webStrings.playbackSupportHdrDisplay}</dt>
      <dd>{state.hdrDisplay ? webStrings.playbackSupportYes : webStrings.playbackSupportNo}</dd>
      <dt>{webStrings.playbackSupportTenBit}</dt><dd>{list(state.tenBit)}</dd>
      <dt>{webStrings.playbackSupportUhd}</dt><dd>{list(state.uhd)}</dd>
      <dt>{webStrings.playbackSupportChannels}</dt><dd>{state.maxAudioChannels}</dd>
      <dt>{webStrings.playbackSupportHlsCopy}</dt>
      <dd>{webStrings.playbackSupportHlsCopyLists(list(state.hlsVideo), list(state.hlsAudio))}</dd>
      <dt>{webStrings.playbackSupportHlsThrough}</dt><dd>{hlsThrough}</dd>
    </dl>
  );
}

const copyMessages: Record<CopyResult, string> = {
  copied: webStrings.copyDone,
  selected: webStrings.copySelected,
  failed: webStrings.copyFailed,
};

/** Copies [text], or selects it in [fallback] where the clipboard is unavailable; the outcome is announced in a status line. */
function CopyButton({ text, fallback }: { text: string; fallback: { current: HTMLElement | null } }) {
  const [result, setResult] = useState<CopyResult | null>(null);
  // "Copied" fades after a while; the other results stay, since the user still has something to do.
  useEffect(() => {
    if (result !== 'copied') return;
    const timer = setTimeout(() => setResult(null), 4000);
    return () => clearTimeout(timer);
  }, [result]);
  const copy = () => {
    setResult(null);
    copyText(text, fallback.current).then(setResult);
  };
  return (
    <div className="copy">
      <button type="button" className="button outlined" onClick={copy}>{webStrings.copy}</button>
      <span className="muted" role="status">{result && copyMessages[result]}</span>
    </div>
  );
}

const list = (items: string[]) => (items.length ? items.join(', ') : webStrings.playbackSupportNone);
