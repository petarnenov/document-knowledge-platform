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
    body: '#10141b',
    surface: '#191f29',
    panel: '#212936',
    text: '#edf1f7',
    muted: '#a2afc0',
    border: '#354051',
    accent: '#6597f7',
    danger: '#fa7777',
    success: '#52c49a',
    warning: '#efb55f',
    controlBorder: '#71879e',
    primaryText: '#10141b',
  },
  light: {
    body: '#f6f8fc',
    surface: '#ffffff',
    panel: '#eef2f8',
    text: '#1b2533',
    muted: '#5e6c80',
    border: '#d9e0e9',
    accent: '#235cbd',
    danger: '#b92e2e',
    success: '#137653',
    warning: '#98630b',
    controlBorder: '#718095',
    primaryText: '#ffffff',
  },
} as const;
export const portalTheme = createTheme({
  fontFamily: 'Inter, system-ui, -apple-system, Segoe UI, Arial, sans-serif',
  primaryColor: 'dkp',
  primaryShade: { light: 7, dark: 4 },
  autoContrast: true,
  colors: {
    dark: [
      '#edf1f7',
      '#d4dce7',
      '#a2afc0',
      '#8796aa',
      '#71879e',
      '#354051',
      '#212936',
      '#191f29',
      '#10141b',
      '#0a0d12',
    ],
    dkp: [
      '#edf3ff',
      '#dbe7fe',
      '#bbd2fc',
      '#91b5fa',
      '#6597f7',
      '#427ce7',
      '#2d69d2',
      '#235cbd',
      '#1b4eaa',
      '#163e88',
    ],
  },
  defaultRadius: 'md',
  radius: { sm: '9px', md: '13px' },
  focusRing: 'always',
  headings: {
    fontWeight: '700',
    sizes: {
      h1: { fontSize: '26px', lineHeight: '1.2' },
      h2: { fontSize: '17px' },
      h3: { fontSize: '15px' },
    },
  },
  components: {
    Button: Button.extend({
      defaultProps: { variant: 'default' },
      styles: {
        root: {
          borderRadius: '9px',
          height: 'auto',
          minHeight: '38px',
          padding: '9px 12px',
          fontSize: '16px',
          fontWeight: 400,
        },
      },
    }),
    TextInput: TextInput.extend({
      defaultProps: { size: 'sm' },
      styles: {
        input: {
          borderColor: 'var(--dkp-control-border)',
          backgroundColor: 'var(--mantine-color-default)',
        },
      },
    }),
    Textarea: Textarea.extend({
      defaultProps: { size: 'sm', rows: 3 },
      styles: {
        input: {
          borderColor: 'var(--dkp-control-border)',
          backgroundColor: 'var(--mantine-color-default)',
        },
      },
    }),
    NativeSelect: NativeSelect.extend({
      defaultProps: { size: 'sm' },
      styles: {
        input: {
          borderColor: 'var(--dkp-control-border)',
          backgroundColor: 'var(--mantine-color-default)',
        },
      },
    }),
    Switch: Switch.extend({
      styles: {
        track: { borderColor: 'var(--dkp-control-border)' },
        thumb: {
          backgroundColor: 'var(--mantine-color-body)',
          borderColor: 'var(--mantine-color-text)',
        },
      },
    }),
    Alert: Alert.extend({
      styles: {
        root: {
          backgroundColor: 'var(--dkp-panel)',
          borderColor: 'var(--mantine-color-default-border)',
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
    '--dkp-panel': palette.panel,
    '--dkp-control-border': palette.controlBorder,
    '--dkp-primary-text': palette.primaryText,
    '--dkp-accent': palette.accent,
    '--dkp-warning': palette.warning,
    '--dkp-danger': palette.danger,
    '--dkp-success': palette.success,
  });
  return {
    variables: {},
    dark: values(palettes.dark),
    light: values(palettes.light),
  };
};
