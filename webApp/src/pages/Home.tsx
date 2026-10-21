import type { HomePage } from 'current-core';
import { EmptyState } from '../components';
import { useObservable, usePage } from '../hooks';
import { Link, useCore } from '../nav';
import { strings } from '../strings';

/** Home: what to do next until M2 brings the libraries. */
export function Home() {
  const core = useCore();
  const page = usePage(() => core.homePage(), [core]);
  return page && <HomeState page={page} />;
}

function HomeState({ page }: { page: HomePage }) {
  const state = useObservable(page.state);
  switch (state.kind) {
    case 'noServer':
      return (
        <EmptyState
          icon="server"
          title={strings.homeNoServerTitle}
          message={strings.homeNoServerMessage}
          action={<Link path="/settings/servers/add" className="button" focusId="home-action">{strings.homeAddServer}</Link>}
        />
      );
    case 'signedOut':
      return (
        <EmptyState
          icon="server"
          title={strings.homeSignedOutTitle}
          message={strings.homeSignedOutMessage(state.serverName ?? '')}
          action={state.signInPath
            ? <Link path={state.signInPath} className="button" focusId="home-action">{strings.homeSignIn}</Link>
            : <Link path="/settings" className="button" focusId="home-action">{strings.homeOpenSettings}</Link>}
        />
      );
    default:
      return (
        <EmptyState
          icon="home"
          title={strings.homeSignedInTitle(state.serverName ?? '')}
          message={state.userName ? strings.homeSignedInAs(state.userName, state.serverName ?? '') : strings.homeSignedInMessage}
        />
      );
  }
}
