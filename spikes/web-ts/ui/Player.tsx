import { useEffect, useRef } from 'react';
import type { PlayerPage, TrackView } from 'current-core';
import { Link, useCore } from './App';
import { useObservable, usePage } from './hooks';

export function Player({ id }: { id: string }) {
  const core = useCore();
  const page = usePage(() => core.playerPage(id), [id]);
  const s = useObservable(page.state);
  const video = useRef<HTMLVideoElement>(null);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    page.attach(video.current!, box.current!);
    return () => page.detach();
  }, [page]);

  const status = s.error ?? (s.loading || s.buffering ? 'Loading…' : null);
  return (
    <div ref={box} className={s.controlsVisible ? 'player' : 'player idle'} onMouseMove={() => page.onActivity()}>
      <video ref={video} crossOrigin="anonymous" playsInline aria-label={s.title}
             onClick={() => page.togglePlay()} onDoubleClick={() => page.toggleFullscreen()} />
      {status && <div className={s.error ? 'status error' : 'status'} role="status">{status}</div>}
      {s.controlsVisible && (
        <>
          <div className="top">
            <Link path={`/items/${id}`} className="button">← Back</Link>
            <span>{s.title}</span>
            {s.method && <span className="badge">{s.method}</span>}
          </div>
          <div className="controls">
            <button aria-label={s.paused ? 'Play' : 'Pause'} onClick={() => page.togglePlay()}>{s.paused ? '▶' : '❚❚'}</button>
            <input className="seek" type="range" aria-label="Seek" min={0} max={Math.max(0, s.duration)} step={1}
                   value={s.position} aria-valuetext={`${s.positionText} of ${s.durationText}`}
                   onChange={e => page.seekTo(Number(e.currentTarget.value))} />
            <span className="time">{s.positionText} / {s.durationText}</span>
            <TrackMenu page={page} menu="Audio" label="Audio" open={s.openMenu} tracks={s.audio} onSelect={i => page.selectAudio(i)} />
            <TrackMenu page={page} menu="Subtitles" label="Subtitles" open={s.openMenu} tracks={s.subtitles} onSelect={i => page.selectSubtitle(i)} />
            <button aria-label="Fullscreen" onClick={() => page.toggleFullscreen()}>{s.fullscreen ? '⤡' : '⛶'}</button>
          </div>
        </>
      )}
    </div>
  );
}

function TrackMenu({ page, menu, label, open, tracks, onSelect }: {
  page: PlayerPage; menu: string; label: string; open?: string | null; tracks: TrackView[]; onSelect: (index: number) => void;
}) {
  const expanded = open === menu;
  return (
    <div className="menu-anchor">
      <button aria-haspopup="menu" aria-expanded={expanded} disabled={tracks.length === 0} onClick={() => page.toggleMenu(menu)}>{label}</button>
      {expanded && (
        <div className="menu" role="menu">
          {tracks.map(t => (
            <button key={t.index} role="menuitemradio" aria-checked={t.selected} onClick={() => onSelect(t.index)}>{t.label}</button>
          ))}
        </div>
      )}
    </div>
  );
}
