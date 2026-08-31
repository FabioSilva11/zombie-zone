import { mkdir, writeFile } from 'node:fs/promises';

await mkdir(new URL('../dist/server/', import.meta.url), { recursive: true });

await writeFile(
  new URL('../dist/server/index.js', import.meta.url),
  `export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/api/maps') {
      return Response.json([]);
    }
    return env.ASSETS.fetch(request);
  },
};
`,
);

await writeFile(
  new URL('../dist/server/wrangler.json', import.meta.url),
  `${JSON.stringify(
    {
      main: 'index.js',
      compatibility_date: '2026-08-31',
      assets: { directory: '../client', binding: 'ASSETS' },
    },
    null,
    2,
  )}\n`,
);
