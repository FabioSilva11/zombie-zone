const IDS = 'abcdefghijklmnopqr'.split('');
const NAMES = [
  'Kael', 'Mira', 'Brann', 'Nyla', 'Toren', 'Zuri',
  'Lyra', 'Dax', 'Elara', 'Orin', 'Vex', 'Runa',
  'Grom', 'Soren', 'Iris', 'Kiro', 'Atlas', 'Nova',
];

export const CHARACTERS = Object.freeze(
  IDS.map((id, index) => Object.freeze({
    id,
    name: NAMES[index],
    model: `./models/characters/character-${id}.glb`,
    texture: `./models/characters/Textures/texture-${id}.png`,
  })),
);

export const DUNGEON_ASSETS = Object.freeze({
  roomLarge: './models/dungeon/room-large.glb',
  roomSmall: './models/dungeon/room-small.glb',
  roomWide: './models/dungeon/room-wide.glb',
  corridor: './models/dungeon/corridor.glb',
  corridorCorner: './models/dungeon/corridor-corner.glb',
  gateDoor: './models/dungeon/gate-door.glb',
  stairs: './models/dungeon/stairs.glb',
});

export function characterById(id) {
  return CHARACTERS.find((character) => character.id === id) ?? CHARACTERS[0];
}

