import { type KeyboardEvent, type MouseEvent, type ReactNode } from 'react';
import type { CurrentWeb, RouteView } from 'current-core';
import { Page } from './components';
import { useObservable } from './hooks';
import { Icon, type IconName } from './icons';
import { Link, NavProvider } from './nav';
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

/**
 * The arrow keys move between the sidebar's entries and stop at the ends: Up and Down, and Left and Right at phone width, where the
 * sidebar is a bar. Tab leaves the sidebar.
 */
const arrowSteps: Record<string, number> = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 };

function arrowKeys(e: KeyboardEvent<HTMLElement>) {
  const stepBy = arrowSteps[e.key];
  if (!stepBy || e.altKey) return;
  const items = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('.sidebar-item'));
  const index = items.indexOf(document.activeElement as HTMLElement);
  if (index < 0) return;
  e.preventDefault();
  items[Math.max(0, Math.min(items.length - 1, index + stepBy))].focus();
}

function NotFound() {
  return (
    <Page title={webStrings.notFoundTitle}>
      <p className="muted">{webStrings.notFoundMessage} <Link path="/">{webStrings.notFoundHome}</Link></p>
    </Page>
  );
}
