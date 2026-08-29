# Zombie Zone

Jogo Android de tiro e exploração 3D em terceira pessoa, construído com os personagens e a masmorra modular disponíveis nesta pasta.

## O que já funciona

- Campanha local com início, mapa de seis fases, história curta antes/depois da missão, preparação, pausa, vitória e derrota.
- Progressão persistente com até três estrelas por fase, cristais e melhorias de vitalidade e poder.
- Seleção entre 18 personagens blocky.
- Mira assistida com disparo manual e indicação do alvo no retículo.
- Movimento por joystick com NippleJS, corpo cinemático e colisões com Rapier.
- Câmera de terceira pessoa com ControllerYaw/ControllerPitch, rotação por toque, sensibilidade e SpringArm com proteção contra paredes.
- Personagem sempre orientado pela mira, com avanço, recuo e strafe relativos à câmera.
- Masmorras montadas a partir dos GLBs fornecidos, inimigos, animações, portão/portal de saída e minimapa explorável.
- Runtime completo empacotado no APK: Three.js `0.185.1`, Camera Controls `3.1.2`, Rapier `0.20.0` e NippleJS `1.0.4`; nenhuma conexão é necessária para jogar.
- APK configurado para paisagem e WebView com navegação externa bloqueada.

## Verificação

```powershell
npm test
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --max-workers=1
```

APK: `app/build/outputs/apk/debug/app-debug.apk`  
ID: `com.fabiosilva.ecosdeeldra`  
Versão: `0.1.0` (versionCode 1)  
SHA-256: gerado após cada recompilação para conferir a entrega.

Para atualizar as cópias locais das bibliotecas depois de `npm install`, execute `npm run vendor:web`. Os créditos e as licenças ficam em `app/src/main/assets/notices/`.

O editor visual dos mapas fica em `Mp_editor/ecos-de-eldra-map-editor-main`.
