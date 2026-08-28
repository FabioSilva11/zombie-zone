# Ecos de Eldra

Protótipo Android de exploração 3D com combate leve, construído com os personagens e a masmorra modular disponíveis nesta pasta.

## O que já funciona

- Seleção entre 18 personagens blocky.
- Three.js `0.185.0` carregado online pelo jsDelivr.
- Masmorra 3D com salas, paredes, iluminação, névoa e partículas.
- Câmera em terceira pessoa, analógico virtual e arrastar para olhar.
- Dois guardiões (um monstro verde e um morto-vivo), vida, perseguição e ataque.
- Golpe corpo a corpo, animações GLB e portão/portal de saída.
- APK configurado para paisagem e WebView com navegação externa bloqueada.

## Verificação

```powershell
node --test web-tests\*.test.mjs
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --max-workers=1
```

APK: `app/build/outputs/apk/debug/app-debug.apk`  
ID: `com.fabiosilva.ecosdeeldra`  
Versão: `0.1.0` (versionCode 1)  
SHA-256: `5020571FE5BC0BDB122252EB0DD212EE93B3FD3DD4C2EB39F54FE7C5992F0E5C`

O APK foi instalado e iniciado via ADB Wi-Fi no Xiaomi `2312DRA50G5` (`192.168.1.231:5555`). A captura real está em [docs/screenshots/gameplay.png](docs/screenshots/gameplay.png).

O runtime Three.js depende de internet na primeira abertura; os GLBs, texturas e a interface do jogo estão empacotados localmente. Os créditos/licenças estão em `app/src/main/assets/notices/`.
