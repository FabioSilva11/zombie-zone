import { cp, mkdir, rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { defineConfig } from 'vite';
import { sites } from '@openai/sites-vite-plugin';

function copyDungeonAssets() {
  return {
    name: 'copy-dungeon-assets',
    async closeBundle() {
      const destination = resolve('dist/client/assets');
      await rm(destination, { recursive: true, force: true });
      await mkdir(destination, { recursive: true });
      await cp(resolve('assets'), destination, { recursive: true });
    },
  };
}

export default defineConfig({
  publicDir: false,
  plugins: [copyDungeonAssets(), sites()],
  build: {
    outDir: 'dist/client',
    emptyOutDir: true,
    rollupOptions: {
      external: ['three', /^three\/addons\//],
    },
  },
});
