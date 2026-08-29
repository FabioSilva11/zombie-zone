import { createServer } from 'node:http';
import { readdir, readFile, stat } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const EDITOR_ROOT = fileURLToPath(new URL('.', import.meta.url));
const MAP_ROOT = fileURLToPath(new URL('../mapas_json/', import.meta.url));
const PORT = Number(process.env.PORT) || 4175;
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.png': 'image/png',
  '.glb': 'model/gltf-binary',
};

function naturalMapSort(left, right) {
  return left.localeCompare(right, 'pt-BR', { numeric: true, sensitivity: 'base' });
}

function safeEditorPath(pathname) {
  const relative = normalize(decodeURIComponent(pathname)).replace(/^[/\\]+/, '');
  if (relative.includes('..')) return null;
  return join(EDITOR_ROOT, relative || 'index.html');
}

async function sendFile(response, path) {
  const details = await stat(path);
  const resolved = details.isDirectory() ? join(path, 'index.html') : path;
  const body = await readFile(resolved);
  response.writeHead(200, {
    'Content-Type': CONTENT_TYPES[extname(resolved).toLowerCase()] || 'application/octet-stream',
    'Cache-Control': 'no-cache',
  });
  response.end(body);
}

createServer(async (request, response) => {
  try {
    const url = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
    if (url.pathname === '/api/maps') {
      const files = (await readdir(MAP_ROOT, { withFileTypes: true }))
        .filter((entry) => entry.isFile() && entry.name.toLowerCase().endsWith('.json'))
        .map((entry) => entry.name)
        .sort(naturalMapSort);
      response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-cache' });
      response.end(JSON.stringify(files));
      return;
    }
    if (url.pathname.startsWith('/map-files/')) {
      const filename = decodeURIComponent(url.pathname.slice('/map-files/'.length));
      if (!filename || filename !== filename.split(/[\\/]/).at(-1) || !filename.toLowerCase().endsWith('.json')) {
        response.writeHead(400).end('Nome de mapa inválido.');
        return;
      }
      await sendFile(response, join(MAP_ROOT, filename));
      return;
    }
    const path = safeEditorPath(url.pathname);
    if (!path) {
      response.writeHead(403).end('Acesso negado.');
      return;
    }
    await sendFile(response, path);
  } catch (error) {
    response.writeHead(error?.code === 'ENOENT' ? 404 : 500, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end(error?.code === 'ENOENT' ? 'Arquivo não encontrado.' : `Erro do servidor: ${error.message}`);
  }
}).listen(PORT, () => {
  console.log(`Editor disponível em http://localhost:${PORT}`);
  console.log(`Mapas carregados de ${MAP_ROOT}`);
});
