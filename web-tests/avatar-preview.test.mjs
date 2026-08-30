import test from 'node:test';
import assert from 'node:assert/strict';
import * as preview from '../app/src/main/assets/game/js/avatar-preview.js';

test('preview 3D escolhe uma apresentação coerente para cada tela', () => {
  assert.equal(typeof preview.resolvePreviewMode, 'function');
  assert.equal(preview.resolvePreviewMode('home'), 'showcase');
  assert.equal(preview.resolvePreviewMode('avatar'), 'rotate');
  assert.equal(preview.resolvePreviewMode('preparation'), null);
  assert.equal(preview.resolvePreviewMode('victory'), 'celebrate');
  assert.equal(preview.resolvePreviewMode('gameplay'), null);
});

test('distância da prévia mantém o corpo inteiro mesmo em painel estreito', () => {
  assert.equal(typeof preview.previewCameraDistance, 'function');
  const wide = preview.previewCameraDistance({ x: 2.4, y: 4.2, z: 1.6 }, 1);
  const narrow = preview.previewCameraDistance({ x: 2.4, y: 4.2, z: 1.6 }, 0.38);
  assert.ok(wide > 7);
  assert.ok(narrow > wide, 'painel estreito precisa afastar a câmera para não cortar os braços');
});

test('tela estática recentraliza o herói de frente depois da rotação da seleção', () => {
  assert.equal(preview.previewRotationForMode('idle'), -0.18);
  assert.equal(preview.previewRotationForMode('celebrate'), -0.18);
  assert.equal(preview.previewRotationForMode('rotate'), null);
});

test('lobby usa apresentação animada sem girar continuamente de costas', () => {
  assert.equal(preview.previewRotationForMode('showcase'), -0.18);
  assert.equal(
    preview.choosePreviewClipName(['static', 'idle', 'emote-no', 'emote-yes'], 'showcase'),
    'emote-yes',
  );
});
