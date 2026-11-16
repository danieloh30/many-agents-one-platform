import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test.skip(!process.env.MAOP_CONSOLE_URL, 'MAOP_CONSOLE_URL is not set');

test('console loads read-only, connects the stream, and has no serious axe violations', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Agent Operations Console' })).toBeVisible();
  await expect(page.getByTestId('model-badge')).toBeVisible();
  await expect(page.getByTestId('synthetic-badge')).toHaveText('SYNTHETIC DATA');
  await expect(page.getByTestId('stream-status')).toHaveText('Live', { timeout: 15_000 });
  await expect(page.getByRole('region', { name: 'Evidence queue' })).toBeVisible();

  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag22aa']).analyze();
  const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(serious.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
});
