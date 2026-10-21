import { useEffect, useRef, useState } from 'react';
import type { AddressRowView, ServerAddressesPage } from 'current-core';
import { ConfirmRemoveDialog, Notice, Page, Panel } from '../components';
import { Icon } from '../icons';
import { useObservable, usePage } from '../hooks';
import { focusSoon, Link, useCore, useNav } from '../nav';
import { strings, textFor, webStrings } from '../strings';

/** Settings > Servers > Addresses: a server's addresses in priority order, the one in use, and adding, removing and reordering them. */
export function ServerAddresses({ path }: { path: string }) {
  const core = useCore();
  const page = usePage(() => core.serverAddressesPage(path), [core, path]);
  // Read once: a server that is gone when the page opens gets a page that says so; one removed while it is open leaves it.
  const savedAtOpen = useRef<boolean | null>(null);
  if (!page) return null;
  savedAtOpen.current ??= page.state.getSnapshot().saved;
  if (!savedAtOpen.current) {
    return (
      <Page title={webStrings.signInServerMissingTitle}>
        <Notice>{strings.addAddressServerNotSaved}</Notice>
        <p><Link path="/settings">{strings.navSettings}</Link></p>
      </Page>
    );
  }
  return <AddressesContent page={page} />;
}

type Pending =
  | { kind: 'moved'; url: string; up: boolean; before: string[] }
  | { kind: 'removed'; url: string; index: number };

const upFocusId = (url: string) => `up:${url}`;
const downFocusId = (url: string) => `down:${url}`;
const removeFocusId = (url: string) => `remove:${url}`;
const byFocusId = (id: string) => `[data-focus-id="${CSS.escape(id)}"]`;

function AddressesContent({ page }: { page: ServerAddressesPage }) {
  const nav = useNav();
  const state = useObservable(page.state);
  const [removing, setRemoving] = useState<AddressRowView | null>(null);
  // Where focus goes once a move or a removal shows in the list: a moved row's button can be disabled at its new place, and a removed
  // row's buttons are gone.
  const [pending, setPending] = useState<Pending | null>(null);
  const urls = state.addresses.map((it) => it.url);

  useEffect(() => {
    if (!state.saved) nav.back();
  }, [state.saved, nav]);

  useEffect(() => {
    if (!pending) return;
    if (pending.kind === 'moved') {
      // Waits for the new order.
      if (urls.join('\n') === pending.before.join('\n')) return;
      const index = urls.indexOf(pending.url);
      if (index >= 0) {
        // The same button while it still moves the row; at either end, the other one.
        const up = pending.up ? index > 0 : index === urls.length - 1;
        focusSoon(byFocusId(up ? upFocusId(pending.url) : downFocusId(pending.url)));
      }
    } else {
      if (urls.includes(pending.url)) return;
      // The row that took its place, or the new last one; Add address when no row has buttons left.
      const index = Math.min(pending.index, urls.length - 1);
      const next = urls[index];
      if (urls.length > 1 && next) focusSoon(byFocusId(index > 0 ? upFocusId(next) : downFocusId(next)));
      else focusSoon(byFocusId('add-address'));
    }
    setPending(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, pending]);

  return (
    <Page
      title={strings.addressesTitle}
      subtitle={strings.addressesSubtitle(state.name)}
      actions={
        <Link path={state.addPath} className="button" focusId="add-address">
          <Icon name="add" />
          {strings.addressesAdd}
        </Link>
      }
    >
      {state.unreachable && <Notice kind="warning">{strings.addressesUnreachable(state.name)}</Notice>}
      <ol className="server-list">
        {state.addresses.map((row, index) => (
          <li key={row.url}>
            <AddressPanel
              row={row}
              index={index}
              count={state.addresses.length}
              moveUp={() => {
                setPending({ kind: 'moved', url: row.url, up: true, before: urls });
                page.moveUp(row.url);
              }}
              moveDown={() => {
                setPending({ kind: 'moved', url: row.url, up: false, before: urls });
                page.moveDown(row.url);
              }}
              remove={() => setRemoving(row)}
            />
          </li>
        ))}
      </ol>
      {removing && (
        <ConfirmRemoveDialog
          title={strings.addressesRemoveTitle(removing.url)}
          message={strings.addressesRemoveMessage}
          confirm={() => {
            setPending({ kind: 'removed', url: removing.url, index: urls.indexOf(removing.url) });
            page.remove(removing.url);
            setRemoving(null);
          }}
          cancel={() => {
            setRemoving(null);
            focusSoon(byFocusId(removeFocusId(removing.url)));
          }}
        />
      )}
    </Page>
  );
}

function AddressPanel({ row, index, count, moveUp, moveDown, remove }: {
  row: AddressRowView;
  index: number;
  count: number;
  moveUp: () => void;
  moveDown: () => void;
  remove: () => void;
}) {
  return (
    <Panel className="address">
      <p className="panel-title">{row.url}</p>
      <p className="address-meta">
        {/* An unknown scope says nothing rather than something wrong. */}
        <span className="muted">{textFor(strings.addressesScopes, row.scope, '')}</span>
        {row.inUse && <span className="in-use">{strings.addressesInUse}</span>}
      </p>
      {row.plainHttpOnInternet && <Notice kind="warning">{strings.addServerPlainHttp}</Notice>}
      {!row.canRemove && <Notice kind="info">{strings.addressesKeepOne}</Notice>}
      {/* A single address has nothing to move or remove. The buttons say which address they act on, as a screen reader reads them
          apart from the row. */}
      {count > 1 && (
        <div className="server-actions">
          <button
            type="button"
            className="button outlined"
            data-focus-id={upFocusId(row.url)}
            aria-label={strings.addressesMoveUpDescription(row.url)}
            disabled={index === 0}
            onClick={moveUp}
          >
            {strings.addressesMoveUp}
          </button>
          <button
            type="button"
            className="button outlined"
            data-focus-id={downFocusId(row.url)}
            aria-label={strings.addressesMoveDownDescription(row.url)}
            disabled={index === count - 1}
            onClick={moveDown}
          >
            {strings.addressesMoveDown}
          </button>
          {row.canRemove && (
            <button
              type="button"
              className="text-button danger"
              data-focus-id={removeFocusId(row.url)}
              aria-label={strings.addressesRemoveDescription(row.url)}
              onClick={remove}
            >
              {strings.settingsRemove}
            </button>
          )}
        </div>
      )}
    </Panel>
  );
}
