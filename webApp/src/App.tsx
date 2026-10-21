import { useEffect, type KeyboardEvent, type MouseEvent, type ReactNode } from 'react';
import type { CurrentWeb, RouteView } from 'current-core';
import { Page } from './components';
import { useObservable } from './hooks';
import { Icon, type IconName } from './icons';
import { Link, NavProvider, useNav } from './nav';
import { AddServer } from './pages/AddServer';
import { Home } from './pages/Home';
import { PlaybackSupport } from './pages/PlaybackSupport';
import { Settings } from './pages/Settings';
import { SignIn } from './pages/SignIn';
import { strings, webStrings } from './strings';

/** The sidebar's entries, top to bottom; `owns` tells which pages highlight an entry. */
const sidebar: { label: string; icon: IconName; path: string; owns: string[] }[] = [
  { label: strings.navHome, icon: 'home', path: '/', owns: ['home'] },
  { label: strings.navSettings, icon: 'settings', path: '/settings', owns: ['settings', 'addServer', 'signIn', 'playbackSupport'] },
];

/** The desktop shell's layout: the sidebar on the left, the current page beside it (on narrow windows, the sidebar above it). */
export function App({ core }: { core: CurrentWeb }) {
  const route = useObservable(core.route);
  return (
    <NavProvider core={core} route={route}>
      <Shell route={route} />
    </NavProvider>
  );
}

function Shell({ route }: { route: RouteView }) {
  const nav = useNav();
  // Escape goes back on every page, as on desktop; Alt+Left and the browser's Back are the browser's own.
  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key !== 'Escape' || e.defaultPrevented || e.isComposing || document.querySelector('dialog[open]')) return;
      nav.back();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [nav]);

  let page: ReactNode;
  switch (route.kind) {
    case 'home': page = <Home />; break;
    case 'settings': page = <Settings />; break;
    case 'addServer': page = <AddServer />; break;
    case 'signIn': page = <SignIn path={route.path} />; break;
    case 'playbackSupport': page = <PlaybackSupport />; break;
    default: page = <NotFound />;
  }
  return (
    <div className="shell">
      {/* Moves focus without touching the URL: a fragment would add a history entry the router does not count. */}
      <a className="skip-link" href="#main" onClick={skipToContent}>{webStrings.skipToContent}</a>
      <nav className="sidebar" aria-label={strings.appName} onKeyDown={arrowKeys}>
        <p className="sidebar-title">{strings.appName}</p>
        <ul>
          {sidebar.map((entry) => (
            <li key={entry.path}>
              <Link path={entry.path} className="sidebar-item" current={entry.owns.includes(route.kind)} focusId={`nav:${entry.path}`}>
                <Icon name={entry.icon} />
                {entry.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      {/* Keyed by path, so each page starts afresh, as a new back stack entry does on desktop. */}
      <main id="main" className="main" tabIndex={-1} key={route.path}>{page}</main>
    </div>
  );
}

function skipToContent(e: MouseEvent) {
  e.preventDefault();
  (document.getElementById('page-title') ?? document.getElementById('main'))?.focus();
}

/** Up and Down move between the sidebar's entries and stop at the ends; Tab leaves the sidebar. */
function arrowKeys(e: KeyboardEvent<HTMLElement>) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
  const items = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('.sidebar-item'));
  const index = items.indexOf(document.activeElement as HTMLElement);
  if (index < 0) return;
  e.preventDefault();
  items[Math.max(0, Math.min(items.length - 1, index + (e.key === 'ArrowDown' ? 1 : -1)))].focus();
}

function NotFound() {
  return (
    <Page title={webStrings.notFoundTitle} hasBack={false}>
      <p className="muted">{webStrings.notFoundMessage} <Link path="/">{webStrings.notFoundHome}</Link></p>
    </Page>
  );
}
