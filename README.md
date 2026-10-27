# Zombie Zone

Jogo Android de tiro em terceira pessoa num **mundo aberto de blocos** (estilo Minecraft), escrito em **Kotlin + C++ (NDK/OpenGL ES 3.0)**. O jogo roda offline: não há conta, servidor, WebView nem JavaScript (só os anúncios precisam de internet). Abra a pasta do projeto no Android Studio e rode.

## Como jogar

**Objetivo de cada fase: destrua ninhos → recolha cristais → extraia pelo portal.**

1. **Destrua os 3 ninhos de infecção.** Cada ninho tem um núcleo roxo pulsante: atire nele (tiros ou faca; granadas e molotovs não ferem ninhos). Com você a menos de 55 m, o ninho gera zumbis.
2. **Recolha o cristal** que cai do núcleo de cada ninho destruído: basta chegar perto. O cristal fica no chão até você pegá-lo, e aparece na bússola, no minimapa e no mapa.
3. **Extraia pelo portal.** Com os 3 cristais em mãos, o portal de obsidiana abre. Entre nele para vencer a fase. O portal fica num ponto da ilha independente dos ninhos (aparece como PORTAL no mapa).

Não é preciso matar todos os zumbis: os abates rendem diamantes, mas não abrem o portal.

- **Tutorial jogável:** na primeira fase, dicas curtas ensinam mover, olhar, atirar, abrir o mapa, seguir o marcador, destruir o núcleo do ninho, recolher o cristal e usar o portal. Quem já venceu alguma fase nunca vê o tutorial; ele pode ser pulado no próprio cartão de dica.
- **Estrelas:** vencer a fase, terminar com vida ≥ 50% e terminar dentro do tempo-alvo (cresce com a fase; 6:09 na fase 1).
- **Cristais x diamantes:** cada fase tem 3 **cristais** (um por ninho); eles dão um bônus na primeira vez. Os **diamantes** (◆) são a moeda do jogo: vêm de abates, baús, estrelas, missões e vitórias.
- **Campanha infinita:** 5 fases por zona. Os quatro biomas (Floresta, Deserto, Neve e Terra Devastada) se repetem em ciclos cada vez mais difíceis (II, III...). A noite só aparece a partir da fase 3.
- **O terreno:** cada partida usa o gerador contínuo do projeto Horizonte por semente, numa área de 6144 × 6144 blocos e altura de 128. Inclui ruído de valor suavizado, floresta/deserto/neve, rios, cavernas, minérios, aldeias, árvores e ilhas flutuantes. As missões acrescentam trilhas e pontos de interesse: acampamento, ruínas, cemitério, depósito, fazenda, torre de vigia, posto avançado, casa, vilarejo, igreja, posto de gasolina, prédio em ruínas e celeiro. Entre nas construções e suba nas torres: há baús escondidos com diamantes e itens. A cura é escassa.
- **Zumbis:** 10 tipos que aparecem conforme a fase (errante, corredor, brutamontes, arremessador, incendiário, retalhador, inchado, gritador, cuspidor e atirador de facas), mais variações elite (blindado, gigante, veloz, regenerador, tóxico e furioso). Eles contornam o relevo com um campo de fluxo (BFS), escutam tiros e cercam o jogador.
- **Mapa:** tocar no minimapa abre o mapa da ilha inteira (o jogo pausa) com ninhos, cristais caídos, portal e suprimentos.
- **Dificuldade:** depende só da fase e das melhorias que você comprou. Nunca depende de anúncios.
- **Sobrevivência:** numa ilha livre sem ninhos nem portal. Os zumbis chegam em ondas cada vez maiores, vindos de até 3 frentes; a cada 5 ondas vem uma GRANDE HORDA. O ranking é local (neste aparelho). Ela se escolhe na aba SOBREVIVÊNCIA da tela PREPARAR EXPEDIÇÃO (veja abaixo), como o contrato **Resistência**.

### Contratos de expedição

Depois da primeira vitória, JOGAR abre **PREPARAR EXPEDIÇÃO**: você escolhe o modo (campanha ou sobrevivência), o **contrato**, confere o equipamento (barra de itens e MOCHILA) e toca em **PARTIR**. Antes da primeira vitória o jogo parte direto, sem tela de contratos.

Um contrato muda as regras da expedição em troca de uma recompensa maior. A tela mostra o custo, a recompensa e o objetivo opcional antes de partir. Contratos nunca custam diamantes nem anúncios, e o bônus do contrato não é dobrado por vídeo (o DOBRAR só dobra a recompensa normal). O bônus só é pago ao **extrair pelo portal**; ao cair ou abandonar, valem só as recompensas normais de abates, baús e exploração.

| Contrato | Libera | Regras | Recompensa |
|---|---|---|---|
| Expedição padrão | fase 1 | as regras de sempre | recompensas normais da fase |
| Recuperação silenciosa | fase 2 | 45% menos zumbis e 50% menos guardas nos ninhos; **munição escassa**: 3,2 pentes de reserva (+0,1 por fase, no máximo 7) e +0,5 pente por baú; suprimentos por anúncio só completam o pente atual | ao extrair, ◆ 20 + 1 por fase (até 80), mais 25% do saque de abates, baús e exploração; objetivo opcional **Silêncio**: até ◆ 60, menos ◆ 3 por zumbi alertado (os gerados pelos ninhos não contam) |
| Noite vermelha | fase 3 | névoa a 45% do alcance; zumbis percebem você de mais longe e perseguem por mais tempo (+35%), 10% mais rápidos e 15% mais fortes | na campanha, ao extrair, ◆ 25 + 2 por fase (até 125), mais 50% do saque; na sobrevivência, +50% da recompensa de ondas |
| Ninho instável | fase 4 | 35% dos zumbis gerados pelos ninhos são Inchados (explodem ao chegar perto) e o núcleo dos ninhos fica laranja | ao extrair, ◆ 10 + 1 por fase (até 60), mais 30% do saque; destruir todos os ninhos concede o módulo raro **Núcleo instável** (guardado no histórico de expedições) |
| Caixa perdida | fase 3 | uma carga fica entre 450 a 1400 m da largada e a mais de 250 m dos ninhos e do portal (a mesma ilha tem sempre a mesma carga); ela só aparece no mapa e na bússola depois que você destruir o primeiro ninho | ao extrair, ◆ 10 + 1 por fase (até 60); objetivo opcional **Carga**: ◆ 60 se for coletada antes de entrar no portal |
| Resistência | fase 1 | sobrevivência por ondas, sem ninhos nem portal | ◆ 8 por onda vencida, ◆ 1 a cada 2 abates e ◆ 30 ao bater o recorde (60 s ou mais) |

**Código da ilha:** cada expedição tem um código no formato `ZZ-XXXX-XXXX-XXXXX`. Quem digitar o mesmo código em **CÓDIGO…** joga a mesma ilha sob as mesmas regras (a partida em si não se repete quadro a quadro, e as suas melhorias contam). Um código só abre fases que você já liberou e contratos disponíveis. **COPIAR** guarda o código na área de transferência e **NOVA ILHA** sorteia outro; com um código colado, tentar de novo mantém a ilha. O jogo guarda o último contrato usado em cada modo e o histórico das últimas 40 expedições, tudo neste aparelho.

### PvP em Wi-Fi local

O PvP é **casual**: partidas entre amigos no mesmo Wi-Fi, sem ranking global, sem conta e sem anti-cheat absoluto. O anfitrião decide a vida, os abates e o placar, então não use o PvP para competições sérias.

Na tela inicial, toque em **⚔ PvP WI-FI**:

1. Um jogador toca em **CRIAR SALA**.
2. Os outros veem a sala na lista e tocam em **ENTRAR**. Se a lista não aparecer, use **CONECTAR POR IP** com o IP mostrado na tela do anfitrião.
3. O anfitrião escolhe o limite de abates (5, 10, 15 ou 20) e o tempo (3, 5 ou 8 minutos), e toca em **INICIAR PARTIDA**.

Regras e detalhes:

- **Formato:** todos contra todos, de 2 a 8 jogadores.
- **Mira:** sem mira assistida e sem tiro automático.
- **Arena:** usa o mesmo núcleo de terreno do Horizonte, incluindo ilhas flutuantes, com bordas finitas e áreas seguras de renascimento. Todos os participantes precisam desta versão do gerador (protocolo ZZ2); versões anteriores não entram na sala.
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
| Correr | Botão CORRER liga a corrida automática (toque de novo, ou puxe o joystick para trás, para parar); empurrar o joystick até o fim também corre |
| Câmera | Arrastar em qualquer ponto livre da metade direita |
| Atirar | Segurar ATIRAR; arrastar o botão também gira a câmera |
| Mirar | MIRAR alterna o zoom |
| Pular / Recarregar / Agachar | Botões próprios (a recarga também é automática com o pente vazio) |
| Faca | FACA golpeia de perto (também fere o núcleo do ninho) |
| Granada | Segure para ver a trajetória e solte para lançar; arraste até o ✕ para cancelar (granada, molotov ou fumaça, conforme o item escolhido) |
| Itens | Barra rápida de 6 espaços: toque para equipar a arma, escolher o arremessável ou usar o consumível; MOCHILA abre o inventário (o jogo pausa) |
| Suprimentos | Botão SUPRIMENTOS (anúncio premiado): caixa de paraquedas com vida, munição e itens |
| Mapa | Tocar no minimapa abre o mapa da ilha (o jogo pausa) |

Nos Ajustes há sensibilidade, mira assistida, tiro automático, eixo Y invertido, personalização completa do HUD (mover, aumentar ou esconder cada botão), modo canhoto, vibração, volumes, qualidade gráfica (60/80/100% da resolução), modo ultra-realista, FPS e lembretes. **REVER TUTORIAL** refaz as dicas da primeira fase sem mexer no seu progresso.

## Armas, mochila e melhorias

- **Mochila estilo Minecraft:** 6 espaços na barra rápida + 18 na mochila. As armas nunca somem; consumíveis empilham.
- **Armas do template Multiplayer FPS (Unity):** fuzil de assalto (hitscan, 10 tiros/s, pente de 30, recarga de 2 s, mira em cruz) e escopeta (bala que voa a 30 m/s, 1 tiro/s, pente de 10, recarga de 3 s, mira de círculo aberto). A mira fica cinza recarregando ou entre disparos e um anel mostra o progresso da recarga. O dano foi escalado para a vida dos zumbis (x1,8). Modelos e sons do template não foram copiados (licença Unity Companion): o fuzil usa o modelo AK, a escopeta é em blocos e os sons são sintetizados.
- **Consumíveis:** kit médico, bandagem, antídoto, adrenalina, munição incendiária, colete, granada, molotov e granada de fumaça.
- **10 melhorias** compradas com diamantes (vitalidade, poder de fogo, recarga, blindagem, regeneração, agilidade, mira mortal, pente estendido, sorte e explosivos); o limite de nível sobe a cada zona liberada.

## Personagem, progresso e anúncios

- **Editor de personagem** em blocos (até 6 heróis). As peças comuns são livres; 17 peças especiais (além do kit básico) são liberadas por objetivos (abates, tiros na cabeça, fases, estrelas, cristais, minutos jogados), por dias de login, por anúncios assistidos ou por diamantes.
- **Calendário diário** com 7 prêmios crescentes (30 → 150 diamantes), resgatado uma vez por dia.
- **3 missões diárias** sorteadas por data, mais um bônus por completar as três.
- **Baú grátis a cada 4 horas.** Dá para abrir antes do tempo assistindo a um anúncio.
- **Lembretes locais** (notificação) quando o baú fica pronto e para o prêmio do dia seguinte.
- **Save recuperável:** o progresso fica num arquivo próprio com cópias de segurança rotativas; se o arquivo principal estragar, o jogo recupera a cópia mais recente e avisa, em vez de recomeçar do zero em silêncio.
- **Anúncios (AdMob):**
  - Premiados, sempre opcionais: +25 diamantes (até 5 por dia), reviver uma vez por partida, dobrar a recompensa da vitória, abrir o baú antes da hora, pedir suprimentos durante a partida, caixas de suprimentos no Arsenal (até 6 por dia) e pular uma fase travada (depois de 2 derrotas).
  - Intersticiais: entre partidas (com intervalo mínimo) e antes de cada 5º início manual de fase pela campanha. Nunca durante a partida.
  - O consentimento (UMP) é pedido quando exigido; em Ajustes aparece "Privacidade dos anúncios" quando disponível.
  - Os IDs reais do app ficam em `app/build.gradle.kts` (`admobAppId`, `ADMOB_REWARDED_ID`, `ADMOB_INTERSTITIAL_ID`). O aparelho de desenvolvimento está em `ADMOB_TEST_DEVICES` e recebe anúncios de teste; ao instalar em outro aparelho, adicione o hash dele a essa lista e não clique em anúncios reais.

## Estrutura

```text
app/src/main/
  cpp/                      Motor C++ (CMake, OpenGL ES 3.0)
    core/                   matemática, aleatório/ruído, plataforma (assets e imagens via JNI), medição de desempenho
    world/                  blocos, texturas procedurais 16x16, mundo em chunks com streaming,
                            ilha procedural (island), estruturas e construções
    render/                 renderizador (céu, nuvens, água, partículas, sombras), personagens de blocos
                            (blocky/character), arma importada (weapon_asset)
    game/                   jogador, câmera, armas e itens, missão da ilha (ninhos, cristais, portal),
                            sobrevivência por ondas, zumbis (IA + campo de fluxo), PvP (net.cpp, UDP),
                            HUD/eventos e protocolo JNI
    jni_bridge.cpp          funções nativas usadas por NativeBridge.kt
  java/com/fabiosilva/ecosdeeldra/
    MainActivity.kt         fluxo de telas, missão, anúncios, eventos do motor
    engine/                 NativeBridge (JNI), GameSurfaceView (GLSurfaceView, 60 FPS), protocolo
    data/                   campanha, itens, progresso e regras (testáveis), contratos e código da ilha, visual do personagem, SaveStore
      save/                 save recuperável: codec JSON, migrações, arquivos com backup e mensagens
    tutorial/               objetivo oficial e tutorial jogável (máquina de estados pura, testável)
    ui/                     telas em Views nativas, HUD/controles em Canvas, sobreposições, ícones da marca
    audio/                  SoundPool (efeitos) + MediaPlayer (música)
    net/                    LanLobby: salas PvP no Wi-Fi (broadcast UDP)
    ads/  notify/           AdMob (consentimento, premiado, intersticial) e lembretes (AlarmManager)
  assets/weapons/           modelo da arma (.zzm + textura)
  assets/icons/             ícones SVG de armas
  res/raw/                  áudio OGG Vorbis gerado por scripts/build-audio.ps1
docs/brand/                 identidade visual (cores, ícones SVG, regras de uso)
docs/superpowers/           especificações e planos de trabalho
```

O mundo, as texturas, os personagens, os sons e as músicas são **gerados pelo projeto**: terreno, texturas e personagens em tempo de execução, e áudio sintetizado por `scripts/generate-audio.mjs`. Para regerar o áudio é preciso ter Node.js e ffmpeg:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-audio.ps1
```

## Compilar e testar

No Android Studio, use **Run** com o celular conectado. Só a ABI do aparelho é compilada.

Pela linha de comando (configurada para PCs com pouca RAM; rode um Gradle por vez):

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
- **ID do app:** `com.fabiosilva.ecosdeeldra`. Versão `0.7.0` (versionCode 9).

## Versão anterior

A versão WebView/Three.js, os mapas de masmorra, o editor de mapas e os testes web foram movidos para `legacy/`. Eles não entram no APK.

## Créditos

Mundo, texturas, personagens, sons e músicas são gerados pelo projeto. O modelo da arma em `app/src/main/assets/weapons/` foi convertido por `tools/convert_glb_to_zzm.py`; a origem e a licença desse modelo ainda precisam ser documentadas aqui (pendência do autor). Qualquer material de terceiros da versão anterior fica apenas em `legacy/` (com as licenças em `legacy/notices/`) e não entra no APK.

## Terreno Horizonte

O núcleo está em `app/src/main/cpp/world/horizonte`, portado do gerador contínuo
do projeto Horizonte. As fórmulas de ruído, rios, biomas, cavernas e ilhas são
preservadas; os IDs dos blocos são convertidos na integração com este motor.
A área inicial especial de posições fixas do Horizonte não é usada.

As ilhas flutuantes mantêm sua altura original (topos entre y=61 e y=82).
Ninhos, início e portal ficam no chão inferior, em áreas reservadas. Não há
voo nem construção para alcançar as ilhas. Os minérios são decorativos neste
jogo; minério de diamante usa o equivalente de ferro e lava usa magma sólido.

A água usa o algoritmo e as texturas do Horizonte: fonte, sete níveis de
escoamento e queda vertical, com atualização a cada 0,25 s. A simulação tem
fila limitada e acompanha a área do jogador; no PvP, o anfitrião a controla.
Tiros removem o bloco atingido e granadas abrem crateras. O fundo do mundo,
o portal e os suportes essenciais dos ninhos são protegidos. As alterações
permanecem durante a partida, inclusive ao sair e voltar a uma região, e são
reiniciadas numa nova partida. No PvP, as alterações são confirmadas e
reenviadas pelo anfitrião; todos precisam da versão de protocolo ZZ3.

O ciclo solar original dura 20 minutos: 10 de dia, 1 min 40 s de pôr do sol,
6 min 40 s de noite e 1 min 40 s de amanhecer. A pausa da missão congela
o relógio. A iluminação, o céu e a lanterna acompanham esse ciclo.

Verificação nativa no Android conectado (NDK instalado):

```powershell
powershell -ExecutionPolicy Bypass -File scripts/test-terrain.ps1
powershell -ExecutionPolicy Bypass -File scripts/test-world.ps1
powershell -ExecutionPolicy Bypass -File scripts/test-network.ps1
node --test tests/lan-terrain-codec.test.mjs
```

Os testes comparam 40 chunks com fixtures produzidas diretamente pelo código
original e verificam objetivos, início seco, sobrevivência, arena, regeneração,
estruturas acima de y=63 e coordenadas de renderização distantes.
O segundo comando verifica fluxo e altura da água, crateras, streaming,
proteções e fases do relógio. O terceiro cobre retransmissão e autoridade
com sockets UDP reais. Os scripts aceitam `-Abi armeabi-v7a` para ARM32.
O compilador desativa a contração de ponto flutuante para manter o terreno
idêntico entre ARM32 e ARM64, inclusive nos limites das ilhas.

### Compilar em computador com 4 GB de RAM

O projeto reserva 512 MB para o Gradle, usa GC serial, um worker, tarefas
sequenciais e um compilador C++ por vez. Execute a compilação e os testes
em sequência; evite abrir outra compilação ao mesmo tempo. Use JDK 17.
