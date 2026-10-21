import { useEffect, useId, useRef, type ReactNode } from 'react';
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

/**
 * A message under a form. Only errors, which the user's last action caused, are announced as they appear (`role="alert"`). Warnings and
 * information are part of the page: an assertive announcement for a state the user did not just cause (Offline) would interrupt them.
 * A warning that must be heard goes into the description of the control it concerns (`id` and `aria-describedby`).
 */
export function Notice({ kind = 'error', id, children }: { kind?: 'error' | 'warning' | 'info'; id?: string; children: ReactNode }) {
  return <p id={id} className={`notice notice-${kind}`} role={kind === 'error' ? 'alert' : undefined}>{children}</p>;
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

/**
 * Asks before removing something. Cancel has focus, so Enter alone never removes; Escape cancels. [cancel] puts focus back on what
 * opened the dialog, by its id: after a click, Safari and Firefox on macOS leave focus on the page rather than the button.
 */
export function ConfirmRemoveDialog({ title, message, confirm, cancel }: {
  title: string;
  message: string;
  confirm: () => void;
  cancel: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancelButton = useRef<HTMLButtonElement>(null);
  const id = useId();
  useEffect(() => {
    const shown = dialog.current;
    shown?.showModal();
    // Explicitly, not by button order: showModal() focuses the first focusable element, and React's autoFocus runs before it.
    cancelButton.current?.focus();
    return () => shown?.close();
  }, []);
  // The page behind a modal dialog is inert, so the dialog closes before focus moves there.
  const close = () => dialog.current?.close();
  const dismiss = () => {
    close();
    cancel();
  };
  return (
    <dialog
      ref={dialog}
      className="dialog"
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-message`}
      onCancel={(e) => {
        e.preventDefault();
        dismiss();
      }}
    >
      <h2 id={`${id}-title`}>{title}</h2>
      <p id={`${id}-message`}>{message}</p>
      <div className="dialog-buttons">
        <button ref={cancelButton} type="button" className="text-button" onClick={dismiss}>{strings.settingsCancel}</button>
        <button
          type="button"
          className="text-button danger"
          onClick={() => {
            close();
            confirm();
          }}
        >
          {strings.settingsRemove}
        </button>
      </div>
    </dialog>
  );
}
