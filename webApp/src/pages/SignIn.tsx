import { useEffect, useRef } from 'react';
import type { SignInPage } from 'current-core';
import { Notice, Page, Panel, Progress } from '../components';
import { useObservable, usePage, useRerender } from '../hooks';
import { Link, useCore, useNav } from '../nav';
import { strings } from '../strings';

/** Sign in to a saved Jellyfin server with a password or Quick Connect; goes back to where it was opened from once signed in. */
export function SignIn({ path }: { path: string }) {
  const core = useCore();
  // One ViewModel per server: another server's sign-in page gets its own.
  const page = usePage(() => core.signInPage(path), [core, path]);
  if (!page) return null;
  if (page.serverName == null) {
    return (
      <Page title={strings.signInTitle('')}>
        <Notice>{strings.signInProblems.ServerNotSaved}</Notice>
        <p><Link path="/settings">{strings.navSettings}</Link></p>
      </Page>
    );
  }
  return (
    <Page title={strings.signInTitle(page.serverName)} subtitle={strings.signInSubtitle}>
      <SignInContent page={page} />
    </Page>
  );
}

function SignInContent({ page }: { page: SignInPage }) {
  const nav = useNav();
  const state = useObservable(page.state);
  useEffect(() => {
    if (state.kind === 'signedIn') nav.back();
  }, [state.kind, nav]);
  switch (state.kind) {
    case 'form': return <SignInForm page={page} />;
    case 'quickConnect': return <QuickConnectCode code={state.code ?? ''} cancel={() => page.cancelQuickConnect()} />;
    default: return null;
  }
}

function SignInForm({ page }: { page: SignInPage }) {
  // Read here rather than passed in, so the re-render after typing shows the new value.
  const form = useObservable(page.state);
  const rerender = useRerender();
  const username = useRef<HTMLInputElement>(null);
  const password = useRef<HTMLInputElement>(null);
  useEffect(() => username.current?.focus(), []);
  // Fields are disabled while signing in, which drops focus; after a failure it goes back to the password.
  useEffect(() => {
    if (form.problem) password.current?.focus();
  }, [form.problem]);

  return (
    <form
      className="form"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        page.signIn();
      }}
    >
      <label className="field">
        <span>{strings.signInUsername}</span>
        <input
          ref={username}
          name="username"
          autoComplete="username"
          autoCapitalize="off"
          spellCheck={false}
          value={form.username}
          disabled={form.working}
          onChange={(e) => {
            page.onUsername(e.target.value);
            rerender();
          }}
        />
      </label>
      <label className="field">
        <span>{strings.signInPassword}</span>
        <input
          ref={password}
          type="password"
          name="password"
          autoComplete="current-password"
          value={form.password}
          disabled={form.working}
          aria-invalid={form.problem === 'WrongCredentials' ? true : undefined}
          onChange={(e) => {
            page.onPassword(e.target.value);
            rerender();
          }}
        />
      </label>
      {form.problem && <Notice>{strings.signInProblems[form.problem]}</Notice>}
      {form.working ? (
        <Progress>{strings.signInWorking}</Progress>
      ) : (
        <div className="buttons">
          <button type="submit" className="button" disabled={form.username.trim() === ''}>{strings.signInSubmit}</button>
          {form.quickConnect && (
            <button type="button" className="button outlined" onClick={() => page.startQuickConnect()}>{strings.signInQuickConnect}</button>
          )}
        </div>
      )}
    </form>
  );
}

function QuickConnectCode({ code, cancel }: { code: string; cancel: () => void }) {
  const button = useRef<HTMLButtonElement>(null);
  useEffect(() => button.current?.focus(), []);
  return (
    <>
      <Panel>
        <p className="panel-title">{strings.signInQuickConnectTitle}</p>
        <p className="code">{code}</p>
        <p>{strings.signInQuickConnectMessage}</p>
        <Progress>{strings.signInQuickConnectWaiting}</Progress>
      </Panel>
      <div className="buttons">
        <button ref={button} type="button" className="button outlined" onClick={cancel}>{strings.signInQuickConnectCancel}</button>
      </div>
    </>
  );
}
