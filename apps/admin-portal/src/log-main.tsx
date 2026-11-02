import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@mantine/core/styles.css';
import './styles.css';
import './log-viewer.css';
import { LogViewer } from './LogViewer';
import { initializeTheme } from './theme-settings';

initializeTheme();
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <LogViewer />
  </StrictMode>,
);
