package com.fabiosilva.ecosdeeldra

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.fabiosilva.ecosdeeldra.ads.AdConsent
import com.fabiosilva.ecosdeeldra.ads.InterstitialAds
import com.fabiosilva.ecosdeeldra.ads.RewardedAds
import com.fabiosilva.ecosdeeldra.audio.GameAudio
import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.SaveStore
import com.fabiosilva.ecosdeeldra.data.SkinDef
import com.fabiosilva.ecosdeeldra.data.Skins
import com.fabiosilva.ecosdeeldra.data.ZombieKinds
import com.fabiosilva.ecosdeeldra.engine.Cfg
import com.fabiosilva.ecosdeeldra.engine.Ev
import com.fabiosilva.ecosdeeldra.engine.GameSurfaceView
import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.NativeBridge
import com.fabiosilva.ecosdeeldra.engine.PoiKind
import com.fabiosilva.ecosdeeldra.engine.PvpCfg
import com.fabiosilva.ecosdeeldra.engine.Res
import com.fabiosilva.ecosdeeldra.net.LanLobby
import com.fabiosilva.ecosdeeldra.notify.Reminders
import com.fabiosilva.ecosdeeldra.ui.DefeatOverlay
import com.fabiosilva.ecosdeeldra.ui.HomeScreen
import com.fabiosilva.ecosdeeldra.ui.HudView
import com.fabiosilva.ecosdeeldra.ui.LoadingOverlay
import com.fabiosilva.ecosdeeldra.ui.MapOverlay
import com.fabiosilva.ecosdeeldra.ui.MissionsScreen
import com.fabiosilva.ecosdeeldra.ui.Palette
import com.fabiosilva.ecosdeeldra.ui.PauseOverlay
import com.fabiosilva.ecosdeeldra.ui.PvpPauseOverlay
import com.fabiosilva.ecosdeeldra.ui.PvpResultOverlay
import com.fabiosilva.ecosdeeldra.ui.PvpScreen
import com.fabiosilva.ecosdeeldra.ui.Popup
import com.fabiosilva.ecosdeeldra.ui.SettingsScreen
import com.fabiosilva.ecosdeeldra.ui.SkinsScreen
import com.fabiosilva.ecosdeeldra.ui.StagesScreen
import com.fabiosilva.ecosdeeldra.ui.StoryOverlay
import com.fabiosilva.ecosdeeldra.ui.Ui
import com.fabiosilva.ecosdeeldra.ui.UpgradesScreen
import com.fabiosilva.ecosdeeldra.ui.VictoryOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : ComponentActivity(), HudView.Listener {
    lateinit var progress: Progress
        private set
    lateinit var audio: GameAudio
        private set
    lateinit var ads: RewardedAds
        private set
    private lateinit var interstitials: InterstitialAds
    private lateinit var store: SaveStore
    private lateinit var surface: GameSurfaceView
    private lateinit var hud: HudView
    private lateinit var uiLayer: FrameLayout
    private lateinit var overlayLayer: FrameLayout

    /** Bônus de combate comprado com anúncio (vale para a próxima fase iniciada). */
    var boostReady = false

    private var inMission = false
    private var missionStage = 1
    private var missionPaused = false
    private var reviveUsed = false
    private var missionEnded = false
    private var defeatShown = false
    private var bestCombo = 0
    private var missionKills = 0
    private var loading: LoadingOverlay? = null
    private var currentScreen: View? = null
    private var homeScreen: HomeScreen? = null
    private var bigMap: Bitmap? = null
    private var worldSize = 0f

    // PvP em Wi-Fi local
    data class LobbyState(val players: List<LanLobby.LobbyPlayer>, val rules: LanLobby.Rules, val localId: Int, val isHost: Boolean)
    private var lanLobby: LanLobby? = null
    private var pvpScreen: PvpScreen? = null
    private var pvpInfo: LanLobby.StartInfo? = null
    private var inPvp = false
    private var pvpEnded = false
    var lastLobbyState: LobbyState? = null

    private val lobbyListener = object : LanLobby.Listener {
        override fun onRooms(rooms: List<LanLobby.Room>) {
            pvpScreen?.showRooms(rooms)
        }

        override fun onLobby(players: List<LanLobby.LobbyPlayer>, rules: LanLobby.Rules, localId: Int, isHost: Boolean) {
            lastLobbyState = LobbyState(players, rules, localId, isHost)
            if (!inPvp) pvpScreen?.showRoom(players, rules, localId, isHost)
        }

        override fun onStart(info: LanLobby.StartInfo) = startPvpMatch(info)

        override fun onClosed(reason: String) {
            lastLobbyState = null
            if (inPvp && !pvpEnded) return  // o motor detecta a queda e avisa
            pvpScreen?.showBrowse()
            Popup.show(this@MainActivity, "Sala", reason, "OK")
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        store = SaveStore(this)
        progress = store.load()
        audio = GameAudio(this)
        audio.sfxVolume = progress.settings.sfxVolume
        audio.musicVolume = progress.settings.musicVolume
        Ui.clickSound = { audio.playUi("click", 0.6f) }
        ads = RewardedAds(this)
        ads.onStateChanged = { runOnUiThread { homeScreen?.refresh() } }
        interstitials = InterstitialAds(this)

        NativeBridge.init(assets)
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.bg) }
        surface = GameSurfaceView(this) { events, n -> onEngineEvents(events, n) }
        surface.setQuality(progress.settings.quality)
        hud = HudView(this, this).apply { visibility = View.GONE }
        uiLayer = FrameLayout(this)
        overlayLayer = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        root.addView(uiLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlayLayer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        enterImmersive()
        val s = progress.settings
        engine { NativeBridge.setGraphics(s.quality, s.cinematic) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        val newDay = Rules.onAppOpen(progress, today())
        Rules.refreshSkinUnlocks(progress)
        store.save(progress)
        AdConsent.gather(this) {
            ads.load()
            interstitials.load()
        }
        showHome()
        if (Rules.canClaimCalendar(progress, today())) {
            surface.postDelayed({ showDailyReward() }, if (newDay) 900L else 600L)
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onResume() {
        super.onResume()
        enterImmersive()
        surface.onResume()
        surface.start()
        audio.resume()
        Reminders.cancel(this)
        // Virou o dia com o app em segundo plano.
        if (Rules.onAppOpen(progress, today())) {
            store.save(progress)
            homeScreen?.refresh()
        }
    }

    override fun onPause() {
        // Durante anúncio/derrota/carregamento a missão já está parada; não sobrepor a pausa.
        if (inMission && !missionPaused && !missionEnded && !defeatShown && loading == null && !ads.showing && !interstitials.showing) pauseMission()
        surface.stop()
        surface.onPause()
        audio.pause()
        store.save(progress)
        Reminders.schedule(this, progress.chestReadyAt, progress.settings.reminders)
        super.onPause()
    }

    override fun onDestroy() {
        lanLobby?.close()
        audio.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !ads.showing && !interstitials.showing) enterImmersive()
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun onBack() {
        when {
            loading != null || defeatShown -> Unit
            inPvp && !pvpEnded -> if (overlayLayer.childCount > 0) resumePvp() else showPvpPause()
            inPvp -> Unit
            overlayLayer.childCount > 0 && !inMission -> closeOverlay()
            inMission && !missionEnded -> if (missionPaused) resumeMission() else pauseMission()
            currentScreen !is HomeScreen -> showHome()
            else -> finish()
        }
    }

    // ------------------------------------------------------------------ utilidades

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun save() {
        store.save(progress)
    }

    fun engine(block: () -> Unit) = surface.engine(block)

    fun showOverlay(view: View) {
        overlayLayer.removeAllViews()
        overlayLayer.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    fun closeOverlay() {
        overlayLayer.removeAllViews()
    }

    private fun showScreen(view: View) {
        uiLayer.removeAllViews()
        uiLayer.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180).start()
        currentScreen = view
        homeScreen = view as? HomeScreen
        pvpScreen = view as? PvpScreen
    }

    /**
     * Mostra anúncio premiado; [onGranted] roda somente se a recompensa foi ganha.
     * [onClosed] roda sempre que o fluxo termina (com ou sem recompensa, ou sem anúncio).
     */
    fun watchAd(onClosed: ((Boolean) -> Unit)? = null, onGranted: () -> Unit) {
        if (!ads.isReady) {
            Popup.show(this, "Anúncio indisponível", "Nenhum anúncio carregado agora. Tente novamente em alguns segundos.", "OK") { onClosed?.invoke(false) }
            ads.load()
            return
        }
        audio.pause()
        ads.show { granted ->
            runOnUiThread {
                audio.resume()
                enterImmersive()
                progress.lastAdAt = System.currentTimeMillis()
                if (granted) {
                    Rules.registerAdWatched(progress)
                    onGranted()
                    checkUnlocks()
                }
                save()
                homeScreen?.refresh()
                onClosed?.invoke(granted)
            }
        }
    }

    /** Intersticial entre partidas, respeitando a frequência de [Rules.shouldShowInterstitial]. */
    private fun maybeInterstitial(then: () -> Unit) {
        val now = System.currentTimeMillis()
        if (!interstitials.enabled || !interstitials.isReady || !Rules.shouldShowInterstitial(progress, now)) {
            interstitials.load()
            then()
            return
        }
        progress.missionsSinceInterstitial = 0
        progress.lastAdAt = now
        save()
        audio.pause()
        interstitials.show {
            runOnUiThread {
                audio.resume()
                enterImmersive()
                then()
            }
        }
    }

    /** Libera skins por objetivo e celebra cada uma. */
    fun checkUnlocks() {
        val unlocked = Rules.refreshSkinUnlocks(progress)
        if (unlocked.isEmpty()) return
        save()
        announceUnlocks(unlocked)
    }

    private fun announceUnlocks(list: List<SkinDef>) {
        if (list.isEmpty()) return
        audio.playUi("unlock", 1f)
        val names = list.joinToString(", ") { it.name }
        Popup.show(this, "Nova skin liberada!", "$names agora faz parte do seu esquadrão. Equipe na tela de Heróis.", "VER HERÓIS", "DEPOIS") { ok ->
            if (ok && !inMission) showSkins()
        }
    }

    fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && progress.settings.reminders &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------------ telas

    fun showHome() {
        closeOverlay()
        hud.visibility = View.GONE
        audio.setMusic(GameAudio.Music.MENU)
        val skin = progress.selectedSkin
        engine { NativeBridge.enterLobby(skin, 0) }
        showScreen(HomeScreen(this))
    }

    fun showStages() {
        closeOverlay()
        hud.visibility = View.GONE
        audio.setMusic(GameAudio.Music.MENU)
        val skin = progress.selectedSkin
        engine { NativeBridge.enterLobby(skin, 0) }
        showScreen(StagesScreen(this))
    }

    fun showSkins() {
        val skin = progress.selectedSkin
        engine { NativeBridge.enterLobby(skin, 1) }
        showScreen(SkinsScreen(this))
    }

    fun showMissions() = showScreen(MissionsScreen(this))
    fun showUpgrades() = showScreen(UpgradesScreen(this))
    fun showSettings() = showScreen(SettingsScreen(this, fromPause = false))

    fun previewSkin(index: Int) = engine { NativeBridge.setPreviewSkin(index) }
    fun rotatePreview(dx: Float) = NativeBridge.rotatePreview(dx)

    fun showDailyReward(onDone: (() -> Unit)? = null) {
        if (!Rules.canClaimCalendar(progress, today())) return
        val reward = Rules.calendarReward(progress.calendarClaims)
        val day = progress.calendarClaims % 7 + 1
        Popup.show(
            this, "Prêmio diário • Dia $day",
            "Volte todos os dias para ganhar mais! Hoje: ◆ $reward diamantes.\nEntrar em 2, 5 e 7 dias diferentes libera skins exclusivas.",
            "RESGATAR",
        ) {
            val got = Rules.claimCalendar(progress, today())
            if (got > 0) audio.playUi("reward", 1f)
            save()
            checkUnlocks()
            homeScreen?.refresh()
            onDone?.invoke()
            requestNotificationsOnce()
        }
    }

    fun applySettings() {
        val s = progress.settings
        audio.sfxVolume = s.sfxVolume
        audio.musicVolume = s.musicVolume
        surface.setQuality(s.quality)
        hud.applySettings(s.leftHanded, s.vibration, s.showFps)
        engine {
            NativeBridge.setSettings(s.sensitivity, s.aimAssist, s.autoFire, s.invertY)
            NativeBridge.setGraphics(s.quality, s.cinematic)
        }
        save()
    }

    // ------------------------------------------------------------------ missão

    fun startStage(number: Int, skipStory: Boolean = false) {
        missionStage = number
        val stage = Campaign.stage(number)
        if (skipStory) {
            launchMission()
        } else {
            showOverlay(StoryOverlay(this, stage) { closeOverlay(); launchMission() })
        }
    }

    private fun launchMission() {
        val stage = Campaign.stage(missionStage)
        val p = progress
        val diff = Campaign.difficulty(stage.number, p.vitality, p.power, p.reload)
        val config = FloatArray(Cfg.COUNT)
        config[Cfg.STAGE] = stage.number.toFloat()
        config[Cfg.SKIN] = p.selectedSkin.toFloat()
        config[Cfg.ZOMBIES] = diff.zombieCount.toFloat()
        config[Cfg.ZOMBIE_HP] = diff.zombieHealth
        config[Cfg.ZOMBIE_DMG] = diff.zombieDamage
        config[Cfg.ZOMBIE_SPEED] = diff.zombieSpeed
        config[Cfg.PLAYER_HP] = diff.playerHealth
        config[Cfg.MAGAZINE] = diff.magazine.toFloat()
        config[Cfg.POWER] = diff.power
        config[Cfg.CRYSTAL_MASK] = 0f
        config[Cfg.CRYSTAL_TOTAL] = stage.crystalTotal.toFloat()
        config[Cfg.SENSITIVITY] = p.settings.sensitivity
        config[Cfg.AIM_ASSIST] = if (p.settings.aimAssist) 1f else 0f
        config[Cfg.AUTO_FIRE] = if (p.settings.autoFire) 1f else 0f
        config[Cfg.INVERT_Y] = if (p.settings.invertY) 1f else 0f
        // Semente nova a cada partida: a ilha nunca se repete (até 2^24 cabe exato em float).
        config[Cfg.SEED] = Random.nextInt(1, 16_000_000).toFloat()
        config[Cfg.RELOAD_SPEED] = diff.reloadSpeed
        config[Cfg.BOOST_HEALTH] = if (boostReady) 1.3f else 1f
        config[Cfg.BOOST_DAMAGE] = if (boostReady) 1.2f else 1f
        config[Cfg.QUALITY] = p.settings.quality.toFloat()
        config[Cfg.CINEMATIC] = if (p.settings.cinematic) 1f else 0f
        val boosted = boostReady
        boostReady = false

        inMission = true
        missionPaused = false
        missionEnded = false
        defeatShown = false
        reviveUsed = false
        bestCombo = 0
        missionKills = 0
        bigMap = null
        uiLayer.removeAllViews()
        currentScreen = null
        homeScreen = null
        loading = LoadingOverlay(this, stage).also { showOverlay(it) }
        hud.reset()
        hud.playerName = Skins.get(p.selectedSkin).name
        hud.applySettings(p.settings.leftHanded, p.settings.vibration, p.settings.showFps)
        audio.setMusic(GameAudio.Music.BATTLE)
        // Deixa a tela de carregamento aparecer antes de gerar o mundo.
        surface.postDelayed({ engine { NativeBridge.startMission(config) } }, 120)
        if (boosted) surface.postDelayed({ if (inMission) hud.message("BÔNUS DE COMBATE ATIVO!", Palette.cyan) }, 2500)
    }

    /** Chamado na thread GL com eventos do motor. */
    private fun onEngineEvents(events: IntArray, count: Int) {
        var i = 0
        while (i + 3 < count) {
            val type = events[i]
            val a = events[i + 1]
            val b = events[i + 2]
            val c = events[i + 3]
            i += 4
            when (type) {
                Ev.SFX -> audio.playEngine(a, b, c)
                Ev.MISSION_READY -> {
                    val style = a
                    val size = NativeBridge.minimapSize()
                    val minimap = copyBitmap(size) { NativeBridge.copyMinimap(it) }
                    val bigSize = NativeBridge.bigMapSize()
                    val big = if (bigSize > 0) copyBitmap(bigSize) { NativeBridge.copyBigMap(it) } else null
                    runOnUiThread {
                        bigMap = big
                        worldSize = if (big != null) 6144f else size.toFloat()
                        if (big == null) {
                            // PvP: o minimapa é a arena inteira. Na ilha a origem vem do evento MINIMAP.
                            hud.minimapOriginX = 0f
                            hud.minimapOriginZ = 0f
                        }
                        onMissionReady(minimap)
                        if (style >= 0) hud.message("MAPA: " + mapStyleName(style), Palette.cyan)
                    }
                }
                Ev.MINIMAP -> {
                    val size = NativeBridge.minimapSize()
                    val minimap = copyBitmap(size) { NativeBridge.copyMinimap(it) }
                    runOnUiThread {
                        hud.minimap = minimap
                        hud.minimapOriginX = (a - size / 2).toFloat()
                        hud.minimapOriginZ = (b - size / 2).toFloat()
                    }
                }
                else -> runOnUiThread { onGameEvent(type, a, b, c) }
            }
        }
    }

    private fun copyBitmap(size: Int, fill: (IntArray) -> Unit): Bitmap? {
        if (size <= 0) return null
        val pixels = IntArray(size * size)
        fill(pixels)
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun onMissionReady(minimap: Bitmap?) {
        if (!inMission && !inPvp) return
        hud.minimap = minimap
        closeOverlay()
        loading = null
        hud.visibility = View.VISIBLE
        hud.message(if (inPvp) "QUE VENÇA O MELHOR!" else "DESTRUA OS 3 NINHOS!", Palette.lime)
        if (!inPvp) hud.tip("Siga as setas roxas até os ninhos • explore para achar baús")
        if (!progress.tutorialSeen) {
            progress.tutorialSeen = true
            save()
            hud.message("Arraste à direita para mirar", Color.WHITE)
        }
    }

    private fun onGameEvent(type: Int, a: Int, b: Int, c: Int) {
        if (inPvp) {
            onPvpEvent(type, a, b, c)
            return
        }
        if (!inMission) return
        when (type) {
            Ev.KILL -> {
                bestCombo = maxOf(bestCombo, c)
                missionKills += 1
                hud.message(if (a == 1) "TIRO NA CABEÇA! ☠ $missionKills" else "+1 ABATE ☠ $missionKills", if (a == 1) Palette.gold else Palette.lime)
                hud.haptic(false)
            }
            Ev.CRYSTAL -> {
                hud.message("CRISTAL COLETADO ◆", Palette.cyan)
                audio.playUi("reward", 0.7f)
            }
            Ev.NEST_DESTROYED -> {
                hud.message("NINHO DESTRUÍDO! PEGUE O CRISTAL", Palette.purple)
                hud.haptic(true)
            }
            Ev.CHEST -> {
                hud.message("BAÚ ABERTO! +◆ $a", Palette.gold)
                audio.playUi("reward", 0.8f)
            }
            Ev.POI -> {
                val label = when (a) {
                    PoiKind.FIRE -> "ACAMPAMENTO ABANDONADO"
                    PoiKind.RUINS -> "RUÍNAS ANTIGAS"
                    PoiKind.GRAVEYARD -> "CEMITÉRIO"
                    PoiKind.DEPOT -> "DEPÓSITO DE SUPRIMENTOS"
                    PoiKind.FARM -> "FAZENDA"
                    PoiKind.TOWER -> "TORRE DE VIGIA"
                    PoiKind.OUTPOST -> "POSTO AVANÇADO"
                    PoiKind.NEST -> "NINHO DE INFECÇÃO!"
                    else -> "PORTAL DE EXTRAÇÃO"
                }
                hud.message(label, if (a == PoiKind.NEST) Palette.purple else Color.WHITE)
                if (a == PoiKind.TOWER) hud.tip("Suba a escada: lá de cima dá para ver longe — e tem um baú!")
            }
            Ev.SUPPLY -> {
                if (a == 0) hud.message("SUPRIMENTOS CAINDO! SIGA A FUMAÇA VERDE", Palette.cyan)
                else {
                    hud.message("MUNIÇÃO INCENDIÁRIA + VIDA!", Palette.gold)
                    hud.haptic(true)
                }
            }
            Ev.ELITE -> hud.message("ELITE: ${ZombieKinds.get(a).name.uppercase()}", Palette.gold)
            Ev.NEW_KIND -> {
                val info = ZombieKinds.get(a)
                hud.tip("NOVO ZUMBI: ${info.name} — ${info.ability}")
            }
            Ev.STATUS -> {
                when (a) {
                    1 -> hud.message("QUEIMANDO! PULE NA ÁGUA", Color.rgb(255, 150, 60))
                    2 -> hud.message("SANGRANDO!", Palette.red)
                    3 -> hud.message("ENVENENADO!", Color.rgb(140, 255, 90))
                    4 -> hud.message("ÁCIDO! VOCÊ ESTÁ LENTO", Color.rgb(140, 255, 90))
                }
                hud.haptic(true)
            }
            Ev.PORTAL_OPEN -> hud.message("PORTAL ABERTO!", Palette.purple)
            Ev.HEAL -> hud.message("+$a VIDA", Palette.red)
            Ev.VICTORY -> surface.postDelayed({ finishMission(true) }, 700)
            Ev.DEFEAT -> surface.postDelayed({ showDefeat() }, 900)
        }
    }

    private fun readResult(victory: Boolean): MissionResult {
        val r = FloatArray(Res.SIZE)
        NativeBridge.copyResult(r)
        return MissionResult(
            victory = victory,
            elapsedSeconds = r[Res.ELAPSED],
            health = r[Res.HEALTH],
            maxHealth = r[Res.MAX_HEALTH],
            kills = r[Res.KILLS].toInt(),
            headshots = r[Res.HEADSHOTS].toInt(),
            crystalMask = r[Res.CRYSTAL_MASK].toInt(),
            bestCombo = bestCombo,
            shots = r[Res.SHOTS].toInt(),
            hits = r[Res.HITS].toInt(),
            chests = r[Res.CHESTS].toInt(),
            chestDiamonds = r[Res.CHEST_DIAMONDS].toInt(),
            distance = r[Res.DISTANCE],
            nests = r[Res.NESTS].toInt(),
        )
    }

    private fun finishMission(victory: Boolean) {
        if (missionEnded) return
        missionEnded = true
        hud.visibility = View.GONE
        hud.reset()
        val stage = Campaign.stage(missionStage)
        val result = readResult(victory)
        val summary = Rules.applyResult(progress, stage, result)
        val unlocked = Rules.refreshSkinUnlocks(progress)
        progress.missionsSinceInterstitial += 1
        save()
        audio.playUi(if (victory) "victory" else "defeat", 1f)
        audio.setMusic(GameAudio.Music.MENU)
        showOverlay(VictoryOverlay(this, stage, result, summary, unlocked))
    }

    private fun showDefeat() {
        if (missionEnded || !inMission) return
        defeatShown = true
        hud.visibility = View.GONE
        NativeBridge.setFire(false)
        audio.playUi("defeat", 1f)
        showOverlay(DefeatOverlay(this, Campaign.stage(missionStage), canRevive = !reviveUsed, canSkip = Rules.canSkip(progress, missionStage, pendingFails = 1)))
    }

    fun reviveWithAd() {
        watchAd {
            reviveUsed = true
            defeatShown = false
            missionPaused = false
            closeOverlay()
            hud.reset()
            hud.visibility = View.VISIBLE
            hud.message("DE VOLTA À LUTA!", Palette.lime)
            engine {
                NativeBridge.setPaused(false)
                NativeBridge.revive()
            }
        }
    }

    /** Registra a derrota (se ainda não registrada) contando abates, baús e distância. */
    private fun recordDefeat() {
        if (missionEnded) return
        missionEnded = true
        val result = readResult(false)
        Rules.applyResult(progress, Campaign.stage(missionStage), result)
        progress.missionsSinceInterstitial += 1
        checkUnlocks()
        save()
    }

    /** Sai da missão contabilizando abates e tempo (derrota ou abandono). */
    fun abandonMission(retry: Boolean) {
        recordDefeat()
        endMissionUi()
        maybeInterstitial {
            if (retry) startStage(missionStage, skipStory = true) else showStages()
        }
    }

    fun skipStageWithAd() {
        watchAd {
            recordDefeat()
            if (Rules.skipStage(progress, missionStage)) {
                save()
                audio.playUi("unlock", 1f)
                endMissionUi()
                startStage(missionStage + 1)
            }
        }
    }

    fun leaveAfterVictory(next: Boolean) {
        endMissionUi()
        maybeInterstitial {
            if (next && progress.isStageCompleted(missionStage)) startStage(missionStage + 1)
            else showStages()
        }
    }

    private fun endMissionUi() {
        inMission = false
        missionPaused = false
        defeatShown = false
        hud.visibility = View.GONE
        hud.reset()
        closeOverlay()
    }

    fun doubleRewardWithAd(amount: Int, onDone: () -> Unit) {
        watchAd {
            progress.diamonds += amount
            audio.playUi("reward", 1f)
            onDone()
        }
    }

    override fun onPauseRequested() = if (inPvp) showPvpPause() else pauseMission()

    override fun onMapRequested() {
        if (inPvp && !pvpEnded) {
            hud.reset()
            showOverlay(MapOverlay(this, hud.minimap, worldSize) { resumePvp() })
            return
        }
        if (!inMission || missionEnded) return
        missionPaused = true
        engine { NativeBridge.setPaused(true) }
        hud.reset()
        showOverlay(MapOverlay(this, bigMap ?: hud.minimap, worldSize) {
            closeOverlay()
            resumeMission()
        })
    }

    /** Botão SUPRIMENTOS: anúncio premiado → caixa de paraquedas com munição incendiária e vida. */
    override fun onSupplyRequested() {
        if (!inMission || missionEnded || missionPaused || defeatShown) return
        if (!ads.isReady) {
            hud.message("ANÚNCIO INDISPONÍVEL, TENTE JÁ JÁ", Palette.muted)
            ads.load()
            return
        }
        missionPaused = true
        engine { NativeBridge.setPaused(true) }
        hud.reset()
        watchAd(onClosed = { if (inMission && !missionEnded) resumeMission() }) {
            NativeBridge.requestSupply()
        }
    }

    fun openMapFromPause() {
        if (!inMission || missionEnded) return
        showOverlay(MapOverlay(this, bigMap ?: hud.minimap, worldSize) { showOverlay(PauseOverlay(this, Campaign.stage(missionStage))) })
    }

    fun pauseMission() {
        if (!inMission || missionEnded) return
        missionPaused = true
        engine { NativeBridge.setPaused(true) }
        hud.reset()
        showOverlay(PauseOverlay(this, Campaign.stage(missionStage)))
    }

    fun resumeMission() {
        if (!inMission) return
        missionPaused = false
        closeOverlay()
        hud.visibility = View.VISIBLE
        engine { NativeBridge.setPaused(false) }
    }

    fun showPauseSettings() {
        showOverlay(SettingsScreen(this, fromPause = true))
    }

    private fun mapStyleName(style: Int): String = when (style) {
        1 -> "ARQUIPÉLAGO"
        2 -> "VALE"
        3 -> "MESETAS"
        4 -> "CRATERA"
        5 -> "RIOS"
        else -> "ILHA"
    }

    // ------------------------------------------------------------------ PvP

    fun showPvp() {
        if (lanLobby == null) {
            val lobby = LanLobby(this, lobbyListener)
            if (!lobby.start()) {
                Popup.show(this, "Rede local indisponível", "Não foi possível abrir a rede local. Verifique se o Wi-Fi está ligado.", "OK")
                return
            }
            lanLobby = lobby
        }
        hud.visibility = View.GONE
        audio.setMusic(GameAudio.Music.MENU)
        val skin = progress.selectedSkin
        engine { NativeBridge.enterLobby(skin, 0) }
        showScreen(PvpScreen(this, lanLobby!!))
    }

    fun closePvp() {
        if (inPvp) engine { NativeBridge.enterLobby(progress.selectedSkin, 0) }
        inPvp = false
        pvpEnded = false
        lanLobby?.close()
        lanLobby = null
        lastLobbyState = null
        pvpScreen = null
        showHome()
    }

    private fun startPvpMatch(info: LanLobby.StartInfo) {
        if (inMission) return
        pvpInfo = info
        inPvp = true
        pvpEnded = false
        val cfg = FloatArray(PvpCfg.COUNT)
        cfg[PvpCfg.LOCAL_ID] = info.localId.toFloat()
        cfg[PvpCfg.IS_HOST] = if (info.isHost) 1f else 0f
        cfg[PvpCfg.SEED] = info.seed.toFloat()
        cfg[PvpCfg.KILL_LIMIT] = info.rules.killLimit.toFloat()
        cfg[PvpCfg.TIME_LIMIT] = info.rules.timeMinutes * 60f
        cfg[PvpCfg.SENSITIVITY] = progress.settings.sensitivity
        cfg[PvpCfg.INVERT_Y] = if (progress.settings.invertY) 1f else 0f
        for (i in 0 until 8) cfg[PvpCfg.SKINS + i] = 255f
        info.players.forEach { cfg[PvpCfg.SKINS + it.id] = it.skin.toFloat() }

        uiLayer.removeAllViews()
        currentScreen = null
        homeScreen = null
        pvpScreen = null
        val hostName = info.players.firstOrNull { it.id == 0 }?.name ?: "anfitrião"
        loading = LoadingOverlay(this, "PvP • ${info.players.size} JOGADORES", "Arena de $hostName", Palette.red, info.seed).also { showOverlay(it) }
        hud.reset()
        hud.pvpNames = info.players.associate { it.id to it.name }
        hud.playerName = info.players.firstOrNull { it.id == info.localId }?.name ?: "Você"
        hud.applySettings(progress.settings.leftHanded, progress.settings.vibration, progress.settings.showFps)
        audio.setMusic(GameAudio.Music.BATTLE)
        val host = info.hostAddress
        surface.postDelayed({ engine { NativeBridge.startPvp(cfg, host) } }, 120)
    }

    private fun pvpName(id: Int): String = pvpInfo?.players?.firstOrNull { it.id == id }?.name ?: "Jogador ${id + 1}"

    private fun onPvpEvent(type: Int, a: Int, b: Int, c: Int) {
        val local = pvpInfo?.localId ?: return
        when (type) {
            Ev.PVP_KILL -> when {
                a == local -> {
                    hud.message(if (c == 1) "TIRO NA CABEÇA EM ${pvpName(b)}!" else "VOCÊ ELIMINOU ${pvpName(b)}", if (c == 1) Palette.gold else Palette.lime)
                    hud.haptic(true)
                }
                b == local -> hud.message("${pvpName(a)} ELIMINOU VOCÊ", Palette.red)
                else -> hud.message("${pvpName(a)} eliminou ${pvpName(b)}", android.graphics.Color.WHITE)
            }
            Ev.PVP_LEFT -> hud.message("${pvpName(a)} saiu da partida", Palette.muted)
            Ev.PVP_END -> surface.postDelayed({ finishPvp(a) }, 800)
            Ev.PVP_LOST -> if (!pvpEnded) {
                pvpEnded = true
                hud.visibility = View.GONE
                closeOverlay()
                Popup.show(this, "Conexão perdida", "Não foi possível falar com o anfitrião. Confira se estão no mesmo Wi-Fi.", "OK") { backToPvpRoom() }
            }
        }
    }

    private fun finishPvp(winner: Int) {
        if (!inPvp || pvpEnded) return
        pvpEnded = true
        val info = pvpInfo ?: return
        val h = FloatArray(Hud.SIZE)
        NativeBridge.copyHud(h)
        val ranking = (0 until 8).filter { h[Hud.PVP_PLAYERS + it * Hud.PVP_STRIDE] > 0.5f }
            .map { Triple(it, h[Hud.PVP_PLAYERS + it * Hud.PVP_STRIDE + 3].toInt(), h[Hud.PVP_PLAYERS + it * Hud.PVP_STRIDE + 4].toInt()) }
            .sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.second }.thenBy { it.third })
        val myKills = ranking.firstOrNull { it.first == info.localId }?.second ?: 0
        val won = winner == info.localId
        val reward = Rules.applyPvpResult(progress, myKills, won)
        save()
        hud.visibility = View.GONE
        hud.reset()
        audio.playUi(if (won) "victory" else "defeat", 1f)
        audio.setMusic(GameAudio.Music.MENU)
        showOverlay(PvpResultOverlay(this, ranking, info.players.associate { it.id to it.name }, winner, info.localId, reward, info.isHost))
    }

    fun backToPvpRoom() {
        closeOverlay()
        hud.visibility = View.GONE
        hud.reset()
        inPvp = false
        pvpEnded = false
        val lobby = lanLobby
        if (lobby == null) {
            showHome()
            return
        }
        if (lobby.isHost) lobby.endMatch()
        showPvp()
    }

    fun showPvpPause() {
        if (!inPvp || pvpEnded) return
        hud.reset()
        showOverlay(PvpPauseOverlay(this))
    }

    fun resumePvp() {
        closeOverlay()
        if (inPvp && !pvpEnded) hud.visibility = View.VISIBLE
    }

    fun leavePvpMatch() {
        pvpEnded = true
        inPvp = false
        closeOverlay()
        hud.visibility = View.GONE
        hud.reset()
        val lobby = lanLobby
        if (lobby != null && lobby.isHost) {
            // Anfitrião saiu: a sala fecha para todos.
            closePvp()
        } else {
            lobby?.leaveRoom()
            lastLobbyState = null
            engine { NativeBridge.enterLobby(progress.selectedSkin, 0) }
            showPvp()
        }
    }
}
