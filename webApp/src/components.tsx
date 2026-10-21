import { useEffect, type ReactNode } from 'react';
import { Icon, type IconName } from './icons';
import { useNav, useRoute } from './nav';
import { strings } from './strings';

// The web counterparts of core/designsystem's Page, Panel, Notice and state screens.

/** A page: a Back button on pages below a sidebar entry's page, the title (focused when the page opens) and its content. */
export function Page({ title, subtitle, actions, children }: {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  children?: ReactNode;
}) {
  const { hasBack } = useRoute();
  useDocumentTitle(title);
  return (
    <div className="page">
      {hasBack && <BackButton />}
      <div className="page-head">
        <div>
          <h1 id="page-title" tabIndex={-1}>{title}</h1>
          {subtitle && <p className="muted">{subtitle}</p>}
        </div>
        {actions}
      </div>
      {children}
    </div>
  );
}

function BackButton() {
  const nav = useNav();
  return (
    <button type="button" className="text-button back" onClick={() => nav.back()}>
      <Icon name="back" />
      {strings.navBack}
    </button>
  );
}

export function useDocumentTitle(title: string) {
  useEffect(() => {
    document.title = `${title} · ${strings.appName}`;
  }, [title]);
}

export function Panel({ children, className }: { children: ReactNode; className?: string }) {
  return <section className={className ? `panel ${className}` : 'panel'}>{children}</section>;
}

/** A message under a form. Errors and warnings are announced as they appear. */
export function Notice({ kind = 'error', children }: { kind?: 'error' | 'warning' | 'info'; children: ReactNode }) {
  return <p className={`notice notice-${kind}`} role={kind === 'info' ? undefined : 'alert'}>{children}</p>;
}

/** Something is on its way: a spinner and what is being waited for. */
export function Progress({ children }: { children: ReactNode }) {
  return (
    <p className="progress" role="status">
      <span className="spinner" aria-hidden="true" />
      {children}
    </p>
  );
}

/** Nothing to show yet: why, and what the user can do about it. The title is the page's title. */
export function EmptyState({ icon, title, message, action }: { icon: IconName; title: string; message: string; action?: ReactNode }) {
  useDocumentTitle(title);
  return (
    <div className="empty-state">
      <span className="empty-icon"><Icon name={icon} size={40} /></span>
      <h1 id="page-title" tabIndex={-1}>{title}</h1>
      <p className="muted">{message}</p>
      {action}
    </div>
  );
}
