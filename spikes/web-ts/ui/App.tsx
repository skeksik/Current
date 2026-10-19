import { createContext, useContext, type FormEvent, type MouseEvent, type ReactNode } from 'react';
import type { CardView, CurrentWeb } from 'current-core';
import { useObservable, usePage } from './hooks';
import { Player } from './Player';

const Core = createContext<CurrentWeb>(null!);
export const useCore = () => useContext(Core);

export function App({ core }: { core: CurrentWeb }) {
  const route = useObservable(core.route);
  let page: ReactNode;
  switch (route.kind) {
    case 'signIn': page = <SignIn />; break;
    case 'home': page = <Shell><Home /></Shell>; break;
    case 'library': page = <Shell><LibraryPage id={route.id!} key={route.id} /></Shell>; break;
    case 'item': page = <Shell><ItemPage id={route.id!} key={route.id} /></Shell>; break;
    case 'player': page = <Player id={route.id!} key={route.id} />; break;
    default: page = <Shell><p className="message">Nothing here.</p></Shell>;
  }
  return <Core.Provider value={core}>{page}</Core.Provider>;
}

/** A real link: middle-click, new tab and copy link work; a plain click navigates inside the app. */
export function Link({ path, className, children }: { path: string; className?: string; children: ReactNode }) {
  const core = useCore();
  const onClick = (e: MouseEvent) => {
    if (core.onLinkClick(path, e.button, e.ctrlKey, e.metaKey, e.shiftKey, e.altKey)) e.preventDefault();
  };
  return <a href={path} className={className} onClick={onClick}>{children}</a>;
}

function Shell({ children }: { children: ReactNode }) {
  const core = useCore();
  const session = useObservable(core.session);
  return (
    <>
      <div className="header">
        <Link path="/" className="brand">Current</Link>
        <span className="spacer" />
        <span className="user">{session?.userName}</span>
        <button onClick={() => core.signOut()}>Sign out</button>
      </div>
      <main className="page">{children}</main>
    </>
  );
}

function SignIn() {
  const core = useCore();
  const page = usePage(() => core.signInPage(), []);
  const state = useObservable(page.state);
  const onSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    page.signIn(String(f.get('server')), String(f.get('username')), String(f.get('password')));
  };
  return (
    <form className="sign-in" method="post" onSubmit={onSubmit}>
      <h1>Sign in to Jellyfin</h1>
      <label>Server
        <input type="url" name="server" autoComplete="url" placeholder="https://jellyfin.example.org" defaultValue={state.serverUrl} required />
      </label>
      <label>Username
        <input type="text" name="username" autoComplete="username" required />
      </label>
      <label>Password
        <input type="password" name="password" autoComplete="current-password" />
      </label>
      {state.error && <p className="error" role="alert">{state.error}</p>}
      <button type="submit" className="primary" disabled={state.busy}>{state.busy ? 'Signing in…' : 'Sign in'}</button>
    </form>
  );
}

function Status({ loading, error }: { loading: boolean; error?: string | null }) {
  if (error) return <p className="error" role="alert">{error}</p>;
  return loading ? <p className="message">Loading…</p> : null;
}

function Grid({ cards }: { cards: CardView[] }) {
  return (
    <div className="grid">
      {cards.map(card => (
        <Link key={card.id} path={card.path} className="card">
          <img className={card.wide ? 'poster wide' : 'poster'} src={card.imageUrl ?? ''} alt="" loading="lazy" />
          <span className="title">{card.title}</span>
          {card.sub && <span className="sub">{card.sub}</span>}
        </Link>
      ))}
    </div>
  );
}

function Home() {
  const core = useCore();
  const page = usePage(() => core.homePage(), []);
  const state = useObservable(page.state);
  return <><h1>{state.title}</h1><Status loading={state.loading} error={state.error} /><Grid cards={state.cards} /></>;
}

function LibraryPage({ id }: { id: string }) {
  const core = useCore();
  const page = usePage(() => core.libraryPage(id), [id]);
  const state = useObservable(page.state);
  return <><h1>{state.title}</h1><Status loading={state.loading} error={state.error} /><Grid cards={state.cards} /></>;
}

function ItemPage({ id }: { id: string }) {
  const core = useCore();
  const page = usePage(() => core.itemPage(id), [id]);
  const s = useObservable(page.state);
  if (s.loading || s.error) return <Status loading={s.loading} error={s.error} />;
  return (
    <>
      <div className="item">
        {s.imageUrl && <img className="poster" src={s.imageUrl} alt="" />}
        <div>
          <h1>{s.title}</h1>
          <p className="meta">{s.meta}</p>
          {s.playPath && <p className="actions"><Link path={s.playPath} className="button primary">▶ Play</Link></p>}
          {s.overview && <p className="overview">{s.overview}</p>}
        </div>
      </div>
      {s.children.length > 0 && <Grid cards={s.children} />}
    </>
  );
}
