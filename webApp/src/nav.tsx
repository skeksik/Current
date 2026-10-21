import { createContext, useContext, useEffect, useMemo, useRef, type MouseEvent, type ReactNode } from 'react';
import type { CurrentWeb, RouteView } from 'current-core';

// Navigation as the desktop shell does it (docs/architecture.md): every page opened is a history step (here the browser's), and any
// Back goes one step back. Focus follows: a page opened gets focus on its title, and a page shown again by Back gets it on what opened
// the page that left (the server's Sign in button, Add server), which the router keeps in that page's history entry.

const Core = createContext<CurrentWeb>(null!);
export const useCore = () => useContext(Core);

const Route = createContext<RouteView>(null!);
export const useRoute = () => useContext(Route);

interface Nav {
  /** Opens `path` as a new step; `opener` is the `data-focus-id` that gets focus again when Back returns to this page. */
  open(path: string, opener?: string): void;
  /** For a link's click: navigates for a plain click (and prevents the browser's), leaves any other click to the browser. */
  follow(event: MouseEvent, path: string, opener?: string): void;
  replace(path: string): void;
  back(): void;
}

const NavContext = createContext<Nav>(null!);
export const useNav = () => useContext(NavContext);

export function NavProvider({ core, route, children }: { core: CurrentWeb; route: RouteView; children: ReactNode }) {
  const nav = useMemo<Nav>(() => ({
    open(path, opener) {
      core.open(path, opener ?? null);
    },
    follow(event, path, opener) {
      if (core.onLinkClick(path, opener ?? null, event.button, event.ctrlKey, event.metaKey, event.shiftKey, event.altKey)) {
        event.preventDefault();
      }
    },
    replace(path) {
      core.replace(path);
    },
    back() {
      core.back();
    },
  }), [core]);

  // Every navigation is a new route view. The first one keeps the browser's own focus; so does StrictMode's second run of this effect.
  const shown = useRef(route);
  useEffect(() => {
    if (shown.current === route) return;
    shown.current = route;
    focusSoon(route.opener ? `[data-focus-id="${CSS.escape(route.opener)}"]` : null);
  }, [route]);

  return (
    <Core.Provider value={core}>
      <Route.Provider value={route}>
        <NavContext.Provider value={nav}>{children}</NavContext.Provider>
      </Route.Provider>
    </Core.Provider>
  );
}

/**
 * Focuses `selector`, else the page title, once the page has drawn it: a page creates its ViewModel in an effect, so its content
 * appears a frame or two after the route changes.
 */
export function focusSoon(selector: string | null, frames = 10) {
  // A page that put focus somewhere itself (a form's first field) has the last word over its title.
  if (!selector && frames < 10 && document.getElementById('main')?.contains(document.activeElement)) return;
  const wanted = selector ? document.querySelector<HTMLElement>(selector) : document.getElementById('page-title');
  if (wanted) {
    wanted.focus();
  } else if (frames > 0) {
    requestAnimationFrame(() => focusSoon(selector, frames - 1));
  } else {
    document.getElementById('page-title')?.focus();
  }
}

/**
 * A real link: middle-click, new tab and copy link work; a plain click navigates inside the app. `focusId` names it, so focus comes
 * back to it after Back from the page it opened.
 */
export function Link({ path, className, current, focusId, children }: {
  path: string;
  className?: string;
  current?: boolean;
  focusId?: string;
  children: ReactNode;
}) {
  const nav = useNav();
  return (
    <a
      href={path}
      className={className}
      aria-current={current ? 'page' : undefined}
      data-focus-id={focusId}
      onClick={(e) => nav.follow(e, path, focusId)}
    >
      {children}
    </a>
  );
}
