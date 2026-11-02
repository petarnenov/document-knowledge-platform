import { expect, test } from '@playwright/test';

test('built shell supports navigation, deep-link reload, theme and fallback', async ({
  page,
}) => {
  await page.goto('/');
  await expect(
    page.getByRole('heading', { name: 'Knowledge workspace' }),
  ).toBeVisible();
  await page.getByRole('link', { name: 'Foundation', exact: true }).click();
  await expect(page).toHaveURL('/foundation');
  await page.reload();
  await expect(
    page.getByRole('heading', { name: 'Built to grow with care.' }),
  ).toBeVisible();
  await expect(
    page.getByRole('link', { name: 'Foundation', exact: true }),
  ).toHaveAttribute('aria-current', 'page');
  const theme = page.getByRole('switch', { name: 'Light theme' });
  await theme.focus();
  await page.keyboard.press('Space');
  await expect(theme).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('html')).toHaveAttribute(
    'data-mantine-color-scheme',
    'light',
  );
  await page.goto('/unknown');
  await expect(
    page.getByRole('heading', { name: 'Page not found.' }),
  ).toBeVisible();
  await page.getByRole('link', { name: 'Return to overview' }).click();
  await expect(
    page.getByRole('heading', { name: 'Knowledge workspace' }),
  ).toBeVisible();
});

test('keyboard navigation is visible and mobile content remains usable', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to content' });
  await expect(skip).toBeFocused();
  await expect(skip).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
  const documents = page.getByRole('link', { name: 'Documents', exact: true });
  await expect(documents).toBeVisible();
  await expect(documents).toBeInViewport();
  await page.getByRole('link', { name: 'Foundation', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(
    page.getByRole('heading', { name: 'Built to grow with care.' }),
  ).toBeVisible();
  await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 390);
});
