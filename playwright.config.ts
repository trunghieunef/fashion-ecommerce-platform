import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173',
  },
  projects: [
    {
      name: 'chromium-desktop',
      use: { browserName: 'chromium', viewport: { width: 1280, height: 720 } },
    },
    {
      name: 'chromium-mobile',
      use: { browserName: 'chromium', viewport: { width: 360, height: 720 } },
    },
  ],
});
