import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { App } from './App';

function renderShell(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe('foundation shell', () => {
  it('shows honest scope, semantic landmarks and active home navigation', () => {
    renderShell();
    expect(
      screen.getByRole('heading', { name: 'A foundation for knowledge.' }),
    ).toBeTruthy();
    expect(screen.getByRole('main').id).toBe('main-content');
    expect(
      screen.getByRole('navigation', { name: 'Main navigation' }),
    ).toBeTruthy();
    expect(
      screen
        .getByRole('link', { name: 'Overview' })
        .getAttribute('aria-current'),
    ).toBe('page');
    expect(screen.getByText('Foundation shell', { exact: true })).toBeTruthy();
    expect(screen.getAllByText('Planned capability')).toHaveLength(2);
    expect(screen.getAllByRole('switch')).toHaveLength(1);
    expect(
      document.documentElement.getAttribute('data-mantine-color-scheme'),
    ).toBe('dark');
  });

  it('navigates to foundation information and returns through the brand', async () => {
    const user = userEvent.setup();
    renderShell();
    await user.click(
      screen.getByRole('link', { name: 'Explore the foundation' }),
    );
    expect(
      screen.getByRole('heading', { name: 'Built to grow with care.' }),
    ).toBeTruthy();
    expect(
      screen
        .getByRole('link', { name: /^Foundation$/ })
        .getAttribute('aria-current'),
    ).toBe('page');
    expect(screen.getByText(/Uploading, searching, publishing/)).toBeTruthy();
    await user.click(
      screen.getByRole('link', { name: 'Document Knowledge Platform home' }),
    );
    expect(
      screen.getByRole('heading', { name: 'A foundation for knowledge.' }),
    ).toBeTruthy();
  });

  it('opens foundation directly', () => {
    renderShell('/foundation');
    expect(
      screen.getByRole('heading', { name: 'Guiding principles' }),
    ).toBeTruthy();
  });

  it('recovers an unknown route through its home link', async () => {
    const user = userEvent.setup();
    renderShell('/missing');
    expect(
      screen.getByRole('heading', { name: 'Page not found.' }),
    ).toBeTruthy();
    await user.click(screen.getByRole('link', { name: 'Return to overview' }));
    expect(
      screen.getByRole('heading', { name: 'A foundation for knowledge.' }),
    ).toBeTruthy();
  });

  it('changes theme in both directions by keyboard', async () => {
    const user = userEvent.setup();
    renderShell();
    const toggle = screen.getByRole('switch', { name: 'Light theme' });
    toggle.focus();
    await user.keyboard(' ');
    expect((toggle as HTMLInputElement).checked).toBe(true);
    expect(
      document.documentElement.getAttribute('data-mantine-color-scheme'),
    ).toBe('light');
    await user.keyboard(' ');
    expect((toggle as HTMLInputElement).checked).toBe(false);
    expect(
      document.documentElement.getAttribute('data-mantine-color-scheme'),
    ).toBe('dark');
  });

  it('provides skip navigation as the first keyboard destination', async () => {
    const user = userEvent.setup();
    renderShell();
    await user.tab();
    expect(document.activeElement).toBe(
      screen.getByRole('link', { name: 'Skip to content' }),
    );
    expect(
      screen
        .getByRole('link', { name: 'Skip to content' })
        .getAttribute('href'),
    ).toBe('#main-content');
  });
});
