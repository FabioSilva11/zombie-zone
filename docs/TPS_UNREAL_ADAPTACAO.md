# Adaptação TPS inspirada em portfolio-unrealengine-tps

Referência: https://github.com/hyklux/portfolio-unrealengine-tps

O projeto de referência usa o padrão clássico de TPS do Unreal: os eixos `LookUp` e `LookRight` alteram a rotação do Controller, enquanto `MoveForward` e `MoveRight` alimentam o movimento do personagem. Como este jogo é Three.js + Rapier em um WebView Android, a implementação foi portada por comportamento, não por cópia direta do C++.

## O que foi adaptado

- Controller yaw/pitch próprio para a câmera, com ownership por `pointerId`.
- Movimento relativo ao heading do Controller no mesmo frame.
- Câmera única renderizada; removida a câmera clonada usada como controlador lógico.
- Follow equivalente a um SpringArm: acompanha a posição do jogador sem atraso espacial, retrai contra paredes e volta suavemente quando fica livre.
- Ombro direito e mira central preservados.
- Joystick analógico linear: 50% do curso = 50% da velocidade.
- Multitouch independente: soltar câmera ou tiro não cancela o joystick.
- O corpo usa sempre o `ControllerYaw`, equivalente a `bUseControllerRotationYaw`: acompanha a mira parado ou em movimento.
- O joystick controla `MoveForward`/`MoveRight` no espaço do Controller; esquerda/direita fazem strafe e para baixo recua sem virar o corpo para o deslocamento.
- A cápsula Rapier preserva a folga física de `0,03` em relação ao piso, evitando o travamento `toi=0` depois de encostar em obstáculos.

## Ordem do frame

1. Atualiza Controller yaw/pitch.
2. Calcula MoveForward/MoveRight no espaço do Controller.
3. Resolve Rapier/colisão e posição final do personagem.
4. Resolve facing/animação.
5. Atualiza o SpringArm da câmera a partir da posição final.
6. Atualiza mira/aim assist e renderiza.

## Arquivos principais

- `app/src/main/assets/game/js/camera-controller.js`
- `app/src/main/assets/game/js/player-controller.js`
- `app/src/main/assets/game/js/main.js`
- `web-tests/camera-controller.test.mjs`
- `web-tests/player-controller.test.mjs`
- `web-tests/tps-integration.test.mjs`

## Validação

`npm test`: 105 testes aprovados, 0 falhas.

O teste Gradle/Android não pôde ser executado neste ambiente porque o Gradle Wrapper tentou baixar a distribuição em `services.gradle.org` e o ambiente de execução não possui acesso de rede para esse download.
