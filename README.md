# Zombie Zone

Jogo Android de tiro em terceira pessoa num **mundo aberto de blocos** (estilo Minecraft), escrito em **Kotlin + C++ (NDK/OpenGL ES 3.0)**. Abra a pasta do projeto no Android Studio e rode: não há WebView nem JavaScript.

## Como jogar

- **Objetivo de cada fase:** eliminar todos os zumbis da ilha e atravessar o portal de obsidiana que se abre no acampamento mais distante.
- **Estrelas:** vencer a fase, terminar com vida ≥ 50% e terminar dentro do tempo-alvo.
- **Cristais:** cada fase tem 3. Eles caem dos zumbis.
- **Campanha:** 20 fases em 4 biomas — Floresta, Deserto, Neve (lagos congelados) e Terra Devastada (noite com magma).
- **Zumbis:** três tipos (comum, corredor e brutamontes). Eles encontram caminho pelo relevo com um campo de fluxo (BFS), escutam tiros e cercam o jogador.

- **Mapas procedurais:** cada partida usa uma semente nova, então o mapa nunca se repete. São 6 estilos:
  - ilha;
  - arquipélago (com pontes);
  - vale entre montanhas com rio;
  - mesetas em degraus;
  - cratera com lago;
  - planície de rios.

  O horário também varia (dia, hora dourada, nublado, noite). Os acampamentos podem ser de 5 tipos: fogueira, ruínas, cemitério, depósito e fazenda.

### PvP em Wi-Fi local

Na tela inicial, toque em **⚔ PvP WI-FI**:

1. Um jogador toca em **CRIAR SALA**.
2. Os outros veem a sala na lista e tocam em **ENTRAR**. Se a lista não aparecer, use **CONECTAR POR IP** com o IP mostrado na tela do anfitrião.
3. O anfitrião escolhe o limite de abates (5, 10, 15 ou 20) e o tempo (3, 5 ou 8 minutos), e toca em **INICIAR PARTIDA**.

Regras e detalhes:

- **Formato:** todos contra todos, de 2 a 8 jogadores.
- **Mira:** sem mira assistida e sem tiro automático.
- **Arena:** uma arena procedural nova a cada partida.
- **Renascimento:** 3 segundos depois de ser eliminado, longe dos inimigos.
- **Recompensa:** 2 diamantes por abate, mais 25 pela vitória (5 por participar).
- **Rede:** os aparelhos precisam estar no mesmo Wi-Fi, ou um deles roteando o hotspot.
- **Portas UDP:** 47777 (sala) e 47778 (partida).
- **Autoridade:** o anfitrião decide a vida, os abates e o placar.

Para testar com um celular só, crie a sala no celular e rode no PC (mesmo Wi-Fi) o jogador-robô:

```powershell
node scripts/lan-bot.mjs <ip-do-celular>
```

### Controles (toque)

| Ação | Como |
|---|---|
| Mover | Joystick dinâmico: aparece onde o polegar toca na metade esquerda |
| Correr | Empurrar o joystick até o fim (corrida automática) |
| Câmera | Arrastar em qualquer ponto da metade direita |
| Atirar | Segurar ATIRAR; arrastar o botão também gira a câmera |
| Mirar | MIRAR alterna o zoom (menos dispersão) |
| Pular / Recarregar | Botões próprios (a recarga também é automática) |
| Mapa | Tocar no minimapa abre o mapa da ilha (o jogo pausa) |

Nos Ajustes há sensibilidade, mira assistida, tiro automático, eixo Y invertido, modo canhoto, vibração, qualidade gráfica (60/80/100% da resolução), volumes, FPS e lembretes.

## Retenção: objetivos, skins, anúncios e prêmios diários

- **18 skins** (os personagens Kenney). Formas de liberar:
  - **Diamantes:** Mira, Toren e Orin.
  - **Objetivos:**
    - abates: Brann (40), Runa (250);
    - tiros na cabeça: Dax (50);
    - vencer fase: Lyra (5), Atlas (15);
    - estrelas: Grom (30);
    - cristais: Iris (30);
    - minutos jogados: Soren (60).

    Todas essas, menos Lyra e Atlas, também podem ser compradas com diamantes.
  - **Anúncios assistidos:** Zuri (3), Vex (8), Nova (15).
  - **Dias de login:** Nyla (2), Elara (5), Kiro (7). Exclusivas, não podem ser compradas.
- **Calendário diário** com 7 prêmios crescentes (30 → 150 diamantes), resgatado uma vez por dia.
- **3 missões diárias** sorteadas por data, mais um bônus por completar as três.
- **Baú grátis a cada 4 horas.** Dá para abrir antes do tempo assistindo a um anúncio.
- **Anúncios premiados (AdMob):** +25 diamantes (até 5 por dia), reviver uma vez por fase, dobrar a recompensa da vitória e abrir o baú.
- **Dificuldade adaptativa por receita de anúncios (opcional):** em Ajustes, é possível ativar um modo experimental que usa a estimativa ILRD do AdMob registrada localmente neste aparelho para subir até 5 níveis moderados nas novas fases. A opção vem desativada, pode ser desligada a qualquer momento e não altera o PvP. O valor do AdMob é uma estimativa por impressão, não receita consolidada.
- **Lembretes locais** (notificação) quando o baú fica pronto e para o prêmio do dia seguinte.

> Os IDs de AdMob são **de teste**. Antes de publicar, troque `AD_UNIT_ID` em `ads/RewardedAds.kt` e o `APPLICATION_ID` no `AndroidManifest.xml` pelos IDs reais.

## Estrutura

```text
app/src/main/
  cpp/                      Motor C++ (CMake, OpenGL ES 3.0)
    core/                   matemática, JSON, ruído, acesso a assets (AAssetManager)
    world/                  blocos, texturas procedurais 16x16, mundo voxel/chunks, gerador de terreno
    render/                 renderizador (céu, nuvens, água, personagens, partículas), carregador GLB
    game/                   jogador, câmera, armas, zumbis (IA + navegação), PvP (net.cpp, UDP), HUD/eventos, protocolo JNI
    jni_bridge.cpp          funções nativas usadas por NativeBridge.kt
  java/com/fabiosilva/ecosdeeldra/
    MainActivity.kt         fluxo de telas, missão, anúncios, eventos do motor
    engine/                 NativeBridge (JNI), GameSurfaceView (GLSurfaceView, 60 FPS), protocolo
    data/                   campanha, skins, progresso e regras (testáveis), SaveStore (JSON)
    ui/                     telas em Views nativas, HUD/controles em Canvas, sobreposições
    audio/                  SoundPool (efeitos) + MediaPlayer (música)
    net/                    LanLobby: salas PvP no Wi-Fi (broadcast UDP)
    ads/  notify/           AdMob premiado e lembretes (AlarmManager + notificação)
  assets/characters/        os 18 personagens GLB + texturas (únicos assets reaproveitados)
  res/raw/                  áudio OGG Vorbis gerado por scripts/build-audio.ps1
```

O mundo, as texturas, os sons e as músicas são **gerados pelo projeto**: terreno e texturas em tempo de execução, e áudio sintetizado por `scripts/generate-audio.mjs`. Para regerar o áudio é preciso ter Node.js e ffmpeg:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-audio.ps1
```

## Compilar e testar

No Android Studio, use **Run** com o celular conectado. Só a ABI do aparelho é compilada.

Pela linha de comando (configurada para PCs com pouca RAM):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=1
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Para iterar mais rápido num aparelho arm64:

```powershell
.\gradlew.bat :app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a --no-daemon --max-workers=1
adb install -r -t app/build/intermediates/apk/debug/app-debug.apk
```

- **Requisitos:** NDK 28.2.13676358 e CMake 3.22.1 (pelo SDK Manager), JDK 17.
- **ID do app:** `com.fabiosilva.ecosdeeldra`. Versão `0.3.0` (versionCode 3).

## Versão anterior

A versão WebView/Three.js, os mapas de masmorra, o editor de mapas e os testes web foram movidos para `legacy/`. Eles não entram no APK. Também existe um backup completo em `zombie-zone-backup-2026-10-02.zip`, na Área de Trabalho.

Créditos: personagens [Kenney Blocky Characters](https://kenney.nl) (CC0). Licença em `app/src/main/assets/notices/`.
