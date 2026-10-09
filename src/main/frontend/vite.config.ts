import { defineConfig } from 'vitest/config';

export default defineConfig({
  build: {
    // Lands in the jar's static resources; Maven runs this in generate-resources.
    outDir: '../../../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    include: ['src/**/*.test.ts'],
  },
});
