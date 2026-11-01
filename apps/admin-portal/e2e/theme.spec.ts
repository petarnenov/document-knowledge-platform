import { expect, test, type Page } from '@playwright/test';

async function renderedContrast(
  page: Page,
  selector: string,
  property = 'color',
) {
  return page
    .locator(selector)
    .first()
    .evaluate((element, prop) => {
      const parse = (value: string) => {
        const parts = value.match(/[\d.]+/g)?.map(Number);
        if (!parts || parts.length < 3)
          throw new Error(`Unsupported computed color ${value}`);
        return [parts[0]!, parts[1]!, parts[2]!, parts[3] ?? 1];
      };
      const over = (front: number[], back: number[]) => {
        const alpha = front[3]! + back[3]! * (1 - front[3]!);
        return [0, 1, 2]
          .map(
            (index) =>
              (front[index]! * front[3]! +
                back[index]! * back[3]! * (1 - front[3]!)) /
              alpha,
          )
          .concat(alpha);
      };
      const ancestors: Element[] = [];
      for (let node: Element | null = element; node; node = node.parentElement)
        ancestors.push(node);
      let background = [255, 255, 255, 1];
      for (const node of ancestors
        .reverse()
        .filter((node) => !prop.startsWith('outline') || node !== element))
        background = over(
          parse(getComputedStyle(node).backgroundColor),
          background,
        );
      const foreground = over(
        parse(getComputedStyle(element).getPropertyValue(prop)),
        background,
      );
      const luminance = (color: number[]) =>
        color
          .slice(0, 3)
          .map((v) => v / 255)
          .map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4))
          .reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i]!, 0);
      const a = luminance(foreground),
        b = luminance(background);
      return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }, property);
}
for (const scheme of ['dark', 'light'] as const) {
  test(`${scheme} rendered controls, forms, storage and responsive routes`, async ({
    page,
    context,
  }) => {
    const session = {
      issuer: 'https://issuer.example',
      subject: 'synthetic',
      displayName: 'Synthetic',
      tenants: ['tenant-a'],
      selectedTenant: 'tenant-a',
      csrfToken: 'fixture',
    };
    const calls: string[] = [];
    await page.route('**/api/v1/**', async (route) => {
      calls.push(route.request().url());
      if (route.request().url().endsWith('/session'))
        await route.fulfill({ json: session });
      else if (route.request().url().includes('/documents?'))
        await route.fulfill({ json: { items: [], nextCursor: null } });
      else await route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } });
    });
    await page.emulateMedia({
      colorScheme: scheme === 'dark' ? 'light' : 'dark',
      reducedMotion: 'reduce',
    });
    await page.goto('/');
    await page.evaluate(
      (value) => localStorage.setItem('mantine-color-scheme', value),
      scheme,
    );
    await page.reload();
    const toggle = page.getByRole('switch', { name: 'Light theme' });
    await expect(toggle).toBeChecked({ checked: scheme === 'light' });
    await expect(page.locator('html')).toHaveAttribute(
      'data-mantine-color-scheme',
      scheme,
    );
    expect(await renderedContrast(page, 'h1')).toBeGreaterThanOrEqual(4.5);
    expect(await renderedContrast(page, '.breadcrumbs')).toBeGreaterThanOrEqual(
      4.5,
    );
    expect(
      await renderedContrast(page, 'nav a[aria-current=page]'),
    ).toBeGreaterThanOrEqual(4.5);
    await page.getByRole('link', { name: 'Documents', exact: true }).click();
    await expect(
      page.getByText('No accessible drafts on this page.'),
    ).toBeVisible();
    await page
      .getByRole('button', { name: 'New document', exact: true })
      .click();
    await page
      .getByLabel('Title', { exact: true })
      .fill('Unsaved synthetic title');
    await page
      .getByLabel('Description', { exact: true })
      .fill('Unsaved description');
    expect(
      await renderedContrast(page, 'input#draft-title'),
    ).toBeGreaterThanOrEqual(4.5);
    expect(
      await renderedContrast(page, 'input#draft-title', 'border-top-color'),
    ).toBeGreaterThanOrEqual(3);
    await page.getByLabel('Title', { exact: true }).focus();
    await expect(page.getByLabel('Title', { exact: true })).toHaveCSS(
      'outline-style',
      'solid',
    );
    expect(
      await renderedContrast(page, 'input#draft-title', 'outline-color'),
    ).toBeGreaterThanOrEqual(3);
    expect(
      await renderedContrast(page, '.documents form .hint'),
    ).toBeGreaterThanOrEqual(4.5);
    const count = calls.length;
    await toggle.focus();
    await page.keyboard.press('Space');
    await expect(toggle).toBeFocused();
    await expect(page.getByLabel('Title', { exact: true })).toHaveValue(
      'Unsaved synthetic title',
    );
    await expect(page.getByLabel('Description', { exact: true })).toHaveValue(
      'Unsaved description',
    );
    expect(calls.length).toBe(count);
    await page.keyboard.press('Space');
    await expect(toggle).toBeChecked({ checked: scheme === 'light' });
    await page
      .getByRole('button', { name: 'Create draft', exact: true })
      .click();
    await expect(page.getByRole('alert')).toBeVisible();
    expect(await renderedContrast(page, '[role=alert]')).toBeGreaterThanOrEqual(
      4.5,
    );
    await page
      .getByRole('button', { name: 'Back to library', exact: true })
      .click();
    for (const width of [1280, 390]) {
      await page.setViewportSize({ width, height: 844 });
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth),
      ).toBeLessThanOrEqual(width);
    }
    await page.evaluate(() => {
      document.documentElement.style.zoom = '2';
    });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(390);
    const refresh = page.getByRole('button', {
      name: 'Refresh drafts',
      exact: true,
    });
    await refresh.scrollIntoViewIfNeeded();
    await expect(refresh).toBeInViewport();
    const geometry = await refresh.evaluate((element) => {
      const card = element.closest('section')!;
      const button = element.getBoundingClientRect(),
        container = card.getBoundingClientRect();
      const label = element.querySelector('.mantine-Button-label')!;
      return {
        left: button.left,
        right: button.right,
        cardLeft: container.left,
        cardRight: container.right,
        labelWidth: label.clientWidth,
        labelScrollWidth: label.scrollWidth,
        labelHeight: label.clientHeight,
        labelScrollHeight: label.scrollHeight,
      };
    });
    expect(geometry.left).toBeGreaterThanOrEqual(geometry.cardLeft);
    expect(geometry.right).toBeLessThanOrEqual(geometry.cardRight);
    expect(geometry.labelScrollWidth).toBeLessThanOrEqual(geometry.labelWidth);
    expect(geometry.labelScrollHeight).toBeLessThanOrEqual(
      geometry.labelHeight,
    );
    await page.evaluate(() => {
      document.documentElement.style.zoom = '';
    });
    await page.reload();
    await expect(toggle).toBeChecked({ checked: scheme === 'light' });
    await page
      .getByRole('button', { name: 'New document', exact: true })
      .click();
    await expect(page.getByLabel('Title', { exact: true })).toHaveValue('');
    const second = await context.newPage();
    await second.goto('/foundation');
    await expect(
      second.getByRole('switch', { name: 'Light theme' }),
    ).toBeChecked({ checked: scheme === 'light' });
    await second.evaluate(() =>
      localStorage.setItem('mantine-color-scheme', 'invalid'),
    );
    await expect(toggle).not.toBeChecked();
    await second.evaluate(() =>
      localStorage.removeItem('mantine-color-scheme'),
    );
    await expect(toggle).not.toBeChecked();
    await second.close();
    for (const path of ['/foundation', '/identity', '/unknown', '/']) {
      await page.goto(path);
      await expect(page.getByRole('main')).toBeVisible();
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth),
      ).toBeLessThanOrEqual(390);
    }
    expect(
      await page.evaluate(() => ({
        local: Object.entries(localStorage),
        session: Object.entries(sessionStorage),
      })),
    ).toEqual({ local: [['mantine-color-scheme', 'dark']], session: [] });
  });
}

test('invalid preference and blocked persistence retain operable dark-default controls', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Storage.prototype.getItem = () => {
      throw new Error('blocked');
    };
    Storage.prototype.setItem = () => {
      throw new Error('blocked');
    };
    Storage.prototype.removeItem = () => {
      throw new Error('blocked');
    };
  });
  await page.goto('/');
  const toggle = page.getByRole('switch', { name: 'Light theme' });
  await expect(toggle).not.toBeChecked();
  await toggle.check();
  await expect(toggle).toBeChecked();
  await page.getByRole('link', { name: 'Foundation', exact: true }).click();
  await expect(toggle).toBeChecked();
  await page.reload();
  await expect(toggle).not.toBeChecked();
});
