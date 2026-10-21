import { useEffect, useRef, useState } from 'react';
import type { ServerRowView, SettingsPage } from 'current-core';
import { copyText } from '../clipboard';
import { Notice, Page, Panel } from '../components';
import { Icon } from '../icons';
import { useObservable, usePage } from '../hooks';
import { focusSoon, Link, useCore } from '../nav';
import { strings, webStrings } from '../strings';

/** Settings: servers (add, sign in, sign out, remove), where sign-ins are kept, what this browser plays, and about. */
export function Settings() {
  const core = useCore();
  const page = usePage(() => core.settingsPage(), [core]);
  return (
    <Page title={strings.settingsTitle}>
      {page && <SettingsContent page={page} />}
    </Page>
  );
}

function SettingsContent({ page }: { page: SettingsPage }) {
  const core = useCore();
  const state = useObservable(page.state);
  const [removing, setRemoving] = useState<ServerRowView | null>(null);
  // Signing out turns the server's Sign out button into a Sign in link: once it has, focus moves to the new element.
  const [signingOut, setSigningOut] = useState<string | null>(null);
  useEffect(() => {
    if (!signingOut) return;
    const row = state.servers.find((it) => it.id === signingOut);
    if (row?.signedInAs != null) return;
    if (row) focusSoon(`[data-focus-id="${CSS.escape(sessionFocusId(row.id))}"]`);
    setSigningOut(null);
  }, [state, signingOut]);

  return (
    <>
      <div className="section-head">
        <h2>{strings.settingsServers}</h2>
        <Link path="/settings/servers/add" className="button" focusId="add-server">
          <Icon name="add" />
          {strings.settingsAddServer}
        </Link>
      </div>
      {state.servers.length === 0 ? <Notice kind="info">{strings.settingsNoServers}</Notice> : (
        <ul className="server-list">
          {state.servers.map((row) => (
            <li key={row.id}>
              <ServerPanel
                row={row}
                signOut={() => {
                  page.signOut(row.id);
                  setSigningOut(row.id);
                }}
                remove={() => setRemoving(row)}
              />
            </li>
          ))}
        </ul>
      )}
      <Notice kind="info">{strings.settingsSecrets[state.secretStorage] ?? ''}</Notice>
      {state.fileOffered && (
        <button type="button" className="button outlined" onClick={() => page.keepSecretsInFile()}>{strings.settingsSecretsKeepInFile}</button>
      )}

      <h2>{webStrings.settingsPlayback}</h2>
      <p>
        <Link path="/settings/playback" focusId="playback-support">{webStrings.settingsPlaybackSupport}</Link>
      </p>

      <h2>{strings.settingsAbout}</h2>
      <Panel>
        <p className="panel-title">{strings.appName}</p>
        <p>{strings.settingsVersion(core.version)}</p>
      </Panel>
      <Diagnostics />

      {removing && (
        <RemoveDialog
          row={removing}
          confirm={() => {
            page.remove(removing.id);
            setRemoving(null);
            // The removed server's buttons are gone; focus goes to Add server rather than nowhere.
            focusSoon('[data-focus-id="add-server"]');
          }}
          dismiss={() => setRemoving(null)}
        />
      )}
    </>
  );
}

/** Copy diagnostics; without a clipboard (plain http), the text is shown selected, to copy by hand. */
function Diagnostics() {
  const core = useCore();
  const [copied, setCopied] = useState(false);
  const [byHand, setByHand] = useState<string | null>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (byHand == null) return;
    field.current?.focus();
    field.current?.select();
  }, [byHand]);
  // Cleared after a while, and at the next click, so a second copy is confirmed again.
  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 3000);
    return () => window.clearTimeout(timer);
  }, [copied]);
  const copy = async () => {
    setCopied(false);
    const text = core.diagnostics();
    const done = await copyText(text);
    setCopied(done);
    setByHand(done ? null : text);
  };
  return (
    <>
      <p>{strings.settingsDiagnosticsHint}</p>
      <p>
        <button type="button" className="button outlined" onClick={copy}>{strings.settingsCopyDiagnostics}</button>
        {copied && <span role="status"> {strings.settingsDiagnosticsCopied}</span>}
      </p>
      {byHand != null && (
        <>
          <Notice kind="info">{webStrings.settingsDiagnosticsByHand}</Notice>
          <textarea
            ref={field}
            className="diagnostics"
            readOnly
            rows={10}
            value={byHand}
            aria-label={webStrings.settingsDiagnosticsLabel}
          />
        </>
      )}
    </>
  );
}

const sessionFocusId = (id: string) => `session:${id}`;
const removeFocusId = (id: string) => `remove:${id}`;

function ServerPanel({ row, signOut, remove }: { row: ServerRowView; signOut: () => void; remove: () => void }) {
  return (
    <Panel className="server">
      <div className="server-text">
        <p className="panel-title">{row.name}</p>
        <p className="muted">{row.address}</p>
        <p>{row.signedInAs != null ? strings.settingsSignedInAs(row.signedInAs) : strings.settingsSignedOut}</p>
        {row.offline && <Notice kind="warning">{strings.settingsOffline}</Notice>}
      </div>
      {/* Address editing will add its entry here, beside the session and Remove buttons. */}
      <div className="server-actions">
        {row.signedInAs != null ? (
          <button type="button" className="button outlined" data-focus-id={sessionFocusId(row.id)} onClick={signOut}>
            {strings.settingsSignOut}
          </button>
        ) : row.signInPath ? (
          <Link path={row.signInPath} className="button outlined" focusId={sessionFocusId(row.id)}>{strings.settingsSignIn}</Link>
        ) : null}
        <button type="button" className="text-button danger" data-focus-id={removeFocusId(row.id)} onClick={remove}>
          {strings.settingsRemove}
        </button>
      </div>
    </Panel>
  );
}

/**
 * Asks before removing. Cancel has focus, so Enter alone never removes a server; Escape cancels and focus goes back to the server's
 * Remove button (by its id: after a click, Safari and Firefox on macOS leave focus on the page rather than the button).
 */
function RemoveDialog({ row, confirm, dismiss }: { row: ServerRowView; confirm: () => void; dismiss: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const shown = dialog.current;
    shown?.showModal();
    return () => shown?.close();
  }, []);
  // The page behind a modal dialog is inert, so the dialog closes before focus moves there.
  const close = () => dialog.current?.close();
  const cancel = () => {
    close();
    dismiss();
    focusSoon(`[data-focus-id="${CSS.escape(removeFocusId(row.id))}"]`);
  };
  return (
    <dialog
      ref={dialog}
      className="dialog"
      aria-labelledby="remove-title"
      aria-describedby="remove-message"
      onCancel={(e) => {
        e.preventDefault();
        cancel();
      }}
    >
      <h2 id="remove-title">{strings.settingsRemoveTitle(row.name)}</h2>
      <p id="remove-message">{strings.settingsRemoveMessage}</p>
      <div className="dialog-buttons">
        <button type="button" className="text-button" autoFocus onClick={cancel}>{strings.settingsCancel}</button>
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
