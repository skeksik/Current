import { useCallback, useEffect, useReducer, useState, useSyncExternalStore, type DependencyList } from 'react';
import type { Observable, Page } from 'current-core';

/** A Kotlin StateFlow (through the facade's Observable) as React state. */
export function useObservable<T>(observable: Observable<T>): T {
  const subscribe = useCallback((listener: () => void) => observable.subscribe(listener), [observable]);
  const snapshot = useCallback(() => observable.getSnapshot(), [observable]);
  return useSyncExternalStore(subscribe, snapshot);
}

/**
 * One Kotlin ViewModel per page instance: created in an effect and disposed in its cleanup, so StrictMode's double mount never reuses
 * a disposed one. Null until the effect has run.
 */
export function usePage<P extends Page>(create: () => P, deps: DependencyList): P | null {
  const [page, setPage] = useState<P | null>(null);
  useEffect(() => {
    const created = create();
    setPage(created);
    return () => created.dispose();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return page;
}

/**
 * Draws the component again now. After a ViewModel call that set its state synchronously (typing in a field), so the input shows the
 * new value in the same event and keeps its caret, rather than when the flow's collector runs.
 */
export function useRerender(): () => void {
  return useReducer((n: number) => n + 1, 0)[1] as () => void;
}
