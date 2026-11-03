import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import '@mantine/core/styles.css';
import './styles.css';
import { initializeTheme } from './theme-settings';
import { portalLogging } from './portal-logging';

initializeTheme();
portalLogging.start();
import.meta.hot?.dispose(() => portalLogging.close());

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
