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
import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.ContractRun
import com.fabiosilva.ecosdeeldra.data.Contracts
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import com.fabiosilva.ecosdeeldra.data.ExpeditionRules
import com.fabiosilva.ecosdeeldra.data.Inventory
import com.fabiosilva.ecosdeeldra.data.Items
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.SaveStore
import com.fabiosilva.ecosdeeldra.data.Cosmetic
import com.fabiosilva.ecosdeeldra.data.SurvivalResult
import com.fabiosilva.ecosdeeldra.data.Upgrades
import com.fabiosilva.ecosdeeldra.data.ZombieKinds
import com.fabiosilva.ecosdeeldra.data.save.LoadResult
import com.fabiosilva.ecosdeeldra.data.save.RecoveryReport
import com.fabiosilva.ecosdeeldra.data.save.SaveMessages
import com.fabiosilva.ecosdeeldra.data.save.SaveNotice
import com.fabiosilva.ecosdeeldra.engine.Cfg
import com.fabiosilva.ecosdeeldra.engine.applyContract
import com.fabiosilva.ecosdeeldra.engine.Ev
import com.fabiosilva.ecosdeeldra.engine.GameSurfaceView
import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.NativeBridge
import com.fabiosilva.ecosdeeldra.engine.PerfRun
import com.fabiosilva.ecosdeeldra.engine.PoiKind
import com.fabiosilva.ecosdeeldra.engine.PvpCfg
import com.fabiosilva.ecosdeeldra.engine.Res
import com.fabiosilva.ecosdeeldra.net.LanLobby
import com.fabiosilva.ecosdeeldra.notify.Reminders
import com.fabiosilva.ecosdeeldra.tutorial.CoachController
import com.fabiosilva.ecosdeeldra.tutorial.Lesson
import com.fabiosilva.ecosdeeldra.tutorial.Objective
import com.fabiosilva.ecosdeeldra.tutorial.ObjectiveText
import com.fabiosilva.ecosdeeldra.tutorial.TutorialPolicy
import com.fabiosilva.ecosdeeldra.ui.CombatFeed
import com.fabiosilva.ecosdeeldra.ui.DefeatOverlay
import com.fabiosilva.ecosdeeldra.ui.ExpeditionPrepScreen
import com.fabiosilva.ecosdeeldra.ui.ExpeditionResultOverlay
import com.fabiosilva.ecosdeeldra.ui.HomeScreen
import com.fabiosilva.ecosdeeldra.ui.HudEditorScreen
import com.fabiosilva.ecosdeeldra.ui.CharacterScreen
import com.fabiosilva.ecosdeeldra.ui.HudView
import com.fabiosilva.ecosdeeldra.ui.InventoryScreen
import com.fabiosilva.ecosdeeldra.ui.LoadingOverlay
import com.fabiosilva.ecosdeeldra.ui.MapOverlay
import com.fabiosilva.ecosdeeldra.ui.MissionsScreen
import com.fabiosilva.ecosdeeldra.ui.Palette
import com.fabiosilva.ecosdeeldra.ui.PauseOverlay
import com.fabiosilva.ecosdeeldra.ui.PvpPauseOverlay
import com.fabiosilva.ecosdeeldra.ui.PvpResultOverlay
import com.fabiosilva.ecosdeeldra.ui.PvpScreen
import com.fabiosilva.ecosdeeldra.ui.Popup
import com.fabiosilva.ecosdeeldra.ui.RecoveryScreen
import com.fabiosilva.ecosdeeldra.ui.RewardCongratsOverlay
import com.fabiosilva.ecosdeeldra.ui.RewardPreviewOverlay
import com.fabiosilva.ecosdeeldra.ui.SettingsScreen
import com.fabiosilva.ecosdeeldra.ui.StagesScreen
import com.fabiosilva.ecosdeeldra.ui.SurvivalDefeatOverlay
import com.fabiosilva.ecosdeeldra.ui.SurvivalResultOverlay
import com.fabiosilva.ecosdeeldra.ui.SurvivalScreen
import com.fabiosilva.ecosdeeldra.ui.Ui
import com.fabiosilva.ecosdeeldra.ui.UpgradesScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), HudView.Listener {
    lateinit var progress: Progress
        private set
    lateinit var audio: GameAudio
        private set
    lateinit var ads: RewardedAds
        private set
    private lateinit var interstitials: InterstitialAds
    lateinit var store: SaveStore
        private set
    private lateinit var surface: GameSurfaceView
    private lateinit var hud: HudView
    private lateinit var uiLayer: FrameLayout
    private lateinit var overlayLayer: FrameLayout

    private var inMission = false
    private var stageLaunchPending = false
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
    private var survivalMode = false
    private var survivalWave = 0

    /** Expedição escolhida/em curso: tipo, fase, ilha (semente) e contrato. */
    var plan: ExpeditionPlan = ExpeditionPlan.standard(1)
        private set

    // Tutorial jogável (fase 1): o controlador vive só enquanto a missão está rodando.
    private var coach: CoachController? = null
    /** Em "rever tutorial": lições feitas em memória; nada é gravado no save enquanto não for null. */
    private var replayMask: Int? = null
    private var eventNests = 0
    private var eventCrystals = 0
    private var inventoryOpen = false

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

        override fun onAutoJoin(room: LanLobby.Room) {
            pvpScreen?.showAutoJoining(room)
        }

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
        store.onSaveFailed = {
            Popup.show(this, "Não foi possível salvar", "O armazenamento do aparelho parece cheio ou indisponível. Libere espaço para não perder o progresso.", "OK")
        }
        val loaded = store.load()
        // Em recuperação o Progress é provisório: store.recoveryPending bloqueia qualquer gravação até o usuário escolher.
        progress = when (loaded) {
            is LoadResult.Loaded -> loaded.progress
            is LoadResult.Fresh -> Progress()
            is LoadResult.NeedsRecovery -> Progress()
        }
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

        AdConsent.gather(this) {
            ads.load()
            interstitials.load()
        }
        if (loaded is LoadResult.NeedsRecovery) {
            showRecovery(loaded.report)
            return
        }
        startSession((loaded as? LoadResult.Loaded)?.notices.orEmpty())
        // Diagnóstico de desempenho: extras de Intent só existem/valem em build de depuração.
        if (BuildConfig.DEBUG) {
            perfRun = PerfRun.parse { key, def -> intent.getIntExtra(key, def) }
            perfRun?.let { run -> surface.postDelayed({ startPerfRun(run) }, 1500) }
        }
    }

    private var perfRun: PerfRun? = null

    private fun startPerfRun(run: PerfRun) {
        if (run.quality >= 0) surface.setQuality(run.quality)  // a escala de resolução vive no Kotlin
        if (run.mode == PerfRun.MODE_BENCH) {
            perfRun = null
            engine { NativeBridge.perfBench(run.seed, run.stage, 8, run.meters, run.policy, run.flags) }
            return
        }
        survivalMode = false
        missionStage = run.stage
        plan = ExpeditionPlan.standard(run.stage)
        launchMission()  // sem startExpedition: ele contaria o toque em JOGAR e poderia exibir o intersticial
    }

    /** Tudo que o arranque fazia depois de carregar o save; também roda ao sair da tela de recuperação. */
    private fun startSession(notices: List<SaveNotice>) {
        val newDay = Rules.onAppOpen(progress, today())
        Rules.refreshCosmeticUnlocks(progress)
        store.save(progress)
        showHome()
        showSaveNotices(notices)
        if (Rules.canClaimCalendar(progress, today())) {
            surface.postDelayed({ showDailyReward() }, if (newDay) 900L else 600L)
        }
    }

    private fun showRecovery(report: RecoveryReport) {
        hud.visibility = View.GONE
        showScreen(RecoveryScreen(this, report))
    }

    /** Chamado pela RecoveryScreen quando o usuário resolveu (restaurou, tentou de novo com sucesso ou começou novo). */
    fun onRecoveryResolved(newProgress: Progress, notices: List<SaveNotice> = emptyList()) {
        progress = newProgress
        applySettings()      // volume, qualidade e HUD vêm do Progress real; também grava
        startSession(notices)
    }

    private fun showSaveNotices(notices: List<SaveNotice>) {
        val texts = notices.mapNotNull { SaveMessages.describe(it) }
        fun next(i: Int) {
            if (i >= texts.size) return
            val (title, body) = texts[i]
            Popup.show(this, title, body, "OK") { next(i + 1) }
        }
        next(0)
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
        if (!store.recoveryPending && Rules.onAppOpen(progress, today())) {
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
        store.saveNow(progress)
        if (!store.recoveryPending) Reminders.schedule(this, progress.chestReadyAt, progress.settings.reminders)
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
            store.recoveryPending -> finish()
            loading != null || defeatShown -> Unit
            inventoryOpen && inMission -> (overlayLayer.getChildAt(0) as? InventoryScreen)?.let { closeInventoryInMatch(it, -1) } ?: Unit
            inPvp && !pvpEnded -> if (overlayLayer.childCount > 0) resumePvp() else showPvpPause()
            inPvp -> Unit
            overlayLayer.childCount > 0 && !inMission -> closeOverlay()
            inMission && !missionEnded -> if (missionPaused) resumeMission() else pauseMission()
            currentScreen is InventoryScreen -> (currentScreen as InventoryScreen).close()
            currentScreen is ExpeditionPrepScreen -> (currentScreen as ExpeditionPrepScreen).goBack()
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

    /** Libera visuais por objetivo e celebra cada um. */
    fun checkUnlocks() {
        val unlocked = Rules.refreshCosmeticUnlocks(progress)
        if (unlocked.isEmpty()) return
        save()
        announceUnlocks(unlocked)
    }

    private fun announceUnlocks(list: List<Cosmetic>) {
        if (list.isEmpty()) return
        audio.playUi("unlock", 1f)
        val names = list.joinToString(", ") { it.name }
        Popup.show(this, "Novo visual liberado!", "$names já pode ser usado no editor de PERSONAGEM.", "VER PERSONAGEM", "DEPOIS") { ok ->
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
        replayMask = null
        closeOverlay()
        hud.visibility = View.GONE
        audio.setMusic(GameAudio.Music.MENU)
        val look = progress.lookCode
        engine { NativeBridge.enterLobby(look, 0) }
        showScreen(HomeScreen(this))
    }

    fun showStages() {
        replayMask = null
        closeOverlay()
        hud.visibility = View.GONE
        audio.setMusic(GameAudio.Music.MENU)
        val look = progress.lookCode
        engine { NativeBridge.enterLobby(look, 0) }
        showScreen(StagesScreen(this))
    }

    /** Editor de personagem (estilo Minecraft). */
    fun showSkins() {
        closeOverlay()
        hud.visibility = View.GONE
        val look = progress.lookCode
        engine { NativeBridge.enterLobby(look, 1) }
        showScreen(CharacterScreen(this))
    }

    fun showMissions() = showScreen(MissionsScreen(this))

    fun showSurvival() {
        replayMask = null
        closeOverlay()
        hud.visibility = View.GONE
        audio.setMusic(GameAudio.Music.MENU)
        val look = progress.lookCode
        engine { NativeBridge.enterLobby(look, 0) }
        showScreen(SurvivalScreen(this))
    }

    /**
     * JOGAR / SOBREVIVER: abre a preparação (contrato, equipamento e código da ilha). Antes da primeira vitória o jogo
     * parte direto, sem tela de contratos. [countPlayTap] só vale na campanha (intersticial a cada 5º toque em JOGAR).
     */
    fun showExpeditionPrep(
        kind: ExpeditionKind = ExpeditionKind.CAMPAIGN,
        stage: Int = progress.frontierStage(),
        initial: ExpeditionPlan? = null,
        countPlayTap: Boolean = true,
        back: () -> Unit = { showHome() },
    ) {
        if (kind == ExpeditionKind.CAMPAIGN && initial == null && !Contracts.prepUnlocked(progress)) {
            startExpedition(ExpeditionPlan.standard(stage), countPlayTap)   // jogador novo: parte direto
            return
        }
        replayMask = null
        closeOverlay()
        hud.visibility = View.GONE
        audio.setMusic(GameAudio.Music.MENU)
        val look = progress.lookCode
        engine { NativeBridge.enterLobby(look, 0) }
        showScreen(ExpeditionPrepScreen(this, kind, stage, initial, countPlayTap, back))
    }

    /** Inventário (mochila / arsenal / loja) fora da partida: tela própria com o herói em 3D. */
    fun showInventory(tab: Int, back: () -> Unit = { showHome() }) {
        closeOverlay()
        hud.visibility = View.GONE
        val look = progress.lookCode
        engine { NativeBridge.enterLobby(look, 0) }
        showScreen(InventoryScreen(this, progress.inventory, inMatch = false, initialTab = tab) { back() })
    }
    fun showUpgrades() = showScreen(UpgradesScreen(this))
    fun showSettings() = showScreen(SettingsScreen(this, fromPause = false))

    fun previewLook(code: Int) = engine { NativeBridge.setPreviewSkin(code) }
    fun rotatePreview(dx: Float) = NativeBridge.rotatePreview(dx)

    fun showDailyReward(force: Boolean = false, onDone: (() -> Unit)? = null) {
        val canClaim = Rules.canClaimCalendar(progress, today())
        if (!canClaim && !force) return
        showOverlay(
            RewardPreviewOverlay(this, canClaim, onClaim = {
                val day = progress.calendarClaims + 1
                val got = Rules.claimCalendar(progress, today())
                if (got > 0) audio.playUi("reward", 1f)
                save()
                homeScreen?.refresh()
                onDone?.invoke()
                showReward("◆ $got DIAMANTES", "Prêmio diário • Dia $day") {
                    checkUnlocks()
                    requestNotificationsOnce()
                }
            }, onClose = { closeOverlay() }),
        )
    }

    fun showReward(label: String, caption: String, onOk: (() -> Unit)? = null) {
        showOverlay(RewardCongratsOverlay(this, label, caption) {
            closeOverlay()
            onOk?.invoke()
        })
    }

    fun applySettings() {
        val s = progress.settings
        audio.sfxVolume = s.sfxVolume
        audio.musicVolume = s.musicVolume
        surface.setQuality(s.quality)
        hud.applySettings(s.leftHanded, s.vibration, s.showFps, s.hudLayout, s.hudOpacity)
        engine {
            NativeBridge.setSettings(s.sensitivity, s.aimAssist, s.autoFire, s.invertY)
            NativeBridge.setGraphics(s.quality, s.cinematic)
        }
        save()
    }

    // ------------------------------------------------------------------ missão

    /** PARTIR: fixa o plano e lança a missão. [countPlayTap] só vale na campanha (intersticial a cada 5º toque). */
    fun startExpedition(chosen: ExpeditionPlan, countPlayTap: Boolean = true) {
        if (stageLaunchPending) return
        plan = chosen
        progress.expeditions.remember(chosen)
        survivalMode = chosen.kind == ExpeditionKind.SURVIVAL
        survivalWave = 0
        missionStage = chosen.stage
        closeOverlay()
        val showPlayAd = countPlayTap && !survivalMode && Rules.registerCampaignPlayTap(progress)
        save()
        val launch = {
            stageLaunchPending = false
            launchMission()
        }
        if (!showPlayAd || !interstitials.enabled || !interstitials.isReady) {
            if (showPlayAd) interstitials.load()
            launch()
            return
        }
        progress.lastAdAt = System.currentTimeMillis()
        progress.missionsSinceInterstitial = 0
        save()
        stageLaunchPending = true
        audio.pause()
        interstitials.show {
            runOnUiThread {
                audio.resume()
                enterImmersive()
                launch()
            }
        }
    }

    private fun launchMission() {
        val stage = Campaign.stage(missionStage)
        val p = progress
        // Tutorial: só em campanha; veteranos nunca veem. O ajuste de dificuldade vale só nesta corrida.
        val teachMask: Int? = if (survivalMode) null
            else (replayMask ?: TutorialPolicy.maskToTeach(p.tutorialVersion, p.tutorialMask, p.completedStages()))?.takeIf { it != Lesson.ALL }
        val baseDiff = if (survivalMode) Campaign.survivalDifficulty(p) else Campaign.difficulty(stage.depth, p)
        val rules = Contracts.engineRules(plan.contract, stage.depth)
        val diff = rules.applyTo(if (teachMask != null) TutorialPolicy.tune(baseDiff) else baseDiff)
        val config = FloatArray(Cfg.COUNT)
        config[Cfg.STAGE] = stage.depth.toFloat()
        config[Cfg.SKIN] = 0f  // a aparência vai à parte (32 bits não cabem num float)
        val look = p.lookCode
        config[Cfg.ZOMBIES] = diff.zombieCount.toFloat()
        config[Cfg.ZOMBIE_HP] = diff.zombieHealth
        config[Cfg.ZOMBIE_DMG] = diff.zombieDamage
        config[Cfg.ZOMBIE_SPEED] = diff.zombieSpeed
        config[Cfg.PLAYER_HP] = diff.playerHealth
        config[Cfg.MAGAZINE] = diff.magazine.toFloat()
        config[Cfg.POWER] = diff.power
        config[Cfg.CRYSTAL_MASK] = 0f
        config[Cfg.CRYSTAL_TOTAL] = if (survivalMode) 0f else stage.crystalTotal.toFloat()
        config[Cfg.SENSITIVITY] = p.settings.sensitivity
        config[Cfg.AIM_ASSIST] = if (p.settings.aimAssist) 1f else 0f
        config[Cfg.AUTO_FIRE] = if (p.settings.autoFire) 1f else 0f
        config[Cfg.INVERT_Y] = if (p.settings.invertY) 1f else 0f
        // A semente vem do plano (sorteada na preparação ou colada de um código); até 2^24 cabe exato em float.
        config[Cfg.SEED] = plan.seed.toFloat()
        config[Cfg.RELOAD_SPEED] = diff.reloadSpeed
        config[Cfg.BOOST_HEALTH] = 1f
        config[Cfg.BOOST_DAMAGE] = 1f
        config[Cfg.QUALITY] = p.settings.quality.toFloat()
        config[Cfg.CINEMATIC] = if (p.settings.cinematic) 1f else 0f
        config[Cfg.MODE] = if (survivalMode) Cfg.MODE_SURVIVAL.toFloat() else Cfg.MODE_CAMPAIGN.toFloat()
        config[Cfg.ARMOR] = Upgrades.armor(p.upgrade("armor"))
        config[Cfg.REGEN] = Upgrades.regen(p.upgrade("regen"))
        config[Cfg.SPEED] = Upgrades.agility(p.upgrade("agility"))
        config[Cfg.HEADSHOT] = Upgrades.headshot(p.upgrade("headshot"))
        config[Cfg.LUCK] = Upgrades.luck(p.upgrade("luck"))
        config[Cfg.EXPLOSIVES] = p.upgrade("explosives").toFloat()
        config.applyContract(plan, rules)
        perfRun?.applyTo(config)  // diagnóstico (só debug): semente fixa e autopiloto
        perfRun = null            // vale só para esta partida
        p.inventory.normalize()
        val inventory = p.inventory.toIntArray()
        inMission = true
        missionPaused = false
        missionEnded = false
        defeatShown = false
        reviveUsed = false
        bestCombo = 0
        missionKills = 0
        eventNests = 0
        eventCrystals = 0
        bigMap = null
        coach?.stop()
        coach = teachMask?.let { mask ->
            CoachController(
                mask, sink = hud,
                onMask = { m ->
                    if (replayMask != null) replayMask = m
                    else { p.tutorialVersion = TutorialPolicy.VERSION; p.tutorialMask = m; save() }
                },
                onFinished = {
                    if (replayMask == null) {
                        p.tutorialVersion = TutorialPolicy.VERSION
                        p.tutorialMask = Lesson.ALL
                        p.tutorialSeen = true
                        save()
                    }
                },
                onCelebrate = { audio.playUi("reward", 0.5f); hud.haptic(false) },
            )
        }
        uiLayer.removeAllViews()
        currentScreen = null
        homeScreen = null
        val contractTag = if (plan.contract == ContractId.NONE) "" else " • " + Contracts.get(plan.contract).name.uppercase()
        loading = (if (survivalMode) LoadingOverlay(this, "SOBREVIVÊNCIA • ONDAS INFINITAS$contractTag", "Ilha aleatória", Palette.gold, stage.number)
        else LoadingOverlay(this, "ZONA ${stage.zone.index + 1} • ${stage.zone.name.uppercase()}$contractTag", "Fase ${stage.depth} · ${stage.title}", stage.zone.color, if (teachMask != null) 0 else stage.number)
        ).also { showOverlay(it) }
        hud.reset()
        hud.playerName = p.currentHero.name
        hud.applySettings(p.settings.leftHanded, p.settings.vibration, p.settings.showFps, p.settings.hudLayout, p.settings.hudOpacity)
        audio.setMusic(GameAudio.Music.BATTLE)
        // Deixa a tela de carregamento aparecer antes de gerar o mundo.
        surface.postDelayed({
            engine {
                NativeBridge.startMission(config, look)
                NativeBridge.setInventory(inventory)
            }
        }, 120)
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
        when {
            inPvp -> hud.message("QUE VENÇA O MELHOR!", Palette.lime, 2)
            survivalMode -> {
                hud.message("SOBREVIVA ÀS ONDAS!", Palette.gold, 2)
                hud.tip("A 1ª onda chega em 25 s • explore, ache uma construção e prepare-se")
            }
            else -> {
                hud.message(ObjectiveText.SENTENCE_UPPER, Palette.lime, 2)
                if (coach == null) hud.tip("Explore: baús escondidos guardam diamantes e itens")
            }
        }
        // Contratos não existem no PvP; na sobrevivência o briefing não pode apagar a dica das ondas.
        if (!inPvp && !survivalMode) Contracts.briefing(plan)?.let { hud.tip(it) }
        coach?.start()
    }

    /** O objetivo lido do HUD (o HUD já está publicado quando um evento é tratado). */
    private fun currentObjective(): Objective {
        val h = FloatArray(Hud.SIZE)
        NativeBridge.copyHud(h)
        return Objective.from(h)
    }

    private fun onGameEvent(type: Int, a: Int, b: Int, c: Int) {
        if (inPvp) {
            onPvpEvent(type, a, b, c)
            return
        }
        if (!inMission) return
        coach?.onEngineEvent(type, a, b, c)
        when (type) {
            Ev.KILL -> {
                bestCombo = maxOf(bestCombo, c)
                missionKills += 1
                hud.message(if (a == 1) "TIRO NA CABEÇA! ☠ $missionKills" else "+1 ABATE ☠ $missionKills", if (a == 1) Palette.gold else Palette.lime, CombatFeed.PRIORITY_LOW)
                hud.haptic(false)
            }
            Ev.CRYSTAL -> {
                eventCrystals += 1
                val total = currentObjective().nestTotal.takeIf { it > 0 } ?: 3
                hud.message(ObjectiveText.crystalPicked(eventCrystals.coerceAtMost(total), total), Palette.cyan)
                audio.playUi("reward", 0.7f)
            }
            Ev.NEST_DESTROYED -> {
                eventNests += 1
                val total = currentObjective().nestTotal.takeIf { it > 0 } ?: 3
                hud.message(ObjectiveText.nestDestroyed(eventNests.coerceAtMost(total), total), Palette.purple, CombatFeed.PRIORITY_HIGH)
                hud.haptic(true)
            }
            Ev.CHEST -> {
                hud.message(if (b == 1) "BAÚ ABERTO! +◆ $a  +1 GRANADA" else "BAÚ ABERTO! +◆ $a", Palette.gold)
                audio.playUi("reward", 0.8f)
            }
            Ev.ITEM -> {
                val def = Items.get(a)
                if (c == 1) {
                    hud.message("MOCHILA CHEIA! ABRA ESPAÇO 🎒", Palette.muted)
                } else if (def != null) {
                    hud.message(if (Items.isWeapon(a)) "NOVA ARMA: ${def.name.uppercase()}!" else "+$b ${def.name.uppercase()}", def.color)
                    if (Items.isWeapon(a)) {
                        audio.playUi("unlock", 0.9f)
                        hud.tip("Toque na arma na barra de itens para equipar")
                    }
                }
            }
            Ev.ITEM_USED -> {
                val def = Items.get(a)
                if (b == 2) {
                    hud.message("AGUARDE ${c}s PARA SE CURAR DE NOVO", Palette.muted)
                } else if (b == 0) {
                    hud.message(
                        when (a) {
                            Items.ARMOR -> "O COLETE JÁ ESTÁ INTEIRO"
                            Items.ANTIDOTE -> "NADA PARA CURAR AGORA"
                            else -> "SUA VIDA JÁ ESTÁ CHEIA"
                        },
                        Palette.muted,
                    )
                } else if (def != null) {
                    hud.message("${def.name.uppercase()} ✓", def.color)
                    hud.haptic(false)
                }
            }
            Ev.WEAPON -> Items.get(a)?.let { hud.message(it.name.uppercase(), it.color) }
            Ev.WAVE -> {
                survivalWave = a
                when (c) {
                    0 -> hud.tip("Prepare-se: a onda $a está a caminho")
                    1 -> {
                        // O banner da onda (HudView.drawSurvivalTop) já anuncia; sem segundo aviso.
                        hud.haptic(true)
                    }
                    else -> {
                        hud.message("ONDA $a VENCIDA!", Palette.lime)
                        audio.playUi("reward", 0.8f)
                    }
                }
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
                    PoiKind.HOUSE -> "CASA ABANDONADA"
                    PoiKind.VILLAGE -> "VILAREJO"
                    PoiKind.CHURCH -> "IGREJA"
                    PoiKind.GAS -> "POSTO DE GASOLINA"
                    PoiKind.APARTMENT -> "PRÉDIO EM RUÍNAS"
                    PoiKind.BARN -> "CELEIRO"
                    PoiKind.NEST -> "NINHO DE INFECÇÃO!"
                    else -> "PORTAL DE EXTRAÇÃO"
                }
                hud.message(label, if (a == PoiKind.NEST) Palette.purple else Color.WHITE)
                if (a == PoiKind.TOWER) hud.tip("Suba a escada: lá de cima dá para ver longe — e tem um baú!")
                if (a == PoiKind.APARTMENT) hud.tip("Suba as escadas até o terraço: tem um baú lá em cima!")
                if (a == PoiKind.HOUSE || a == PoiKind.VILLAGE || a == PoiKind.CHURCH || a == PoiKind.GAS || a == PoiKind.BARN) hud.tip("Entre pelas portas: as construções escondem baús")
            }
            Ev.SUPPLY -> {
                if (a == 0) hud.message("SUPRIMENTOS CAINDO! SIGA A FUMAÇA VERDE", Palette.cyan)
                else {
                    hud.message("SUPRIMENTOS: VIDA, MUNIÇÃO E ITENS NA MOCHILA!", Palette.gold)
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
                    5 -> {
                        hud.message("VOCÊ ESCALOU PARA FORA DO BURACO", Palette.cyan)
                        hud.tip("Dica: empurre contra paredes de até 3 blocos para escalar")
                    }
                }
                hud.haptic(true)
            }
            Ev.CONTRACT -> when (a) {
                1 -> hud.message("SEM MUNIÇÃO! USE A FACA E AS GRANADAS", Palette.red, CombatFeed.PRIORITY_HIGH)
                2 -> hud.message("MUNIÇÃO ACABANDO!", Palette.gold, CombatFeed.PRIORITY_HIGH)
                3 -> { hud.message("CARGA COLETADA! EXTRAIA PARA RECEBER ◆", Palette.gold, CombatFeed.PRIORITY_HIGH); hud.haptic(true) }
                4 -> hud.message("CARGA REVELADA NO MAPA!", Palette.gold, CombatFeed.PRIORITY_HIGH)
                5 -> hud.tip("NINHO INSTÁVEL: os Inchados explodem perto de você — derrube-os de longe")
            }
            Ev.PORTAL_OPEN -> hud.message(ObjectiveText.PORTAL_OPEN, Palette.purple, CombatFeed.PRIORITY_HIGH)
            Ev.HEAL -> hud.message("+$a VIDA", Palette.red)
            Ev.VICTORY -> surface.postDelayed({ finishMission(true) }, 700)
            Ev.DEFEAT -> surface.postDelayed({ if (survivalMode) showSurvivalDefeat() else showDefeat() }, 900)
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

    /** Alertas, carga e ninhos da missão, lidos do resultado do motor (o contrato só paga na extração). */
    private fun readContractRun(): ContractRun {
        val r = FloatArray(Res.SIZE)
        NativeBridge.copyResult(r)
        return ContractRun(
            alerts = r[Res.ALERTS].toInt(),
            cargoCollected = r[Res.CARGO] > 0.5f,
            nestsDestroyed = r[Res.NESTS].toInt(),
            nestsTotal = Campaign.stage(missionStage).crystalTotal,
        )
    }

    /** A mochila da partida (itens ganhos/gastos) volta para o progresso salvo. */
    private fun syncInventoryFromEngine() {
        val h = FloatArray(Hud.SIZE)
        NativeBridge.copyHud(h)
        progress.inventory.readFromHud(h)
    }

    private fun finishMission(victory: Boolean) {
        if (missionEnded) return
        missionEnded = true
        coach?.stop(); coach = null
        syncInventoryFromEngine()
        hud.visibility = View.GONE
        hud.reset()
        val stage = Campaign.stage(missionStage)
        val result = readResult(victory)
        val summary = ExpeditionRules.applyCampaign(progress, plan, stage, result, readContractRun(), today())
        val unlocked = Rules.refreshCosmeticUnlocks(progress)
        progress.missionsSinceInterstitial += 1
        save()
        audio.playUi(if (victory) "victory" else "defeat", 1f)
        audio.setMusic(GameAudio.Music.MENU)
        showOverlay(ExpeditionResultOverlay(this, plan, stage, result, summary, unlocked))
    }

    private fun showDefeat() {
        if (missionEnded || !inMission) return
        defeatShown = true
        coach?.pause()
        hud.visibility = View.GONE
        NativeBridge.setFire(false)
        audio.playUi("defeat", 1f)
        val r = readResult(false)
        val secs = r.elapsedSeconds.toInt()
        val line = "Ninhos ${r.nests.coerceAtMost(3)}/3 • ☠ ${r.kills} • %d:%02d".format(secs / 60, secs % 60)
        showOverlay(DefeatOverlay(this, Campaign.stage(missionStage), plan, line, canRevive = !reviveUsed, canSkip = Rules.canSkip(progress, missionStage, pendingFails = 1)))
    }

    fun reviveWithAd() {
        watchAd {
            reviveUsed = true
            defeatShown = false
            missionPaused = false
            closeOverlay()
            hud.reset()
            coach?.resume()
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
        coach?.stop(); coach = null
        syncInventoryFromEngine()
        val result = readResult(false)
        ExpeditionRules.applyCampaign(progress, plan, Campaign.stage(missionStage), result, readContractRun(), today())
        progress.missionsSinceInterstitial += 1
        checkUnlocks()
        save()
    }

    /** Sai da missão contabilizando abates e tempo (derrota ou abandono). */
    fun abandonMission(retry: Boolean) {
        if (survivalMode) {
            recordSurvival()
            endMissionUi()
            maybeInterstitial { if (retry) startExpedition(plan.retry(), countPlayTap = false) else showSurvival() }
            return
        }
        recordDefeat()
        endMissionUi()
        maybeInterstitial {
            if (retry) startExpedition(plan.retry(), countPlayTap = false) else showStages()
        }
    }

    fun skipStageWithAd() {
        watchAd {
            recordDefeat()
            if (Rules.skipStage(progress, missionStage)) {
                save()
                audio.playUi("unlock", 1f)
                endMissionUi()
                showExpeditionPrep(ExpeditionKind.CAMPAIGN, progress.frontierStage(), countPlayTap = false, back = { showStages() })
            }
        }
    }

    fun leaveAfterVictory(next: Boolean) {
        replayMask = null
        endMissionUi()
        maybeInterstitial {
            if (next && progress.isStageCompleted(missionStage)) showExpeditionPrep(ExpeditionKind.CAMPAIGN, progress.frontierStage(), countPlayTap = false, back = { showStages() })
            else showStages()
        }
    }

    private fun endMissionUi() {
        coach?.stop(); coach = null
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

    override fun onInventoryRequested() = openInventoryInMatch()

    // ------------------------------------------------------------------ mochila durante a partida

    private var matchInventory: Inventory? = null

    /** Abre a mochila (pausa o jogo); ao fechar as mudanças voltam ao motor. */
    fun openInventoryInMatch() {
        if (!inMission || missionEnded || defeatShown || loading != null) return
        missionPaused = true
        coach?.pause()
        engine { NativeBridge.setPaused(true) }
        hud.reset()
        val h = FloatArray(Hud.SIZE)
        NativeBridge.copyHud(h)
        val working = Inventory().apply {
            copyFrom(progress.inventory)
            readFromHud(h)
        }
        matchInventory = working
        inventoryOpen = true
        lateinit var screen: InventoryScreen
        screen = InventoryScreen(this, working, inMatch = true, initialTab = 0) { use -> closeInventoryInMatch(screen, use) }
        showOverlay(screen)
    }

    fun closeInventoryInMatch(@Suppress("UNUSED_PARAMETER") screen: InventoryScreen, useSlot: Int) {
        val inv = matchInventory ?: return
        matchInventory = null
        inventoryOpen = false
        inv.normalize()
        progress.inventory.copyFrom(inv)
        save()
        val data = inv.toIntArray()
        engine { NativeBridge.setInventory(data) }
        if (useSlot >= 0) NativeBridge.useSlot(useSlot)
        resumeMission()
    }

    // ------------------------------------------------------------------ sobrevivência

    private fun showSurvivalDefeat() {
        if (missionEnded || !inMission) return
        defeatShown = true
        coach?.pause()
        hud.visibility = View.GONE
        NativeBridge.setFire(false)
        audio.playUi("defeat", 1f)
        val r = FloatArray(Res.SIZE)
        NativeBridge.copyResult(r)
        showOverlay(SurvivalDefeatOverlay(this, r[Res.WAVE].toInt().coerceAtLeast(1), r[Res.ELAPSED].toInt(), canRevive = !reviveUsed))
    }

    private fun readSurvivalResult(): SurvivalResult {
        val r = FloatArray(Res.SIZE)
        NativeBridge.copyResult(r)
        val h = FloatArray(Hud.SIZE)
        NativeBridge.copyHud(h)
        val seconds = if (h[Hud.SURV] > 0.5f) h[Hud.SURV_TIME] else r[Res.ELAPSED]
        val score = if (h[Hud.SURV] > 0.5f) h[Hud.SCORE].toInt() else r[Res.SCORE].toInt()
        // O HUD é publicado a cada quadro: garante onda e abates mesmo saindo pelo menu.
        val wave = maxOf(r[Res.WAVE].toInt(), if (h[Hud.SURV] > 0.5f) h[Hud.WAVE].toInt() else 0)
        val kills = maxOf(r[Res.KILLS].toInt(), h[Hud.KILLS].toInt())
        return SurvivalResult(seconds, wave, kills, r[Res.HEADSHOTS].toInt(), score, bestCombo, r[Res.DISTANCE])
    }

    /** Registra a partida de sobrevivência no ranking e paga a recompensa. */
    private fun recordSurvival(): Pair<SurvivalResult, com.fabiosilva.ecosdeeldra.data.SurvivalExpeditionSummary>? {
        if (missionEnded) return null
        missionEnded = true
        syncInventoryFromEngine()
        val result = readSurvivalResult()
        val name = progress.currentHero.name
        val summary = ExpeditionRules.applySurvival(progress, plan, name, today(), result, ContractRun())
        progress.missionsSinceInterstitial += 1
        save()
        return result to summary
    }

    fun finishSurvival() {
        val (result, summary) = recordSurvival() ?: return
        val unlocked = Rules.refreshCosmeticUnlocks(progress)
        save()
        defeatShown = false
        hud.visibility = View.GONE
        hud.reset()
        audio.playUi(if (summary.base.newRecord) "victory" else "defeat", 1f)
        audio.setMusic(GameAudio.Music.MENU)
        val ranking = Rules.survivalRanking(progress)
        showOverlay(SurvivalResultOverlay(this, result, summary.base, ranking, ranking.getOrNull(summary.base.rank - 1), plan, summary.contract))
        if (unlocked.isNotEmpty()) surface.postDelayed({ announceUnlocks(unlocked) }, 1500)
    }

    fun leaveSurvival(again: Boolean) {
        endMissionUi()
        maybeInterstitial { if (again) startExpedition(plan.retry(), countPlayTap = false) else showSurvival() }
    }

    private fun pauseSubtitle(): String =
        (if (survivalMode) "Sobrevivência • Onda ${survivalWave.coerceAtLeast(1)}"
        else Campaign.stage(missionStage).let { "Fase ${it.depth} · ${it.title}\n${ObjectiveText.SENTENCE_UPPER}" }) +
            if (plan.contract != ContractId.NONE) " · ${Contracts.get(plan.contract).name}" else ""

    override fun onMapRequested() {
        if (inPvp && !pvpEnded) {
            hud.reset()
            showOverlay(MapOverlay(this, hud.minimap, worldSize) { resumePvp() })
            return
        }
        if (!inMission || missionEnded) return
        missionPaused = true
        coach?.onMapOpened()
        coach?.pause()
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
        coach?.pause()
        engine { NativeBridge.setPaused(true) }
        hud.reset()
        watchAd(onClosed = { if (inMission && !missionEnded) resumeMission() }) {
            NativeBridge.requestSupply()
        }
    }

    fun openMapFromPause() {
        if (!inMission || missionEnded) return
        coach?.onMapOpened()
        showOverlay(MapOverlay(this, bigMap ?: hud.minimap, worldSize) { showOverlay(PauseOverlay(this, pauseSubtitle(), survivalMode)) })
    }

    fun pauseMission() {
        if (!inMission || missionEnded) return
        missionPaused = true
        coach?.pause()
        engine { NativeBridge.setPaused(true) }
        hud.reset()
        showOverlay(PauseOverlay(this, pauseSubtitle(), survivalMode))
    }

    fun resumeMission() {
        if (!inMission) return
        missionPaused = false
        closeOverlay()
        hud.visibility = View.VISIBLE
        coach?.resume()
        engine { NativeBridge.setPaused(false) }
    }

    /** Botão PULAR TUTORIAL do cartão: marca o tutorial como concluído (ou só encerra a revisão). */
    override fun onCoachSkipRequested() {
        coach?.skip()
    }

    /** Ajustes > REVER TUTORIAL: joga a fase 1 com as dicas desde o início, sem tocar no progresso salvo. */
    fun replayTutorial() {
        if (inMission || inPvp) return
        replayMask = 0
        startExpedition(ExpeditionPlan.standard(1), countPlayTap = false)
    }

    fun showPauseSettings() {
        showOverlay(SettingsScreen(this, fromPause = true))
    }

    /** Editor de HUD (posição, tamanho e transparência dos controles). */
    fun showHudEditor(fromPause: Boolean) = showOverlay(HudEditorScreen(this, fromPause))

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
        val look = progress.lookCode
        engine { NativeBridge.enterLobby(look, 0) }
        showScreen(PvpScreen(this, lanLobby!!))
    }

    fun closePvp() {
        if (inPvp) engine { NativeBridge.enterLobby(progress.lookCode, 0) }
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
        plan = ExpeditionPlan.standard(1)  // sem contrato do último plano vazando para o PvP
        val cfg = FloatArray(PvpCfg.COUNT)
        cfg[PvpCfg.LOCAL_ID] = info.localId.toFloat()
        cfg[PvpCfg.IS_HOST] = if (info.isHost) 1f else 0f
        cfg[PvpCfg.SEED] = info.seed.toFloat()
        cfg[PvpCfg.KILL_LIMIT] = info.rules.killLimit.toFloat()
        cfg[PvpCfg.TIME_LIMIT] = info.rules.timeMinutes * 60f
        cfg[PvpCfg.SENSITIVITY] = progress.settings.sensitivity
        cfg[PvpCfg.INVERT_Y] = if (progress.settings.invertY) 1f else 0f
        // Vagas ocupadas (255 = vazia) e a aparência de cada jogador.
        for (i in 0 until 8) cfg[PvpCfg.SKINS + i] = 255f
        val looks = IntArray(16)  // [0..7] aparência; [8..15] IPv4 de cada jogador (só o anfitrião usa)
        info.memberIps.forEach { (id, ip) -> if (id in 0..7) looks[8 + id] = ip }
        info.players.forEach {
            cfg[PvpCfg.SKINS + it.id] = 0f
            looks[it.id] = it.skin
        }

        uiLayer.removeAllViews()
        currentScreen = null
        homeScreen = null
        pvpScreen = null
        val hostName = info.players.firstOrNull { it.id == 0 }?.name ?: "anfitrião"
        loading = LoadingOverlay(this, "PvP • ${info.players.size} JOGADORES", "Arena de $hostName", Palette.red, info.seed).also { showOverlay(it) }
        hud.reset()
        hud.pvpNames = info.players.associate { it.id to it.name }
        hud.playerName = info.players.firstOrNull { it.id == info.localId }?.name ?: "Você"
        hud.applySettings(progress.settings.leftHanded, progress.settings.vibration, progress.settings.showFps, progress.settings.hudLayout, progress.settings.hudOpacity)
        audio.setMusic(GameAudio.Music.BATTLE)
        val host = info.hostAddress
        surface.postDelayed({ engine { NativeBridge.startPvp(cfg, host, looks) } }, 120)
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
            engine { NativeBridge.enterLobby(progress.lookCode, 0) }
            showPvp()
        }
    }
}
