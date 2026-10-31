import { useState, type ReactNode } from 'react';
import { MantineProvider } from '@mantine/core';
import { createSchemeManager, portalTheme, variables } from './theme-settings';

export function PortalTheme({ children }: { children: ReactNode }) {
  const [manager] = useState(createSchemeManager);
  return (
    <MantineProvider
      theme={portalTheme}
      defaultColorScheme="dark"
      colorSchemeManager={manager}
      cssVariablesResolver={variables}
    >
      {children}
    </MantineProvider>
  );
}
