import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end smoke tests against a running ops-console. Set MAOP_CONSOLE_URL (for example
 * http://localhost:8080); without it every spec is skipped. Nothing here starts a server.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.MAOP_CONSOLE_URL,
    trace: 'retain-on-failure',
    viewport: { width: 1920, height: 1080 },
  },
  projects: [
    { name: 'light', use: { ...devices['Desktop Chrome'], colorScheme: 'light', viewport: { width: 1920, height: 1080 } } },
    { name: 'dark', use: { ...devices['Desktop Chrome'], colorScheme: 'dark', viewport: { width: 1920, height: 1080 } } },
  ],
});
