import { useEffect, useRef } from 'react';
import type { AddServerPage } from 'current-core';
import { Notice, Page, Panel, Progress } from '../components';
import { useObservable, usePage, useRerender } from '../hooks';
import { useCore, useNav } from '../nav';
import { strings } from '../strings';

/** Settings > Servers > Add server. Once saved, the sign-in page for the new server takes this page's place. */
export function AddServer() {
  const core = useCore();
  const page = usePage(() => core.addServerPage(), [core]);
  return (
    <Page title={strings.addServerTitle} subtitle={strings.addServerSubtitle}>
      {page && <AddServerForm page={page} />}
    </Page>
  );
}

function AddServerForm({ page }: { page: AddServerPage }) {
  const nav = useNav();
  const state = useObservable(page.state);
  const rerender = useRerender();
  const field = useRef<HTMLInputElement>(null);
  const confirm = useRef<HTMLButtonElement>(null);

  useEffect(() => field.current?.focus(), []);
  // After a failure focus goes back to the address.
  useEffect(() => {
    if (state.problem) field.current?.focus();
  }, [state.problem]);
  // The user reads what was found and confirms it themselves: Enter in the field never adds a server.
  useEffect(() => {
    if (state.kind === 'found') confirm.current?.focus();
  }, [state.kind, state.address]);
  useEffect(() => {
    if (state.kind !== 'saved') return;
    // Only Jellyfin has a sign-in page yet; any other service goes back to where Add server was opened.
    if (state.nextPath) nav.replace(state.nextPath);
    else nav.back();
  }, [state.kind, state.nextPath, nav]);

  return (
    <form
      className="form"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        if (page.state.getSnapshot().kind === 'editing') page.check();
      }}
    >
      <label className="field">
        <span>{strings.addServerAddress}</span>
        <input
          ref={field}
          type="url"
          inputMode="url"
          autoComplete="url"
          autoCapitalize="off"
          spellCheck={false}
          value={state.input}
          aria-invalid={state.problem ? true : undefined}
          onChange={(e) => {
            page.onInput(e.target.value);
            // The ViewModel's state changed already; drawing it now keeps the caret where it is.
            rerender();
          }}
        />
      </label>
      {state.kind === 'editing' && (
        <>
          {state.problem && <Notice>{strings.addServerProblems[state.problem]}</Notice>}
          <div className="buttons">
            <button type="submit" className="button" disabled={state.input.trim() === ''}>{strings.addServerCheck}</button>
          </div>
        </>
      )}
      {state.kind === 'checking' && <Progress>{strings.addServerChecking}</Progress>}
      {state.kind === 'found' && (
        <>
          <Panel>
            <p className="panel-title">{strings.addServerFound(state.name ?? '')}</p>
            {state.version && <p>{strings.addServerVersion(state.version)}</p>}
            <p>{state.address}</p>
            <Notice kind="info">{state.local ? strings.addServerLocal : strings.addServerInternet}</Notice>
          </Panel>
          {state.plainHttpOnInternet && <Notice kind="warning">{strings.addServerPlainHttp}</Notice>}
          <div className="buttons">
            <button ref={confirm} type="button" className="button" onClick={() => page.confirm()}>{strings.addServerConfirm}</button>
            <button type="button" className="button outlined" onClick={() => field.current?.focus()}>{strings.addServerChange}</button>
          </div>
        </>
      )}
    </form>
  );
}
