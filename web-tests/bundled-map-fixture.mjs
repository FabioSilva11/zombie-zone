import { readFile } from 'node:fs/promises';
import { prepareBundledMap } from '../app/src/main/assets/game/js/map-library.js';

const sourceUrl = new URL('../app/src/main/assets/game/maps/mapa%20(1).json', import.meta.url);
const source = JSON.parse(await readFile(sourceUrl, 'utf8'));

export const BUNDLED_MAP = prepareBundledMap(source, 'mapa (1).json');
