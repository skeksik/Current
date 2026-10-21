import { StrictMode, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { CurrentWeb } from 'current-core';
import { App } from './App';
import { ErrorBoundary, Failure } from './ErrorBoundary';
import './styles.css';

// One core per page load, outside React: StrictMode may mount the tree twice, the core lives once. Pages create their ViewModels in
// effects (usePage), so the double mount disposes one and creates another.
let content: ReactNode;
try {
  content = <App core={new CurrentWeb()} />;
} catch (e) {
  // The core could not start (storage blocked, a bug): say so rather than leave the page blank.
  reportError(e);
  content = <Failure />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>{content}</ErrorBoundary>
  </StrictMode>,
);
