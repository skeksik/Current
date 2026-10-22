import { useEffect, useRef, useState } from 'react';
import type { ServerRowView, SettingsPage } from 'current-core';
import { copyText, type CopyResult } from '../clipboard';
import { ConfirmRemoveDialog, Notice, Page, Panel } from '../components';
import { Icon } from '../icons';
import { useObservable, usePage } from '../hooks';
import { focusSoon, Link, useCore } from '../nav';
import { strings, textFor, webStrings } from '../strings';

/** Settings: servers (add, sign in, sign out, addresses, remove), where sign-ins are kept, what this browser plays, and about. */
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
      {/* An unknown store says nothing rather than something wrong. */}
      <Notice kind="info">{textFor(strings.settingsSecrets, state.secretStorage, '')}</Notice>
      {state.fileOffered && (
        <button type="button" className="button outlined" onClick={() => page.keepSecretsInFile()}>{strings.settingsSecretsKeepInFile}</button>
      )}

      <h2>{webStrings.settingsPlayback}</h2>
      <p>
        <Link path="/settings/playback" focusId="playback-support">{webStrings.settingsPlaybackSupport}</Link>
      </p>

      <h2>{strings.settingsAbout}</h2>
      <Panel>
        <h3 className="panel-title">{strings.appName}</h3>
        <p>{strings.settingsVersion(core.version)}</p>
      </Panel>
      <Diagnostics />

      {removing && (
        <ConfirmRemoveDialog
          title={strings.settingsRemoveTitle(removing.name)}
          message={strings.settingsRemoveMessage}
          confirm={() => {
            page.remove(removing.id);
            setRemoving(null);
            // The removed server's buttons are gone; focus goes to Add server rather than nowhere.
            focusSoon('[data-focus-id="add-server"]');
          }}
          cancel={() => {
            setRemoving(null);
            focusSoon(`[data-focus-id="${CSS.escape(removeFocusId(removing.id))}"]`);
          }}
        />
      )}
    </>
  );
}

/**
 * Copy diagnostics. The text is made at the click; without a clipboard (plain http) it is shown and selected for the user to copy, and
 * the outcome is announced in a status line, as on Playback support (`copyText`).
 */
function Diagnostics() {
  const core = useCore();
  // A new object per click, so the copy runs again even when the text is unchanged.
  const [request, setRequest] = useState<{ text: string } | null>(null);
  const [result, setResult] = useState<CopyResult | null>(null);
  const shown = useRef<HTMLPreElement>(null);
  // Copies once the text is rendered, so the fallback element holds it to select.
  useEffect(() => {
    if (request == null) return;
    let current = true;
    copyText(request.text, shown.current).then((it) => current && setResult(it));
    return () => {
      current = false;
    };
  }, [request]);
  // "Copied" fades after a while; the other results stay, since the user still has something to do.
  useEffect(() => {
    if (result !== 'copied') return;
    const timer = setTimeout(() => setResult(null), 4000);
    return () => clearTimeout(timer);
  }, [result]);
  const copy = () => {
    setResult(null);
    setRequest({ text: core.diagnostics() });
  };
  // Shown only where the user may have to copy it: no clipboard (plain http), or the browser refused.
  const shownText =
    request != null && (!navigator.clipboard?.writeText || result === 'selected' || result === 'failed') ? request.text : null;
  return (
    <>
      <p>{strings.settingsDiagnosticsHint}</p>
      <div className="copy">
        <button type="button" className="button outlined" onClick={copy}>{strings.settingsCopyDiagnostics}</button>
        <span className="muted" role="status">{result && diagnosticsMessages[result]}</span>
      </div>
      {shownText != null && <pre ref={shown}>{shownText}</pre>}
    </>
  );
}

const diagnosticsMessages: Record<CopyResult, string> = {
  copied: strings.settingsDiagnosticsCopied,
  selected: webStrings.copySelected,
  failed: webStrings.copyFailed,
};

const sessionFocusId = (id: string) => `session:${id}`;
const removeFocusId = (id: string) => `remove:${id}`;

function ServerPanel({ row, signOut, remove }: { row: ServerRowView; signOut: () => void; remove: () => void }) {
  return (
    <Panel className="server">
      <div className="server-text">
        <h3 className="panel-title">{row.name}</h3>
        <p className="muted">{row.address}</p>
        <p>{row.signedInAs != null ? strings.settingsSignedInAs(row.signedInAs) : strings.settingsSignedOut}</p>
        {row.offline && <Notice kind="warning">{strings.settingsOffline}</Notice>}
      </div>
      <div className="server-actions">
        {row.signedInAs != null ? (
          <button type="button" className="button outlined" data-focus-id={sessionFocusId(row.id)} onClick={signOut}>
            {strings.settingsSignOut}
          </button>
        ) : row.signInPath ? (
          <Link path={row.signInPath} className="button outlined" focusId={sessionFocusId(row.id)}>{strings.settingsSignIn}</Link>
        ) : null}
        <Link path={row.addressesPath} className="button outlined" focusId={`addresses:${row.id}`}>{strings.settingsAddresses}</Link>
        <button type="button" className="text-button danger" data-focus-id={removeFocusId(row.id)} onClick={remove}>
          {strings.settingsRemove}
        </button>
      </div>
    </Panel>
  );
}
