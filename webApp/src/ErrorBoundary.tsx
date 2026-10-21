import { Component, type ReactNode } from 'react';
import { webStrings } from './strings';

/**
 * The last resort when rendering throws (a facade call failing, a bug): instead of React's blank page, a message and a Reload button.
 * A reload starts a new core and keeps saved servers and sign-ins, which live in localStorage.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? <Failure /> : this.props.children;
  }
}

export function Failure() {
  return (
    <main className="failure">
      <h1>{webStrings.failureTitle}</h1>
      <p className="muted">{webStrings.failureMessage}</p>
      <button type="button" className="button" onClick={() => location.reload()}>{webStrings.failureReload}</button>
    </main>
  );
}
