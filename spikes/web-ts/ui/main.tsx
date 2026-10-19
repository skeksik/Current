import { createRoot } from 'react-dom/client';
import { CurrentWeb } from 'current-core';
import { App } from './App';
import './styles.css';

// No <StrictMode>: its double mount would dispose a page's Kotlin ViewModel and then reuse it.
const core = new CurrentWeb();
createRoot(document.getElementById('root')!).render(<App core={core} />);
