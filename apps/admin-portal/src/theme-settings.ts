import {
  Alert,
  Button,
  Switch,
  NativeSelect,
  TextInput,
  Textarea,
  createTheme,
  type CSSVariablesResolver,
  type MantineColorSchemeManager,
} from '@mantine/core';

export const THEME_KEY = 'mantine-color-scheme';
export type PortalScheme = 'dark' | 'light';
export function resolveScheme(value: unknown): PortalScheme {
  return value === 'light' ? 'light' : 'dark';
}
function storedScheme(): PortalScheme {
  try {
    return resolveScheme(window.localStorage.getItem(THEME_KEY));
  } catch {
    return 'dark';
  }
}
export function initializeTheme() {
  const scheme = storedScheme();
  document.documentElement.setAttribute('data-mantine-color-scheme', scheme);
  return scheme;
}

export function createSchemeManager(): MantineColorSchemeManager {
  let current = storedScheme();
  let listener: ((event: StorageEvent) => void) | undefined;
  let subscriber: ((value: PortalScheme) => void) | undefined;
  const unsubscribe = () => {
    subscriber = undefined;
    if (listener) {
      try {
        window.removeEventListener('storage', listener);
        listener = undefined;
      } catch {
        // Retain the handle for a later cleanup attempt; inactive callbacks do nothing.
      }
    }
  };
  return {
    get: () => current,
    set: (value) => {
      current = resolveScheme(value);
      try {
        window.localStorage.setItem(THEME_KEY, current);
      } catch {
        // Keep the user's selection in this tab when persistence is blocked.
      }
    },
    clear: () => {
      current = 'dark';
      try {
        window.localStorage.removeItem(THEME_KEY);
      } catch {
        // A later reload may use its last persisted preference.
      }
    },
    subscribe: (update) => {
      subscriber = update;
      if (listener) return;
      listener = (event) => {
        try {
          if (
            event.storageArea !== window.localStorage ||
            (event.key !== THEME_KEY && event.key !== null)
          )
            return;
          current = resolveScheme(event.key === null ? null : event.newValue);
          subscriber?.(current);
        } catch {
          // Unavailable storage does not grant any authority or interrupt navigation.
        }
      };
      try {
        window.addEventListener('storage', listener);
      } catch {
        listener = undefined;
      }
    },
    unsubscribe,
  };
}

export const palettes = {
  dark: {
    body: '#101723',
    surface: '#172130',
    text: '#edf2f7',
    muted: '#b8c7d9',
    border: '#71879e',
    accent: '#91d8ca',
    danger: '#ffb4a7',
    success: '#9cdfbc',
  },
  light: {
    body: '#f5f7f9',
    surface: '#ffffff',
    text: '#172330',
    muted: '#42546a',
    border: '#718095',
    accent: '#155c51',
    danger: '#9c2525',
    success: '#215d3b',
  },
} as const;
export const portalTheme = createTheme({
  fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
  primaryColor: 'dkp',
  primaryShade: { light: 8, dark: 4 },
  autoContrast: true,
  colors: {
    dark: [
      '#edf2f7',
      '#dce5ed',
      '#b8c7d9',
      '#94a6ba',
      '#71879e',
      '#4f657d',
      '#172130',
      '#101723',
      '#0d1420',
      '#080f18',
    ],
    dkp: [
      '#e9faf5',
      '#d1f3e8',
      '#aae7d5',
      '#91d8ca',
      '#70c8b4',
      '#4bb09a',
      '#2a8c79',
      '#1b7064',
      '#155c51',
      '#10483f',
    ],
  },
  defaultRadius: 'sm',
  focusRing: 'always',
  headings: { fontWeight: '600' },
  components: {
    Button: Button.extend({ defaultProps: { variant: 'outline' } }),
    TextInput: TextInput.extend({
      defaultProps: { size: 'sm' },
      styles: {
        input: {
          borderColor: 'var(--mantine-color-default-border)',
          backgroundColor: 'var(--mantine-color-default)',
        },
      },
    }),
    Textarea: Textarea.extend({
      defaultProps: { size: 'sm', rows: 3 },
      styles: {
        input: {
          borderColor: 'var(--mantine-color-default-border)',
          backgroundColor: 'var(--mantine-color-default)',
        },
      },
    }),
    NativeSelect: NativeSelect.extend({
      defaultProps: { size: 'sm' },
      styles: {
        input: {
          borderColor: 'var(--mantine-color-default-border)',
          backgroundColor: 'var(--mantine-color-default)',
        },
      },
    }),
    Switch: Switch.extend({
      styles: {
        track: { borderColor: 'var(--mantine-color-default-border)' },
        thumb: {
          backgroundColor: 'var(--mantine-color-body)',
          borderColor: 'var(--mantine-color-text)',
        },
      },
    }),
    Alert: Alert.extend({
      styles: {
        root: {
          backgroundColor: 'var(--mantine-color-default)',
          borderColor: 'var(--dkp-danger)',
        },
        message: { color: 'var(--mantine-color-text)' },
      },
    }),
  },
});
export const variables: CSSVariablesResolver = () => {
  const values = (palette: (typeof palettes)[PortalScheme]) => ({
    '--mantine-color-body': palette.body,
    '--mantine-color-text': palette.text,
    '--mantine-color-dimmed': palette.muted,
    '--mantine-color-placeholder': palette.muted,
    '--mantine-color-default': palette.surface,
    '--mantine-color-default-border': palette.border,
    '--dkp-accent': palette.accent,
    '--dkp-danger': palette.danger,
    '--dkp-success': palette.success,
  });
  return {
    variables: {},
    dark: values(palettes.dark),
    light: values(palettes.light),
  };
};
