import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { CurrentWeb } from 'current-core';
import { App } from './App';
import './styles.css';

// One core per page load, outside React: StrictMode may mount the tree twice, the core lives once. Pages create their ViewModels in
// effects (usePage), so the double mount disposes one and creates another.
const core = new CurrentWeb();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App core={core} />
  </StrictMode>,
);
