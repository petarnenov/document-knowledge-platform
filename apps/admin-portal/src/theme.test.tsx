import { StrictMode } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
import {
  THEME_KEY,
  createSchemeManager,
  initializeTheme,
  palettes,
  resolveScheme,
} from './theme-settings';

afterEach(() => vi.restoreAllMocks());
it('accepts only exact light/dark preferences and initializes the root before application rendering', () => {
  for (const value of [
    null,
    '',
    'auto',
    '"light"',
    '{}',
    '<script>',
    'LIGHT',
    'x'.repeat(10000),
    1,
  ])
    expect(resolveScheme(value)).toBe('dark');
  for (const value of ['light', 'dark'] as const) {
    localStorage.setItem(THEME_KEY, value);
    expect(initializeTheme()).toBe(value);
    expect(
      document.documentElement.getAttribute('data-mantine-color-scheme'),
    ).toBe(value);
    expect(createSchemeManager().get('auto')).toBe(value);
  }
  localStorage.removeItem(THEME_KEY);
  expect(initializeTheme()).toBe('dark');
});
it('keeps only the non-secret preference key and remains operable on get/set/clear failures', () => {
  localStorage.setItem('unrelated', 'preserved');
  const manager = createSchemeManager();
  manager.set('light');
  expect(manager.get('dark')).toBe('light');
  expect(localStorage.getItem(THEME_KEY)).toBe('light');
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('blocked');
  });
  manager.set('dark');
  expect(manager.get('light')).toBe('dark');
  manager.set('light');
  expect(manager.get('dark')).toBe('light');
  vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
    throw new Error('blocked');
  });
  manager.clear();
  expect(manager.get('light')).toBe('dark');
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('blocked');
  });
  expect(createSchemeManager().get('light')).toBe('dark');
  expect(initializeTheme()).toBe('dark');
  vi.restoreAllMocks();
  manager.clear();
  expect(localStorage.getItem(THEME_KEY)).toBeNull();
  expect(localStorage.getItem('unrelated')).toBe('preserved');
  localStorage.removeItem('unrelated');
});
it('synchronizes valid, deleted and invalid same-origin updates without duplicate subscriptions', () => {
  const manager = createSchemeManager(),
    update = vi.fn();
  manager.subscribe(update);
  manager.subscribe(update);
  const event = (
    key: string | null,
    value: string | null,
    storageArea: Storage | null = localStorage,
  ) =>
    window.dispatchEvent(
      new StorageEvent('storage', { key, newValue: value, storageArea }),
    );
  event(THEME_KEY, 'light');
  expect(update).toHaveBeenLastCalledWith('light');
  expect(update).toHaveBeenCalledTimes(1);
  expect(manager.get('dark')).toBe('light');
  event('other', 'dark');
  event(THEME_KEY, 'dark', sessionStorage);
  expect(update).toHaveBeenCalledTimes(1);
  event(THEME_KEY, null);
  expect(update).toHaveBeenLastCalledWith('dark');
  event(THEME_KEY, 'auto');
  expect(update).toHaveBeenLastCalledWith('dark');
  event(null, null);
  expect(update).toHaveBeenLastCalledWith('dark');
  manager.unsubscribe();
  event(THEME_KEY, 'light');
  expect(update).toHaveBeenCalledTimes(4);
  manager.unsubscribe();
  const add = vi.spyOn(window, 'addEventListener').mockImplementation(() => {
    throw new Error('blocked');
  });
  manager.subscribe(update);
  add.mockRestore();
  manager.unsubscribe();
  manager.subscribe(update);
  vi.spyOn(window, 'removeEventListener').mockImplementation(() => {
    throw new Error('blocked');
  });
  expect(() => manager.unsubscribe()).not.toThrow();
});
it('switches by Space without remounting routes or persisting any authority', async () => {
  localStorage.setItem(THEME_KEY, 'light');
  const user = userEvent.setup();
  render(
    <StrictMode>
      <MemoryRouter>
        <App />
      </MemoryRouter>
    </StrictMode>,
  );
  const control = screen.getByRole('switch', {
    name: 'Light theme',
  }) as HTMLInputElement;
  expect(control.checked).toBe(true);
  control.focus();
  await user.keyboard(' ');
  expect(control.checked).toBe(false);
  expect(document.activeElement).toBe(control);
  expect(screen.getByText('Dark mode')).toBeTruthy();
  await user.click(screen.getByRole('link', { name: 'Foundation' }));
  expect(screen.getByRole('switch', { name: 'Light theme' })).toBe(control);
  expect(control.checked).toBe(false);
  expect(localStorage.getItem(THEME_KEY)).toBe('dark');
  expect(sessionStorage.length).toBe(0);
});
it('defines readable text, semantic status and visible control boundaries in both palettes', () => {
  const luminance = (hex: string) => {
    const rgb = [1, 3, 5]
      .map((offset) => Number.parseInt(hex.slice(offset, offset + 2), 16) / 255)
      .map((value) =>
        value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4,
      );
    return rgb[0]! * 0.2126 + rgb[1]! * 0.7152 + rgb[2]! * 0.0722;
  };
  const contrast = (a: string, b: string) =>
    (Math.max(luminance(a), luminance(b)) + 0.05) /
    (Math.min(luminance(a), luminance(b)) + 0.05);
  for (const palette of Object.values(palettes))
    for (const background of [palette.body, palette.surface]) {
      for (const text of [
        palette.text,
        palette.muted,
        palette.accent,
        palette.danger,
        palette.success,
      ])
        expect(contrast(text, background)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(palette.border, background)).toBeGreaterThanOrEqual(3);
    }
});
