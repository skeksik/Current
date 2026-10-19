import { useCallback, useEffect, useMemo, useSyncExternalStore } from 'react';
import type { Observable, Page } from 'current-core';

/** A Kotlin StateFlow (through the facade's Observable) as React state. */
export function useObservable<T>(observable: Observable<T>): T {
  const subscribe = useCallback((listener: () => void) => observable.subscribe(listener), [observable]);
  const snapshot = useCallback(() => observable.getSnapshot(), [observable]);
  return useSyncExternalStore(subscribe, snapshot);
}

/** One Kotlin ViewModel per page instance, disposed on unmount. */
export function usePage<P extends Page>(create: () => P, deps: unknown[]): P {
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const page = useMemo(create, deps);
  useEffect(() => () => page.dispose(), [page]);
  return page;
}
