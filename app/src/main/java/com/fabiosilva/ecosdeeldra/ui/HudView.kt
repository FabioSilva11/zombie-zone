package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.Contracts
import com.fabiosilva.ecosdeeldra.data.Items
import com.fabiosilva.ecosdeeldra.data.ZombieKinds
import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.NativeBridge
import com.fabiosilva.ecosdeeldra.tutorial.CoachFocus
import com.fabiosilva.ecosdeeldra.tutorial.CoachHint
import com.fabiosilva.ecosdeeldra.tutorial.CoachSink
import com.fabiosilva.ecosdeeldra.tutorial.Objective
import com.fabiosilva.ecosdeeldra.tutorial.PillState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * HUD da missão + controles multitoque:
 * - joystick dinâmico (surge onde o polegar toca; empurrar até o fim = correr);
 * - arrastar na metade direita = girar a câmera;
 * - botão ATIRAR (segurar) também gira a câmera ao arrastar;
 * - MIRAR, PULAR, RECARREGAR, AGACHAR, CORRER (corrida automática), FACA, GRANADA,
 *   PAUSA, MINIMAPA e SUPRIMENTOS (anúncio); um toque em CORRER faz o personagem sair correndo
 *   sozinho para a frente (o joystick ainda dirige; puxar para trás ou tocar de novo para).
 * Todos os controles podem ser movidos/redimensionados no editor (modo [editing]).
 * Na ilha mostra ninhos, portal, efeitos (fogo, sangramento, veneno) e zumbis elite.
 */
@SuppressLint("ViewConstructor")
class HudView(context: Context, private val listener: Listener) : View(context), CoachSink {
    interface Listener {
        fun onPauseRequested()
        fun onMapRequested()
        fun onSupplyRequested()
        fun onInventoryRequested() = Unit
        /** Botão PULAR TUTORIAL do cartão do tutorial. */
        fun onCoachSkipRequested() = Unit
    }

    private enum class Role { NONE, JOYSTICK, LOOK, FIRE, FIRE_LEFT, AIM, JUMP, RELOAD, CROUCH, SPRINT, MELEE, GRENADE, MEDKIT, PAUSE, MAP, SUPPLY, HOTBAR, BAG, COACH_SKIP }
    private class Touch(var role: Role, var x: Float, var y: Float, val startX: Float, val startY: Float, var slot: Int = -1, var cancel: Boolean = false)
    private class Geo(var x: Float = 0f, var y: Float = 0f, var r: Float = 0f, var visible: Boolean = true)

    private val hud = FloatArray(Hud.SIZE)
    private val touches = HashMap<Int, Touch>()
    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.title; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private var vignette: RadialGradient? = null
    private var vignettePaint = Paint()
    private val statusPaint = Paint()
    private val statusShaders = HashMap<Int, RadialGradient>()
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    var minimap: Bitmap? = null
    var minimapOriginX = 0f   // canto do bitmap do minimapa em coordenadas do mundo
    var minimapOriginZ = 0f
    var bigMap: Bitmap? = null
    var pvpNames: Map<Int, String> = emptyMap()
    var playerName = ""
    var showFps = false
    var leftHanded = false
    var vibration = true
    var layoutText = ""   // layout salvo nos ajustes (vazio = padrão)
    var opacity = 0.85f   // transparência dos controles

    /** Modo de edição do HUD: arrastar move os controles em vez de jogar. */
    var editing = false
        set(value) {
            field = value
            selected = null
            invalidate()
        }
    var selected: HudControl? = null
        private set
    var onSelectionChanged: ((HudControl?) -> Unit)? = null
    var layout: HudLayout = HudLayout.defaults(900f, 410f, false)
        private set

    private var aiming = false
    private var aimStage = 0
    private var aimWeapon = -1
    /** Corrida automática ligada pelo botão CORRER (o estado vem do motor). */
    private val autoRun get() = hud[Hud.SPRINT_LOCK] > 0.5f
    private var stickBaseX = 0f
    private var stickBaseY = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var stickActive = false
    /** Aviso de combate (uma só mensagem por vez) e dica longa: estado puro em CombatFeed.kt. */
    private val combatFeed = CombatFeed()
    /** Banner de onda desenhado neste quadro: o aviso de combate cede a faixa a ele. */
    private var bannerShown = false
    private val tipState = HudTip()
    // Tutorial (coach): o cartão e os realces vêm do CoachController; reset() NÃO apaga (é chamado a cada pausa).
    private var coach: CoachHint? = null
    private var coachSince = 0L
    private var coachRect: CoachPlacement.Rect? = null
    private var coachDirty = true
    private var coachSkipActive = false
    private val coachSkip = RectF()
    private val coachPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bold; textAlign = Paint.Align.CENTER }
    private var objective = Objective.INACTIVE
    private var objectiveAt = 0L
    private var lastHealth = -1f
    private var dragControl: HudControl? = null
    private var dragDx = 0f
    private var dragDy = 0f

    // Geometria dos controles (px), recalculada a partir do layout.
    private val geo: Map<HudControl, Geo> = HudControl.entries.associateWith { Geo() }
    private fun g(c: HudControl): Geo = geo.getValue(c)
    private var mapX = 0f; private var mapY = 0f; private var mapSize = 0f
    private var supplyX = 0f; private var supplyY = 0f; private var supplyW = 0f; private var supplyH = 0f
    private var stickRadius = 0f
    private var stickHomeX = 0f; private var stickHomeY = 0f
    private var hotbarX = 0f; private var hotbarY = 0f; private var slotSize = 0f
    private var lastWeapon = -1

    private fun d(v: Float) = v * density

    init {
        isClickable = true
        ItemIcons.initialize(context)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
        vignette = RadialGradient(w / 2f, h / 2f, hypot(w / 2f, h / 2f), intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(220, 180, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        vignettePaint.shader = vignette
        statusShaders.clear()
    }

    /** Recalcula o layout (padrão ou o salvo pelo jogador) para o tamanho atual da tela. */
    fun rebuildLayout() {
        if (width <= 0 || height <= 0) return
        layout = HudLayout.decode(layoutText, defaultLayout())
        layoutControls()
    }

    fun defaultLayout(): HudLayout = HudLayout.defaults(width / density, height / density, leftHanded)

    /** Editor: aplica uma cópia de trabalho sem salvar. */
    fun setWorkingLayout(l: HudLayout) {
        layout = l
        layoutControls()
        invalidate()
    }

    private fun layoutControls() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        for (c in HudControl.entries) {
            val pos = layout[c]
            val gc = g(c)
            gc.x = pos.x * w
            gc.y = pos.y * h
            gc.r = d(c.baseDp) * pos.scale
            gc.visible = pos.visible
        }
        g(HudControl.MAP).let { mapX = it.x; mapY = it.y; mapSize = it.r * 2f }
        g(HudControl.SUPPLY).let { supplyX = it.x; supplyY = it.y; supplyH = it.r * 2f; supplyW = it.r * 6.6f }
        g(HudControl.JOYSTICK).let { stickRadius = it.r; stickHomeX = it.x; stickHomeY = it.y }
        g(HudControl.HOTBAR).let { hotbarX = it.x; hotbarY = it.y; slotSize = it.r * 2f }
        coachDirty = true
        if (!stickActive) {
            stickBaseX = stickHomeX; stickBaseY = stickHomeY; stickX = stickBaseX; stickY = stickBaseY
        }
    }

    fun applySettings(leftHanded: Boolean, vibration: Boolean, showFps: Boolean, layoutText: String = this.layoutText, opacity: Float = this.opacity) {
        this.leftHanded = leftHanded
        this.vibration = vibration
        this.showFps = showFps
        this.layoutText = layoutText
        this.opacity = opacity.coerceIn(0.3f, 1f)
        rebuildLayout()
    }

    fun reset() {
        touches.clear()
        aiming = false
        aimStage = 0
        aimWeapon = -1
        stickActive = false
        combatFeed.clear()
        tipState.clear()
        lastHealth = -1f
        NativeBridge.setMove(0f, 0f)
        NativeBridge.setFire(false)
        NativeBridge.setAimStage(0)
        NativeBridge.setThrowAim(false, true)
        lastWeapon = -1
        if (width > 0) layoutControls()
    }

    // ------------------------------------------------------------------ editor do HUD

    fun scaleSelected(delta: Float) {
        val c = selected ?: return
        val pos = layout[c]
        pos.scale = (pos.scale + delta).coerceIn(HudLayout.MIN_SCALE, HudLayout.MAX_SCALE)
        layoutControls()
        invalidate()
    }

    /** Mostra/esconde o controle selecionado; retorna o novo estado. */
    fun toggleSelectedVisible(): Boolean? {
        val c = selected ?: return null
        if (!c.optional) return null
        val pos = layout[c]
        pos.visible = !pos.visible
        layoutControls()
        invalidate()
        return pos.visible
    }

    private fun pickControl(x: Float, y: Float): HudControl? {
        var best: HudControl? = null
        var bestDist = Float.MAX_VALUE
        for (c in HudControl.entries) {
            val gc = g(c)
            val hit = when (c) {
                HudControl.MAP -> abs(x - gc.x) < gc.r && abs(y - gc.y) < gc.r
                HudControl.SUPPLY -> abs(x - gc.x) < gc.r * 3.3f && abs(y - gc.y) < gc.r * 1.3f
                HudControl.HOTBAR -> abs(x - gc.x) < gc.r * 6.2f && abs(y - gc.y) < gc.r * 1.2f
                else -> hypot(x - gc.x, y - gc.y) < gc.r * 1.25f + d(6f)
            }
            if (!hit) continue
            val dist = hypot(x - gc.x, y - gc.y)
            if (dist < bestDist) {
                bestDist = dist
                best = c
            }
        }
        return best
    }

    private fun editTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = pickControl(event.x, event.y)
                selected = c
                onSelectionChanged?.invoke(c)
                dragControl = c
                if (c != null) {
                    dragDx = g(c).x - event.x
                    dragDy = g(c).y - event.y
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> dragControl?.let { c ->
                val pos = layout[c]
                pos.x = ((event.x + dragDx) / width).coerceIn(0.02f, 0.98f)
                pos.y = ((event.y + dragDy) / height).coerceIn(0.03f, 0.97f)
                layoutControls()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragControl = null
        }
        return true
    }

    /** Aviso de combate: só o mais recente fica na tela (substitui o anterior e expira em 1800 ms). */
    fun message(text: String, color: Int = Palette.lime, priority: Int = CombatFeed.PRIORITY_NORMAL) {
        combatFeed.show(text, color, System.currentTimeMillis(), priority)
    }

    /** Dica longa (ex.: habilidade de um zumbi novo) mostrada na parte de baixo; descartada enquanto o tutorial está ativo. */
    fun tip(text: String) {
        if (coach != null) return
        tipState.show(text, System.currentTimeMillis())
    }

    fun haptic(strong: Boolean) {
        if (!vibration) return
        performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private val island get() = hud[Hud.ISLAND] > 0.5f
    private val supplyReady get() = island && hud[Hud.SUPPLY] >= 1f
    private val inventoryOn get() = hud[Hud.INV_ENABLED] > 0.5f
    private val survival get() = hud[Hud.SURV] > 0.5f

    // ------------------------------------------------------------------ toque

    private fun inside(x: Float, y: Float, cx: Float, cy: Float, r: Float) = hypot(x - cx, y - cy) <= r

    /** O controle aparece nesta partida? (granada e faca só na ilha; suprimentos só quando há.) */
    private fun available(c: HudControl): Boolean {
        if (!g(c).visible) return false
        return when (c) {
            HudControl.GRENADE -> hud[Hud.GRENADES] >= 0f
            HudControl.MEDKIT -> true
            HudControl.MELEE -> hud[Hud.MELEE] >= 0f
            HudControl.SUPPLY -> island && hud[Hud.SUPPLY] >= 0f
            HudControl.HOTBAR, HudControl.BAG -> editing || inventoryOn
            else -> true
        }
    }

    private val buttons = listOf(
        HudControl.FIRE to Role.FIRE, HudControl.FIRE_LEFT to Role.FIRE_LEFT, HudControl.AIM to Role.AIM,
        HudControl.JUMP to Role.JUMP, HudControl.RELOAD to Role.RELOAD, HudControl.CROUCH to Role.CROUCH,
        HudControl.SPRINT to Role.SPRINT, HudControl.MELEE to Role.MELEE, HudControl.GRENADE to Role.GRENADE, HudControl.MEDKIT to Role.MEDKIT,
        HudControl.PAUSE to Role.PAUSE,
    )

    /** Espaço da barra rápida sob o toque (-1 se nenhum). */
    private fun hotbarSlotAt(x: Float, y: Float): Int {
        if (!available(HudControl.HOTBAR)) return -1
        val left = hotbarX - slotSize * 3f
        if (y < hotbarY - slotSize * 0.6f || y > hotbarY + slotSize * 0.6f || x < left || x > left + slotSize * 6f) return -1
        return ((x - left) / slotSize).toInt().coerceIn(0, 5)
    }

    /** O toque cai na área do controle [c] (formas em HudTouch, as mesmas usadas nos testes de layout)? */
    private fun touchHit(c: HudControl, x: Float, y: Float): Boolean {
        if (!available(c)) return false
        val gc = g(c)
        return HudTouch.area(c, gc.x, gc.y, gc.r, density).contains(x, y)
    }

    private fun roleAt(x: Float, y: Float): Role {
        val w = width.toFloat()
        val h = height.toFloat()
        if (coachSkipActive && coachSkip.contains(x, y)) return Role.COACH_SKIP
        if (hotbarSlotAt(x, y) >= 0) return Role.HOTBAR
        if (touchHit(HudControl.BAG, x, y)) return Role.BAG
        for ((c, role) in buttons) if (touchHit(c, x, y)) return role
        if (touchHit(HudControl.MAP, x, y)) return Role.MAP
        if (touchHit(HudControl.SUPPLY, x, y)) return Role.SUPPLY
        // Joystick dinâmico no lado em que ele foi posicionado.
        val leftSide = if (stickHomeX < w / 2) x < w * 0.45f else x > w * 0.55f
        if (leftSide && y > h * 0.28f && touches.values.none { it.role == Role.JOYSTICK }) return Role.JOYSTICK
        return Role.LOOK
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return editTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                val role = roleAt(x, y)
                val touch = Touch(role, x, y, x, y)
                touches[id] = touch
                when (role) {
                    Role.JOYSTICK -> {
                        stickActive = true
                        stickBaseX = x; stickBaseY = y; stickX = x; stickY = y
                    }
                    Role.FIRE, Role.FIRE_LEFT -> { NativeBridge.setFire(true); haptic(false) }
                    Role.AIM -> {
                        aimStage = if (hud[Hud.WEAPON].toInt() == Items.SNIPER) (aimStage + 1) % 3 else if (aimStage == 0) 1 else 0
                        aiming = aimStage > 0
                        NativeBridge.setAimStage(aimStage)
                        haptic(false)
                    }
                    Role.JUMP -> { NativeBridge.pressJump(); haptic(false) }
                    Role.RELOAD -> { NativeBridge.pressReload(); haptic(false) }
                    Role.CROUCH -> { NativeBridge.toggleCrouch(); haptic(false) }
                    Role.SPRINT -> { NativeBridge.toggleSprint(); haptic(false) }
                    Role.MELEE -> { NativeBridge.pressMelee(); haptic(false) }
                    Role.MEDKIT -> { useMedkit(); haptic(false) }
                    Role.GRENADE -> {
                        // Segurar mostra a trajetória; arrastar até o X cancela; soltar arremessa.
                        if (hud[Hud.GRENADES] <= 0f) message("SEM GRANADAS NA MOCHILA", Palette.muted)
                        NativeBridge.setThrowAim(true, false)
                        haptic(false)
                    }
                    Role.HOTBAR -> {
                        touch.slot = hotbarSlotAt(x, y)
                        NativeBridge.tapSlot(touch.slot)
                        haptic(false)
                    }
                    Role.BAG -> listener.onInventoryRequested()
                    Role.PAUSE -> listener.onPauseRequested()
                    Role.COACH_SKIP -> { listener.onCoachSkipRequested(); haptic(false) }
                    Role.MAP -> listener.onMapRequested()
                    Role.SUPPLY -> if (supplyReady) listener.onSupplyRequested()
                    else -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val t = touches[event.getPointerId(i)] ?: continue
                    val x = event.getX(i)
                    val y = event.getY(i)
                    when (t.role) {
                        Role.JOYSTICK -> {
                            var dx = x - stickBaseX
                            var dy = y - stickBaseY
                            val len = hypot(dx, dy)
                            // Base acompanha o polegar se ele passar muito do limite.
                            if (len > stickRadius * 1.6f) {
                                val k = (len - stickRadius * 1.6f) / len
                                stickBaseX += dx * k; stickBaseY += dy * k
                                dx = x - stickBaseX; dy = y - stickBaseY
                            }
                            val l2 = hypot(dx, dy)
                            val clamped = min(l2, stickRadius)
                            val nx = if (l2 > 0) dx / l2 * clamped else 0f
                            val ny = if (l2 > 0) dy / l2 * clamped else 0f
                            stickX = stickBaseX + nx; stickY = stickBaseY + ny
                            var mx = nx / stickRadius
                            var my = -ny / stickRadius
                            val mag = hypot(mx, my)
                            if (mag < 0.12f) { mx = 0f; my = 0f }
                            NativeBridge.setMove(mx, my)
                        }
                        Role.LOOK, Role.FIRE, Role.FIRE_LEFT -> NativeBridge.addLook((x - t.x) / density, (y - t.y) / density)
                        Role.GRENADE -> {
                            // Arrastar gira a mira; sobre o X o arremesso é cancelado.
                            NativeBridge.addLook((x - t.x) / density, (y - t.y) / density)
                            val (cx, cy) = cancelSpot()
                            val now = hypot(x - cx, y - cy) < g(HudControl.GRENADE).r * 0.9f + d(10f)
                            if (now != t.cancel) {
                                t.cancel = now
                                haptic(false)
                            }
                        }
                        else -> Unit
                    }
                    t.x = x
                    t.y = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (event.actionMasked == MotionEvent.ACTION_CANCEL) touches.keys.toList() else listOf(event.getPointerId(event.actionIndex))
                for (id in ids) {
                    val t = touches.remove(id) ?: continue
                    when (t.role) {
                        Role.JOYSTICK -> {
                            stickActive = false
                            stickBaseX = stickHomeX; stickBaseY = stickHomeY; stickX = stickBaseX; stickY = stickBaseY
                            NativeBridge.setMove(0f, 0f)
                        }
                        Role.FIRE, Role.FIRE_LEFT -> if (touches.values.none { it.role == Role.FIRE || it.role == Role.FIRE_LEFT }) NativeBridge.setFire(false)
                        Role.GRENADE -> NativeBridge.setThrowAim(false, t.cancel || event.actionMasked == MotionEvent.ACTION_CANCEL)
                        else -> Unit
                    }
                }
            }
        }
        return true
    }

    /** Onde fica o X de cancelar o arremesso (acima do botão, ou abaixo se não couber). */
    private fun cancelSpot(): Pair<Float, Float> {
        val gc = g(HudControl.GRENADE)
        val up = gc.y - gc.r * 2.6f
        return gc.x to if (up > d(40f)) up else gc.y + gc.r * 2.6f
    }

    // ------------------------------------------------------------------ desenho

    override fun onDraw(canvas: Canvas) {
        if (editing) {
            drawEditor(canvas)
            return
        }
        NativeBridge.copyHud(hud)
        val currentWeapon = hud[Hud.WEAPON].toInt()
        if (aimWeapon != currentWeapon) {
            aimWeapon = currentWeapon
            aimStage = 0
            aiming = false
            NativeBridge.setAimStage(0)
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val health = hud[Hud.HEALTH]
        val maxHealth = hud[Hud.MAX_HEALTH].coerceAtLeast(1f)
        if (lastHealth >= 0 && health < lastHealth - 2f) haptic(true)
        lastHealth = health

        drawStatusTint(canvas, w, h)
        drawScope(canvas, w, h)
        drawVignette(canvas, w, h, health / maxHealth)
        drawDamageDirection(canvas, w, h)
        drawWorldLabels(canvas, w, h)
        if (island) {
            if (survival) drawFronts(canvas, w) else drawObjectives(canvas, w, h)
        }
        drawReticle(canvas, w, h)
        if (available(HudControl.MAP)) drawMinimap(canvas)
        drawStatus(canvas, health, maxHealth)
        if (hud[Hud.PVP] > 0.5f) {
            drawPvpNames(canvas, w, h)
            drawPvpTop(canvas, w)
            drawPvpScoreboard(canvas, w)
        } else {
            if (survival) drawSurvivalTop(canvas, w, h)
            drawContractChip(canvas, w)
            if (island) {
                if (!survival) drawNestBar(canvas, w)
                if (available(HudControl.SUPPLY)) drawSupplyButton(canvas)
            }
        }
        drawFeed(canvas, w, h)
        drawTip(canvas, w, h)
        drawCoach(canvas, w, h)
        if (hud[Hud.PVP] > 0.5f) drawPvpRespawn(canvas, w, h)
        drawThrowAim(canvas, w, h)
        drawControls(canvas)
        if (showFps) {
            // canto inferior direito, com margem segura (fica abaixo da linha de 16 dp dos controles)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = d(10f)
            textPaint.color = Palette.muted
            canvas.drawText("${hud[Hud.FPS].toInt()} FPS", w - d(10f), h - d(5f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
        postInvalidateOnAnimation()
    }

    private fun drawVignette(canvas: Canvas, w: Float, h: Float, ratio: Float) {
        val flash = hud[Hud.DAMAGE_FLASH]
        val low = if (ratio < 0.3f) (0.35f + 0.25f * sin(System.currentTimeMillis() / 180.0).toFloat()) else 0f
        val a = (flash * 0.85f + low).coerceIn(0f, 1f)
        if (a <= 0.01f) return
        vignettePaint.alpha = (a * 255).toInt()
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    /** Bordas coloridas: laranja queimando, vermelho sangrando, verde envenenado/lento. */
    private fun drawStatusTint(canvas: Canvas, w: Float, h: Float) {
        if (!island) return
        val pulse = 0.75f + 0.25f * sin(System.currentTimeMillis() / 140.0).toFloat()
        val states = listOf(
            Triple(hud[Hud.BURNING], Color.rgb(255, 120, 20), 0.75f),
            Triple(hud[Hud.BLEEDING], Color.rgb(200, 0, 0), 0.5f),
            Triple(maxOf(hud[Hud.POISON], hud[Hud.SLOWED]), Color.rgb(90, 220, 40), 0.55f),
        )
        for ((value, color, strength) in states) {
            if (value <= 0f) continue
            val shader = statusShaders.getOrPut(color) {
                RadialGradient(w / 2f, h / 2f, hypot(w / 2f, h / 2f), intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, color), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            }
            statusPaint.shader = shader
            statusPaint.alpha = (min(1f, value) * strength * pulse * 255).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, statusPaint)
        }
    }

    private fun drawDamageDirection(canvas: Canvas, w: Float, h: Float) {
        val t = hud[Hud.DAMAGE_DIR_T]
        if (t <= 0f) return
        val angle = hud[Hud.DAMAGE_DIR]
        val r = min(w, h) * 0.32f
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(7f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb((min(1f, t) * 220).toInt(), 255, 60, 50)
        rect.set(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r)
        val deg = Math.toDegrees(angle.toDouble()).toFloat() - 90f
        canvas.drawArc(rect, deg - 18f, 36f, false, p)
        p.style = Paint.Style.FILL
    }

    private fun drawWorldLabels(canvas: Canvas, w: Float, h: Float) {
        // Barras de vida dos zumbis atingidos e dos elite próximos.
        val bars = hud[Hud.BAR_COUNT].toInt()
        for (i in 0 until bars) {
            val o = Hud.BARS + i * Hud.BAR_STRIDE
            val x = hud[o] * w
            val y = hud[o + 1] * h
            val f = hud[o + 2]
            val a = hud[o + 3]
            val code = hud[o + 4].toInt()
            val elite = code >= 100
            val info = ZombieKinds.get(code % 100)
            val bw = d(if (elite) 52f else 38f)
            val bh = d(5f)
            p.color = Color.argb((a * 170).toInt(), 0, 0, 0)
            rect.set(x - bw / 2 - d(1f), y - d(1f), x + bw / 2 + d(1f), y + bh + d(1f))
            canvas.drawRoundRect(rect, d(2f), d(2f), p)
            p.color = when {
                elite -> Color.argb((a * 255).toInt(), 255, 200, 60)
                f > 0.5f -> Color.argb((a * 255).toInt(), 168, 255, 62)
                else -> Color.argb((a * 255).toInt(), 255, 90, 70)
            }
            rect.set(x - bw / 2, y, x - bw / 2 + bw * f, y + bh)
            canvas.drawRoundRect(rect, d(2f), d(2f), p)
            if (elite || (code % 100) != 0) {
                textPaint.textSize = d(10f)
                val label = (if (elite) "★ " else "") + info.name.uppercase()
                textPaint.color = Color.argb((a * 160).toInt(), 0, 0, 0)
                canvas.drawText(label, x + d(1f), y - d(4f) + d(1f), textPaint)
                textPaint.color = if (elite) Color.argb((a * 255).toInt(), 255, 210, 80) else Color.argb((a * 255).toInt(), Color.red(info.color), Color.green(info.color), Color.blue(info.color))
                canvas.drawText(label, x, y - d(4f), textPaint)
            }
        }
        // Números de dano/cura.
        val labels = hud[Hud.LABEL_COUNT].toInt()
        for (i in 0 until labels) {
            val o = Hud.LABELS + i * 5
            val kind = hud[o + 2].toInt()
            val value = hud[o + 3].toInt()
            val a = hud[o + 4]
            textPaint.textSize = d(if (kind == 2) 22f else 16f)
            val color = when (kind) { 2 -> Palette.gold; 3 -> Palette.lime; else -> Color.WHITE }
            val label = when (kind) { 3 -> "+$value"; 2 -> "$value!"; else -> "$value" }
            textPaint.color = Color.argb((a * 120).toInt(), 0, 0, 0)
            canvas.drawText(label, hud[o] * w + d(1.5f), hud[o + 1] * h + d(1.5f), textPaint)
            textPaint.color = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawText(label, hud[o] * w, hud[o + 1] * h, textPaint)
        }
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, color: Int, label: String, scale: Float, skull: Boolean) {
        val s = d(11f) * scale
        path.reset()
        path.moveTo(x, y - s); path.lineTo(x + s, y); path.lineTo(x, y + s); path.lineTo(x - s, y); path.close()
        p.color = color
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = Color.WHITE
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        if (skull) {
            p.color = Color.WHITE
            canvas.drawCircle(x, y - d(1.5f) * scale, d(3.5f) * scale, p)
            canvas.drawRect(x - d(2f) * scale, y, x + d(2f) * scale, y + d(3.5f) * scale, p)
            p.color = color
            canvas.drawCircle(x - d(1.3f) * scale, y - d(1.5f) * scale, d(1f) * scale, p)
            canvas.drawCircle(x + d(1.3f) * scale, y - d(1.5f) * scale, d(1f) * scale, p)
        }
        textPaint.textSize = d(11f)
        textPaint.color = Color.argb(150, 0, 0, 0)
        canvas.drawText(label, x + d(1f), y + s + d(14f), textPaint)
        textPaint.color = Color.WHITE
        canvas.drawText(label, x, y + s + d(13f), textPaint)
    }

    /** Alvo da bússola e do rótulo: portal aberto > cristal caído > ninho vivo mais próximo > carga revelada. */
    private class Target(val kind: Int, val x: Float, val z: Float, val dist: Float, val marked: Boolean)

    private fun crystalCount(): Int = hud[Hud.CRYSTAL_COUNT].toInt().coerceIn(0, Hud.MAX_CRYSTALS)

    /**
     * Posição (px) do marcador 3D, ou null se está fora da faixa útil da tela. Quando o ninho está alinhado com a
     * câmera o marcador cairia em cima da mira: ele é deslocado para a esquerda, deixando o núcleo à vista.
     */
    private fun markerSpot(sx: Float, sy: Float, on: Boolean, w: Float, h: Float, wide: Boolean): Pair<Float, Float>? {
        val xs = if (wide) 0.08f..0.92f else 0.18f..0.82f
        val ys = if (wide) 0.12f..0.74f else 0.16f..0.66f
        if (!on || sx !in xs || sy !in ys) return null
        var x = sx * w
        val y = sy * h
        if (abs(x - w / 2) < d(70f) && abs(y - h / 2) < d(60f)) x = w / 2 - d(96f)
        return x to y
    }

    /**
     * Ninhos, cristais caídos e portal: marcador 3D só na área central (longe dos botões) e uma bússola no topo
     * com a direção de todos os objetivos — nada de setas na borda por cima dos controles.
     */
    private fun drawObjectives(canvas: Canvas, w: Float, h: Float) {
        val n = hud[Hud.OBJ_COUNT].toInt().coerceIn(0, 4)
        val px = hud[Hud.PLAYER_X]
        val pz = hud[Hud.PLAYER_Z]
        val pulse = 1f + 0.12f * sin(System.currentTimeMillis() / 200.0).toFloat()
        var nest: Target? = null
        var portal: Target? = null
        var crystal: Target? = null
        for (i in 0 until n) {
            val o = Hud.OBJ + i * Hud.OBJ_STRIDE
            val state = hud[o + 2].toInt()
            if (state != 1 && state != 4) continue          // destruído e portal fechado não têm marcador
            val dist = hypot(hud[o] - px, hud[o + 1] - pz)
            val spot = markerSpot(hud[o + 3], hud[o + 4], hud[o + 5] > 0.5f, w, h, false)
            if (spot != null) {
                val label = (if (state == 4) "PORTAL " else "NINHO ") + formatDist(dist)
                drawMarker(canvas, spot.first, spot.second, if (state == 4) Palette.purple else NEST_COLOR, label, pulse, state == 1)
            }
            val tg = Target(if (state == 4) KIND_PORTAL else KIND_NEST, hud[o], hud[o + 1], dist, spot != null)
            if (state == 4) portal = tg else if (nest == null || dist < nest.dist) nest = tg
        }
        for (k in 0 until crystalCount()) {
            val o = Hud.CRYSTAL_ITEMS + k * Hud.CRYSTAL_STRIDE
            val dist = hypot(hud[o] - px, hud[o + 1] - pz)
            val spot = markerSpot(hud[o + 2], hud[o + 3], hud[o + 4] > 0.5f, w, h, true)
            if (spot != null) drawMarker(canvas, spot.first, spot.second, Palette.cyan, "CRISTAL " + formatDist(dist), pulse, false)
            if (crystal == null || dist < crystal.dist) crystal = Target(KIND_CRYSTAL, hud[o], hud[o + 1], dist, spot != null)
        }
        // Caixa perdida: a carga só tem marcador depois de revelada (estado 2); antes disso o chip dá a dica de distância.
        var cargo: Target? = null
        if (hud[Hud.CT_ID] > 0.5f && hud[Hud.CT_CARGO_STATE].toInt() == 2) {
            val dist = hypot(hud[Hud.CT_CARGO_X] - px, hud[Hud.CT_CARGO_Z] - pz)
            val spot = markerSpot(hud[Hud.CT_CARGO_SX], hud[Hud.CT_CARGO_SY], hud[Hud.CT_CARGO_ON] > 0.5f, w, h, true)
            if (spot != null) drawMarker(canvas, spot.first, spot.second, CARGO_COLOR, "CARGA " + formatDist(dist), pulse, false)
            cargo = Target(KIND_CARGO, hud[Hud.CT_CARGO_X], hud[Hud.CT_CARGO_Z], dist, spot != null)
        }
        // Portal aberto > (cristal caído ou ninho vivo); a carga revelada e não coletada vence quando está mais perto.
        val base = portal ?: crystal ?: nest
        val pick = if (cargo != null && (base == null || (portal == null && cargo.dist < base.dist))) cargo else base
        drawCompass(canvas, w, n, px, pz, pick)
    }

    private fun wrapAngle(a: Float): Float {
        var x = a
        while (x > PI) x -= TAU
        while (x < -PI) x += TAU
        return x
    }

    /**
     * Bússola estreita sob a barra superior. A distância do alvo aparece UMA vez: no marcador do mundo
     * ("NINHO 276 m") quando ele está visível; senão no rótulo abaixo da bússola.
     * Alvo = portal aberto > cristal caído > ninho vivo mais próximo; a carga revelada (Caixa perdida) vence quando
     * está mais perto que esse alvo (e o portal ainda não abriu). EXTENSÃO: o cartão do tutorial vai na faixa
     * inferior (CoachPlacement); esta região (até COMPASS_TOP_DP + 16 + 28) não leva mais nada.
     */
    private fun drawCompass(canvas: Canvas, w: Float, n: Int, px: Float, pz: Float, target: Target?) {
        val yaw = hud[Hud.CAM_YAW]
        val cw = minOf(d(220f), w * 0.36f)
        val ch = d(16f)
        val top = d(COMPASS_TOP_DP)
        val left = w / 2 - cw / 2
        val half = (PI / 2).toFloat() // a bússola mostra 90° para cada lado
        fun xFor(rel: Float) = w / 2 + rel / half * (cw / 2 - d(8f))
        rect.set(left, top, left + cw, top + ch)
        p.color = Color.argb(170, 8, 14, 26)
        canvas.drawRoundRect(rect, d(8f), d(8f), p)
        canvas.save()
        canvas.clipRect(rect)
        val names = arrayOf("N", "L", "S", "O")
        textPaint.textSize = d(10f)
        for (k in 0 until 24) {
            val rel = wrapAngle(k * TAU / 24f - yaw)
            if (abs(rel) > half) continue
            val x = xFor(rel)
            if (k % 6 == 0) {
                textPaint.color = if (k == 0) Palette.red else Color.WHITE
                canvas.drawText(names[k / 6], x, top + d(14f), textPaint)
            } else {
                p.color = Color.argb(120, 255, 255, 255)
                val th = if (k % 2 == 0) d(6f) else d(3f)
                canvas.drawRect(x - d(0.7f), top + ch - th - d(2f), x + d(0.7f), top + ch - d(2f), p)
            }
        }
        val cy = top + ch / 2
        val coachHint = coach
        fun mark(wx: Float, wz: Float, color: Int, isTarget: Boolean, ringIt: Boolean) {
            val rel = wrapAngle(atan2(wx - px, -(wz - pz)) - yaw)
            p.color = color
            val s = d(if (isTarget) 7f else 5f)
            if (abs(rel) <= half) {
                val x = xFor(rel)
                path.reset()
                path.moveTo(x, cy - s); path.lineTo(x + s, cy); path.lineTo(x, cy + s); path.lineTo(x - s, cy); path.close()
                canvas.drawPath(path, p)
                if (ringIt && coachHint != null) focusRing(canvas, x, cy, s, color, 1f, 0.55f)
            } else {
                // Fora do campo da bússola: triângulo na ponta indicando para que lado virar.
                val right = rel > 0
                val x = if (right) left + cw - d(6f) else left + d(6f)
                path.reset()
                if (right) { path.moveTo(x + s * 0.6f, cy); path.lineTo(x - s * 0.6f, cy - s); path.lineTo(x - s * 0.6f, cy + s) }
                else { path.moveTo(x - s * 0.6f, cy); path.lineTo(x + s * 0.6f, cy - s); path.lineTo(x + s * 0.6f, cy + s) }
                path.close()
                canvas.drawPath(path, p)
            }
        }
        for (i in 0 until n) {
            val o = Hud.OBJ + i * Hud.OBJ_STRIDE
            val state = hud[o + 2].toInt()
            if (state == 2) continue
            val color = when (state) { 4 -> Palette.purple; 3 -> Color.argb(150, 150, 120, 210); else -> NEST_COLOR }
            val isTarget = target != null && target.x == hud[o] && target.z == hud[o + 1]
            val ringIt = coachHint?.focus == CoachFocus.NEST_MARKER && coachHint.focusIndex == i && state == 1
            mark(hud[o], hud[o + 1], color, isTarget, ringIt)
        }
        for (k in 0 until crystalCount()) {
            val o = Hud.CRYSTAL_ITEMS + k * Hud.CRYSTAL_STRIDE
            val isTarget = target != null && target.kind == KIND_CRYSTAL && target.x == hud[o] && target.z == hud[o + 1]
            mark(hud[o], hud[o + 1], Palette.cyan, isTarget, coachHint?.focus == CoachFocus.CRYSTAL && isTarget)
        }
        if (hud[Hud.CT_ID] > 0.5f && hud[Hud.CT_CARGO_STATE].toInt() == 2) {
            val isTarget = target != null && target.kind == KIND_CARGO
            mark(hud[Hud.CT_CARGO_X], hud[Hud.CT_CARGO_Z], CARGO_COLOR, isTarget, false)
        }
        canvas.restore()
        p.color = Color.WHITE
        canvas.drawRect(w / 2 - d(1f), top - d(2f), w / 2 + d(1f), top + ch + d(2f), p)
        if (target != null) {
            val name = when (target.kind) { KIND_CRYSTAL -> "CRISTAL"; KIND_PORTAL -> "PORTAL"; KIND_CARGO -> "CARGA"; else -> "PRÓXIMO NINHO" }
            val label = if (target.marked) name else "$name ${formatDist(target.dist)}"
            textPaint.textSize = d(if (target.marked) 9f else 11f)
            textPaint.color = Color.argb(160, 0, 0, 0)
            canvas.drawText(label, w / 2 + d(1f), top + ch + d(14f), textPaint)
            textPaint.color = when (target.kind) { KIND_CRYSTAL -> Palette.cyan; KIND_PORTAL -> Color.rgb(200, 160, 255); KIND_CARGO -> CARGO_COLOR; else -> Color.rgb(240, 150, 240) }
            canvas.drawText(label, w / 2, top + ch + d(13f), textPaint)
        }
    }

    private fun formatDist(m: Float): String = if (m >= 1000f) "%.1f km".format(m / 1000f) else "${m.toInt()} m"

    /**
     * Chip do contrato (só com contrato ativo na ilha): nome curto e, quando há regra, alertas e reserva de munição.
     * Fica à direita da bússola (y = COMPASS_TOP_DP + 6 dp), numa faixa que nenhum outro elemento do HUD usa no layout padrão.
     */
    private fun drawContractChip(canvas: Canvas, w: Float) {
        if (!island) return
        val id = ContractId.fromCode(hud[Hud.CT_ID].toInt()) ?: return
        if (id == ContractId.NONE) return
        val def = Contracts.get(id)
        val name = Contracts.chipName(id)
        val clips = hud[Hud.CT_RESERVE_CLIPS]
        val limited = clips >= 0f
        val stats = StringBuilder()
        when (id) {
            ContractId.SILENT_RECOVERY -> stats.append("ALERTAS ${hud[Hud.CT_ALERTS].toInt()}")
            ContractId.UNSTABLE_NEST -> stats.append("INCHADOS ${hud[Hud.CT_BOMBS].toInt()}")
            ContractId.LOST_CRATE -> when (hud[Hud.CT_CARGO_STATE].toInt()) {
                1 -> stats.append("CARGA ~${hud[Hud.CT_CARGO_SX].toInt()} m")   // oculta: só a dica de distância
                2 -> stats.append("CARGA REVELADA")
                3 -> stats.append("CARGA COLETADA ✓")
            }
            else -> Unit
        }
        if (limited) {
            if (stats.isNotEmpty()) stats.append("  •  ")
            stats.append("RESERVA ${hud[Hud.CT_RESERVE_ROUNDS].toInt()}")
        }
        val pad = d(6f)
        val iconSize = d(14f)
        textPaint.textSize = d(10f)
        val nameW = textPaint.measureText(name)
        textPaint.textSize = d(9f)
        val statsW = if (stats.isEmpty()) 0f else textPaint.measureText(stats.toString())
        val left = w / 2 + d(124f)
        val maxW = (w - d(66f) - left).coerceAtLeast(d(80f))
        val cw = minOf(maxW, maxOf(nameW + iconSize + d(6f), statsW) + pad * 2)
        val ch = if (stats.isEmpty()) d(22f) else d(34f)
        val top = d(COMPASS_TOP_DP) + d(6f)   // abaixo da faixa da sobrevivência (até 44 dp) e à direita da bússola
        rect.set(left, top, left + cw, top + ch)
        p.color = Color.argb(205, 8, 14, 26)
        canvas.drawRoundRect(rect, d(9f), d(9f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.2f)
        p.color = Color.argb(190, Color.red(def.color), Color.green(def.color), Color.blue(def.color))
        canvas.drawRoundRect(rect, d(9f), d(9f), p)
        p.style = Paint.Style.FILL
        BrandIcons.draw(canvas, context, contractIconName(id), left + pad, top + d(4f), iconSize, def.color)
        val saved = textPaint.textAlign
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = d(10f)
        textPaint.color = def.color
        canvas.drawText(name, left + pad + iconSize + d(4f), top + d(15f), textPaint)
        if (stats.isNotEmpty()) {
            textPaint.textSize = d(9f)
            // Reserva baixa (<= 1 pente) fica dourada; zerada, vermelha.
            textPaint.color = when {
                limited && clips <= 0f -> Palette.red
                limited && clips <= 1f -> Palette.gold
                else -> Palette.text
            }
            canvas.drawText(stats.toString(), left + pad, top + d(28f), textPaint)
        }
        textPaint.textAlign = saved
    }

    private fun drawNestBar(canvas: Canvas, w: Float) {
        val hp = hud[Hud.NEST_HP]
        if (hp < 0f) return
        val bw = minOf(d(240f), w * 0.4f)
        val top = d(76f)
        rect.set(w / 2 - bw / 2 - d(6f), top, w / 2 + bw / 2 + d(6f), top + d(30f))
        p.color = Color.argb(210, 40, 8, 48)
        canvas.drawRoundRect(rect, d(10f), d(10f), p)
        textPaint.textSize = d(10f)
        textPaint.color = Color.rgb(230, 170, 255)
        canvas.drawText("NÚCLEO DO NINHO", w / 2, top + d(12f), textPaint)
        rect.set(w / 2 - bw / 2, top + d(17f), w / 2 + bw / 2, top + d(25f))
        p.color = Color.argb(160, 0, 0, 0)
        canvas.drawRoundRect(rect, d(4f), d(4f), p)
        rect.right = rect.left + bw * hp.coerceIn(0f, 1f)
        p.color = Color.rgb(200, 70, 230)
        canvas.drawRoundRect(rect, d(4f), d(4f), p)
    }

    private fun drawSupplyButton(canvas: Canvas) {
        val state = hud[Hud.SUPPLY]
        if (state < 0f) return
        val ready = state >= 1f
        rect.set(supplyX - supplyW / 2, supplyY - supplyH / 2, supplyX + supplyW / 2, supplyY + supplyH / 2)
        p.color = if (ready) Palette.cyan else Color.argb(170, 12, 20, 36)
        canvas.drawRoundRect(rect, d(10f), d(10f), p)
        if (!ready) {
            p.color = Color.argb(120, 60, 230, 255)
            rect.right = rect.left + supplyW * state.coerceIn(0f, 1f)
            canvas.drawRoundRect(rect, d(10f), d(10f), p)
        }
        textPaint.textSize = d(11f)
        textPaint.color = if (ready) Palette.dark else Color.argb(200, 255, 255, 255)
        val pulse = if (ready) 1f + 0.05f * sin(System.currentTimeMillis() / 160.0).toFloat() else 1f
        textPaint.textSize = d(11f) * pulse
        canvas.drawText(if (ready) "▶ SUPRIMENTOS" else "SUPRIMENTOS...", supplyX, supplyY + d(4f), textPaint)
    }

    private fun drawReticle(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h / 2
        val fov = hud[Hud.FOV].coerceAtLeast(0.3f)
        val focal = (h / 2) / tan(fov / 2)
        val gap = (hud[Hud.SPREAD] * focal).coerceIn(d(5f), d(40f))
        val locked = hud[Hud.TARGET_LOCKED] > 0.5f
        val boosted = hud[Hud.BOOST] > 0f
        val color = if (locked) Palette.red else if (boosted) Color.rgb(255, 160, 60) else Color.WHITE
        val len = d(8f)
        p.strokeWidth = d(2.4f)
        p.strokeCap = Paint.Cap.SQUARE
        p.color = Color.argb(140, 0, 0, 0)
        for (pass in 0..1) {
            if (pass == 1) { p.color = color; p.strokeWidth = d(1.6f) }
            canvas.drawLine(cx - gap - len, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + gap + len, cy, p)
            canvas.drawLine(cx, cy - gap - len, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + gap + len, p)
        }
        p.color = color
        canvas.drawCircle(cx, cy, d(1.6f), p)
        if (locked) {
            val f = hud[Hud.TARGET_HP]
            val bw = d(46f)
            p.color = Color.argb(160, 0, 0, 0)
            rect.set(cx - bw / 2, cy + gap + len + d(6f), cx + bw / 2, cy + gap + len + d(10f))
            canvas.drawRect(rect, p)
            p.color = Palette.red
            rect.right = rect.left + bw * f
            canvas.drawRect(rect, p)
        }
        val hit = hud[Hud.HITMARK]
        if (hit > 0f) {
            val hs = hud[Hud.HEADSHOT_MARK] > 0.5f
            p.color = if (hs) Palette.gold else Color.WHITE
            p.alpha = (hit * 255).toInt().coerceIn(0, 255)
            p.strokeWidth = d(if (hs) 3f else 2.2f)
            val a = d(7f)
            val b = d(15f)
            canvas.drawLine(cx - b, cy - b, cx - a, cy - a, p)
            canvas.drawLine(cx + b, cy - b, cx + a, cy - a, p)
            canvas.drawLine(cx - b, cy + b, cx - a, cy + a, p)
            canvas.drawLine(cx + b, cy + b, cx + a, cy + a, p)
            p.alpha = 255
        }
    }

    private fun drawMinimap(canvas: Canvas) {
        val left = mapX - mapSize / 2
        val top = mapY - mapSize / 2
        rect.set(left, top, left + mapSize, top + mapSize)
        p.color = Color.argb(200, 8, 14, 26)
        canvas.drawRoundRect(rect, d(14f), d(14f), p)
        val bmp = minimap
        val px = hud[Hud.PLAYER_X]
        val pz = hud[Hud.PLAYER_Z]
        val yawDeg = Math.toDegrees(hud[Hud.CAM_YAW].toDouble()).toFloat()
        val scale = mapSize / 80f // 80 blocos visíveis
        canvas.save()
        path.reset()
        path.addRoundRect(rect, d(14f), d(14f), Path.Direction.CW)
        canvas.clipPath(path)
        canvas.translate(mapX, mapY)
        canvas.rotate(-yawDeg)
        canvas.scale(scale, scale)
        canvas.translate(-px, -pz)
        if (bmp != null) canvas.drawBitmap(bmp, minimapOriginX, minimapOriginZ, null)
        val alive = hud[Hud.ALIVE].toInt()
        val n = hud[Hud.ENEMY_COUNT].toInt()
        for (i in 0 until n) {
            val o = Hud.ENEMIES + i * 3
            val ex = hud[o]
            val ez = hud[o + 1]
            val alerted = hud[o + 2] > 0.5f
            if (alive > 3 && !alerted && hypot(ex - px, ez - pz) > 30f) continue
            p.color = if (alerted) Palette.red else Color.rgb(255, 160, 60)
            canvas.drawCircle(ex, ez, 1.8f, p)
        }
        if (island) {
            val objs = hud[Hud.OBJ_COUNT].toInt()
            for (i in 0 until objs) {
                val o = Hud.OBJ + i * Hud.OBJ_STRIDE
                val state = hud[o + 2].toInt()
                p.color = when (state) { 1 -> Color.rgb(220, 70, 220); 2 -> Color.argb(150, 120, 120, 120); 4 -> Palette.purple; else -> Color.argb(170, 140, 110, 200) }
                canvas.drawCircle(hud[o], hud[o + 1], 3.2f, p)
            }
            for (k in 0 until crystalCount()) {
                val o = Hud.CRYSTAL_ITEMS + k * Hud.CRYSTAL_STRIDE
                p.color = Color.WHITE
                canvas.drawCircle(hud[o], hud[o + 1], 3.6f, p)
                p.color = Palette.cyan
                canvas.drawCircle(hud[o], hud[o + 1], 2.6f, p)
            }
            if (hud[Hud.SUPPLY_X] >= 0f) {
                p.color = Color.rgb(120, 255, 90)
                canvas.drawCircle(hud[Hud.SUPPLY_X], hud[Hud.SUPPLY_Z], 2.6f, p)
            }
        } else if (hud[Hud.PORTAL_OPEN] > 0.5f) {
            p.color = Palette.purple
            canvas.drawCircle(hud[Hud.PORTAL_X], hud[Hud.PORTAL_Z], 3f, p)
        }
        if (hud[Hud.PVP] > 0.5f) {
            val local = hud[Hud.PVP_LOCAL].toInt()
            for (i in 0 until 8) {
                val b = Hud.PVP_PLAYERS + i * Hud.PVP_STRIDE
                if (i == local || hud[b] < 0.5f || hud[b + 1] < 0.5f || hud[b + 2] < 0.5f) continue
                val near = hypot(hud[b + 8] - px, hud[b + 9] - pz) < 10f
                if (hud[b + 10] < 0.5f && !near) continue
                p.color = Palette.red
                canvas.drawCircle(hud[b + 8], hud[b + 9], 2.4f, p)
            }
        }
        canvas.restore()
        // jogador (seta para cima = direção da câmera)
        path.reset()
        val s = d(7f)
        path.moveTo(mapX, mapY - s); path.lineTo(mapX + s * 0.7f, mapY + s * 0.7f); path.lineTo(mapX, mapY + s * 0.3f); path.lineTo(mapX - s * 0.7f, mapY + s * 0.7f); path.close()
        p.color = Color.WHITE
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = Palette.line
        canvas.drawRoundRect(rect, d(14f), d(14f), p)
        p.style = Paint.Style.FILL
        textPaint.textSize = d(9f)
        textPaint.color = Palette.muted
        canvas.drawText("MAPA", mapX, top + mapSize - d(6f), textPaint)
    }

    /** Vida, armadura e efeitos: painel compacto no canto superior esquerdo (acima do minimapa, nos dois modos de mão). */
    private fun drawStatus(canvas: Canvas, health: Float, maxHealth: Float) {
        val left = d(6f)
        val top = d(6f)
        val pw = d(150f)
        rect.set(left, top, left + pw, top + d(30f))
        p.color = Color.argb(200, 8, 14, 26)
        canvas.drawRoundRect(rect, d(10f), d(10f), p)
        drawHeart(canvas, left + d(13f), top + d(14f), d(1.7f))
        val x0 = left + d(26f)
        val bw = pw - d(26f) - d(8f)
        // números à direita ("40/120"; diamantes só aqui quando a barra superior não os mostra)
        val hpText = "${health.toInt()}/${maxHealth.toInt()}"
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = d(9f)
        textPaint.color = Palette.muted
        canvas.drawText(hpText, left + pw - d(7f), top + d(12f), textPaint)
        val hpW = textPaint.measureText(hpText)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = d(11f)
        textPaint.color = Color.WHITE
        canvas.drawText(fit(playerName, bw - hpW - d(6f)), x0, top + d(12f), textPaint)
        val ratio = (health / maxHealth).coerceIn(0f, 1f)
        rect.set(x0, top + d(16f), x0 + bw, top + d(22f))
        p.color = Color.argb(160, 0, 0, 0)
        canvas.drawRoundRect(rect, d(3f), d(3f), p)
        rect.right = rect.left + bw * ratio
        p.color = if (ratio > 0.5f) Palette.lime else if (ratio > 0.25f) Palette.gold else Palette.red
        canvas.drawRoundRect(rect, d(3f), d(3f), p)
        val armorMax = hud[Hud.ARMOR_MAX]
        if (armorMax > 0f) {
            rect.set(x0, top + d(24f), x0 + bw, top + d(27f))
            p.color = Color.argb(160, 0, 0, 0)
            canvas.drawRoundRect(rect, d(1.5f), d(1.5f), p)
            rect.right = rect.left + bw * (hud[Hud.ARMOR] / armorMax).coerceIn(0f, 1f)
            p.color = Color.rgb(140, 170, 230)
            canvas.drawRoundRect(rect, d(1.5f), d(1.5f), p)
        }
        // Efeitos ativos (ícones com tempo): coluna à direita, abaixo da mochila; longe da mira e da barra superior.
        if (island) {
            var y = d(132f)
            fun chip(label: String, seconds: Float, color: Int) {
                if (seconds <= 0f) return
                val txt = "$label ${seconds.toInt()}s"
                textPaint.textSize = d(10f)
                val tw = textPaint.measureText(txt) + d(14f)
                val x = width - d(8f) - tw
                rect.set(x, y - d(13f), x + tw, y + d(5f))
                p.color = Color.argb(200, Color.red(color) / 4, Color.green(color) / 4, Color.blue(color) / 4)
                canvas.drawRoundRect(rect, d(8f), d(8f), p)
                textPaint.color = color
                canvas.drawText(txt, x + d(7f), y, textPaint)
                y += d(22f)
            }
            chip("🔥 QUEIMANDO", hud[Hud.BURNING], Color.rgb(255, 150, 60))
            chip("🩸 SANGRANDO", hud[Hud.BLEEDING], Color.rgb(255, 90, 90))
            chip("☠ VENENO", hud[Hud.POISON], Color.rgb(140, 255, 90))
            chip("💥 MUNIÇÃO INCENDIÁRIA", hud[Hud.BOOST], Color.rgb(255, 180, 70))
            chip("⚡ ADRENALINA", hud[Hud.ADRENALINE], Color.rgb(90, 230, 255))
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** Corta o texto com reticências para caber em [maxW] (usa o tamanho de texto atual do textPaint). */
    private fun fit(text: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (textPaint.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && textPaint.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, px: Float) {
        val rows = arrayOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
        p.color = Palette.red
        for ((r, row) in rows.withIndex()) for ((c, ch) in row.withIndex()) {
            if (ch == 'X') canvas.drawRect(cx + (c - 3.5f) * px, cy + (r - 3f) * px, cx + (c - 2.5f) * px, cy + (r - 2f) * px, p)
        }
    }

    private class BarCell(
        val label: String,
        val value: String,
        val color: Int,
        val icon: String?,
        val state: PillState?,
        val attention: Boolean = false,
    )

    /** Objetivo lido do HUD no máximo a cada 100 ms (as pílulas não precisam de mais que isso). */
    private fun currentObjective(now: Long): Objective {
        if (now - objectiveAt >= 100L) {
            objective = Objective.from(hud)
            objectiveAt = now
        }
        return objective
    }

    /**
     * Barra superior: as três pílulas do objetivo na ordem NINHOS → CRISTAIS → PORTAL (a atual destacada, as
     * concluídas riscadas, a que pede ação pulsando), mais TEMPO e ABATES. O texto longo do objetivo
     * (ObjectiveText.SENTENCE_UPPER) só aparece em pausa, mapa e carregamento. "◆" é só de diamantes (moeda).
     */
    private fun drawObjective(canvas: Canvas, w: Float) {
        val now = System.currentTimeMillis()
        val obj = if (island) currentObjective(now) else Objective.INACTIVE
        val portal = obj.portalOpen
        val pillColors = intArrayOf(NEST_COLOR, Palette.cyan, Palette.purple)
        val pillIcons = arrayOf(BrandIcons.NINHO, BrandIcons.CRISTAL, BrandIcons.PORTAL_EXTRACAO)
        val core = ArrayList<BarCell>()
        if (obj.active) {
            for ((i, pill) in obj.pills.withIndex()) {
                core.add(BarCell(pill.label, pill.value.ifEmpty { "FECHADO" }, pillColors[i], pillIcons[i], pill.state, pill.attention))
            }
        }
        val elapsed = hud[Hud.ELAPSED].toInt()
        core.add(BarCell("TEMPO", "%02d:%02d".format(elapsed / 60, elapsed % 60), Palette.muted, null, null))
        val kills = BarCell("ABATES", "${hud[Hud.KILLS].toInt()}", Palette.muted, null, null)

        val maxW = (w - 2f * d(166f)).coerceAtLeast(d(200f))
        val chevron = d(9f)
        fun cellWidth(c: BarCell, icons: Boolean): Float {
            textPaint.textSize = d(8f)
            val lw = textPaint.measureText(c.label)
            textPaint.textSize = d(12f)
            val vw = textPaint.measureText(c.value)
            return maxOf(lw, vw) + d(16f) + if (icons && c.icon != null) d(17f) else 0f
        }
        fun total(cells: List<BarCell>, icons: Boolean): Float {
            var t = 0f
            for ((i, c) in cells.withIndex()) {
                t += cellWidth(c, icons)
                if (i < cells.size - 1 && c.state != null && cells[i + 1].state != null) t += chevron
            }
            return t
        }
        var cells: List<BarCell> = core + kills
        var icons = true
        if (total(cells, icons) > maxW) cells = core
        if (total(cells, icons) > maxW) icons = false
        val widths = FloatArray(cells.size) { cellWidth(cells[it], icons) }
        val totalW = total(cells, icons)

        val top = d(TOP_BAR_TOP_DP)
        val bh = d(30f)
        val left = w / 2 - totalW / 2
        rect.set(left, top, left + totalW, top + bh)
        p.color = if (portal) Color.argb(220, 60, 24, 110) else Color.argb(205, 8, 14, 26)
        canvas.drawRoundRect(rect, d(12f), d(12f), p)
        var x = left
        for ((i, c) in cells.withIndex()) {
            val cw = widths[i]
            val cx = x + cw / 2
            val st = c.state
            if (st != null) {
                val inset = d(2f)
                rect.set(x + inset, top + inset, x + cw - inset, top + bh - inset)
                if (st == PillState.CURRENT) {
                    p.color = Color.argb(46, Color.red(c.color), Color.green(c.color), Color.blue(c.color))
                    canvas.drawRoundRect(rect, d(10f), d(10f), p)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = d(1.3f)
                    p.color = Color.argb(200, Color.red(c.color), Color.green(c.color), Color.blue(c.color))
                    canvas.drawRoundRect(rect, d(10f), d(10f), p)
                    p.style = Paint.Style.FILL
                }
                if (c.attention) {
                    // Cristal caído e ainda no chão: contorno ciano pulsando.
                    val a = (0.45f + 0.45f * sin(now / 160.0).toFloat()).coerceIn(0f, 1f)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = d(1.8f)
                    p.color = Color.argb((a * 255).toInt(), 60, 230, 255)
                    canvas.drawRoundRect(rect, d(10f), d(10f), p)
                    p.style = Paint.Style.FILL
                }
            }
            val hasIcon = icons && c.icon != null
            val textCx = if (hasIcon) cx + d(8.5f) else cx
            val dim = st == PillState.DONE
            val accent = when (st) {
                PillState.CURRENT -> c.color
                PillState.DONE -> Color.argb(170, Color.red(c.color), Color.green(c.color), Color.blue(c.color))
                PillState.TODO -> Palette.muted
                null -> c.color
            }
            if (hasIcon) {
                val isz = d(14f)
                BrandIcons.draw(canvas, context, c.icon!!, x + d(8f), top + (bh - isz) / 2, isz, accent, if (st == PillState.TODO) 150 else 255)
            }
            textPaint.textSize = d(8f)
            textPaint.color = Palette.muted
            canvas.drawText(c.label, textCx, top + d(12f), textPaint)
            textPaint.textSize = d(12f)
            textPaint.color = if (st == null) (if (c === kills) Palette.muted else Palette.text) else accent
            val ty = top + d(25f)
            canvas.drawText(c.value, textCx, ty, textPaint)
            if (dim) {
                // Concluída: risco no valor.
                val vw = textPaint.measureText(c.value)
                p.color = Color.argb(200, 220, 230, 245)
                canvas.drawRect(textCx - vw / 2, ty - d(4.5f), textCx + vw / 2, ty - d(3.3f), p)
            }
            x += cw
            if (i < cells.size - 1 && st != null && cells[i + 1].state != null) {
                textPaint.textSize = d(12f)
                textPaint.color = Palette.muted
                canvas.drawText("›", x + chevron / 2, top + d(21f), textPaint)
                x += chevron
            }
        }
        if (island && hud[Hud.NEST_HP] < 0f) {
            textPaint.textSize = d(10f)
            textPaint.color = Color.argb(200, 255, 220, 120)
            val chests = hud[Hud.CHESTS].toInt()
            val gems = hud[Hud.CHEST_DIAMONDS].toInt()
            canvas.drawText("BAÚS $chests (◆$gems)   •   ${formatDist(hud[Hud.DISTANCE])} percorridos", w / 2, d(88f), textPaint)
        }
    }

    private fun pvpName(id: Int): String = pvpNames[id] ?: "Jogador ${id + 1}"

    private fun drawPvpTop(canvas: Canvas, w: Float) {
        val local = hud[Hud.PVP_LOCAL].toInt()
        val limit = hud[Hud.PVP_LIMIT].toInt()
        var leader = local
        for (i in 0 until 8) {
            val b = Hud.PVP_PLAYERS + i * Hud.PVP_STRIDE
            if (hud[b] > 0.5f && hud[b + 3] > hud[Hud.PVP_PLAYERS + leader * Hud.PVP_STRIDE + 3]) leader = i
        }
        val myKills = hud[Hud.PVP_PLAYERS + local * Hud.PVP_STRIDE + 3].toInt()
        val leaderKills = hud[Hud.PVP_PLAYERS + leader * Hud.PVP_STRIDE + 3].toInt()
        val leaderText = if (leader == local) "VOCÊ LIDERA" else "LÍDER: ${pvpName(leader)} $leaderKills"
        val t = hud[Hud.PVP_TIME].toInt().coerceAtLeast(0)
        val text = "☠ $myKills/$limit   •   $leaderText   •   %d:%02d".format(t / 60, t % 60)
        textPaint.textSize = d(14f)
        val tw = textPaint.measureText(text) + d(36f)
        rect.set(w / 2 - tw / 2, d(12f), w / 2 + tw / 2, d(44f))
        p.color = Color.argb(205, 8, 14, 26)
        canvas.drawRoundRect(rect, d(16f), d(16f), p)
        val unstable = hud[Hud.PVP_LINK] > 2f
        textPaint.color = if (unstable) Palette.gold else Palette.lime
        canvas.drawText(text, w / 2, d(33f), textPaint)
        if (unstable) {
            textPaint.textSize = d(11f)
            canvas.drawText("Conexão instável...", w / 2, d(58f), textPaint)
        }
    }

    private fun drawPvpScoreboard(canvas: Canvas, w: Float) {
        val local = hud[Hud.PVP_LOCAL].toInt()
        val rows = (0 until 8).filter { hud[Hud.PVP_PLAYERS + it * Hud.PVP_STRIDE] > 0.5f }
            .sortedByDescending { hud[Hud.PVP_PLAYERS + it * Hud.PVP_STRIDE + 3] }
        val right = w - d(70f)
        val left = right - d(150f)
        var y = d(64f)
        rect.set(left, y - d(6f), right, y + rows.size * d(17f) + d(4f))
        p.color = Color.argb(150, 8, 14, 26)
        canvas.drawRoundRect(rect, d(8f), d(8f), p)
        textPaint.textSize = d(11f)
        for (id in rows) {
            val b = Hud.PVP_PLAYERS + id * Hud.PVP_STRIDE
            val active = hud[b + 1] > 0.5f
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = if (id == local) Palette.lime else if (active) Color.WHITE else Palette.muted
            canvas.drawText(pvpName(id) + if (active) "" else " (saiu)", left + d(8f), y + d(9f), textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${hud[b + 3].toInt()} / ${hud[b + 4].toInt()}", right - d(8f), y + d(9f), textPaint)
            y += d(17f)
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawPvpNames(canvas: Canvas, w: Float, h: Float) {
        val local = hud[Hud.PVP_LOCAL].toInt()
        textPaint.textSize = d(12f)
        for (i in 0 until 8) {
            val b = Hud.PVP_PLAYERS + i * Hud.PVP_STRIDE
            if (i == local || hud[b + 7] < 0.5f) continue
            val x = hud[b + 5] * w
            val y = hud[b + 6] * h
            val name = pvpName(i)
            val tw = textPaint.measureText(name) + d(12f)
            rect.set(x - tw / 2, y - d(15f), x + tw / 2, y + d(3f))
            p.color = Color.argb(150, 120, 20, 20)
            canvas.drawRoundRect(rect, d(6f), d(6f), p)
            textPaint.color = Color.WHITE
            canvas.drawText(name, x, y - d(2f), textPaint)
        }
    }

    private fun drawPvpRespawn(canvas: Canvas, w: Float, h: Float) {
        val left = hud[Hud.PVP_RESPAWN]
        if (left <= 0f || hud[Hud.PVP_ENDED] > 0.5f) return
        p.color = Color.argb(110, 40, 0, 0)
        canvas.drawRect(0f, h * 0.38f, w, h * 0.58f, p)
        textPaint.textSize = d(24f)
        textPaint.color = Palette.red
        canvas.drawText("ELIMINADO!", w / 2, h * 0.46f, textPaint)
        textPaint.textSize = d(15f)
        textPaint.color = Color.WHITE
        canvas.drawText("Renascendo em ${kotlin.math.ceil(left).toInt()}...", w / 2, h * 0.53f, textPaint)
    }

    /**
     * Só UM aviso de combate por vez (o mais recente), acima do centro; o COMBO fica abaixo dele, menor.
     * Nada é desenhado na faixa da mira (h/2).
     */
    private fun drawFeed(canvas: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        val y = d(CombatFeed.noticeBaselineDp(h / d(1f)))
        val notice = combatFeed.current(now)
        val suppress = bannerShown
        bannerShown = false
        if (notice != null && !suppress) {
            val age = (now - notice.shownAtMs) / CombatFeed.DURATION_MS.toFloat()
            val a = if (age < 0.8f) 1f else ((1f - age) / 0.2f).coerceIn(0f, 1f)
            val scale = if (age < 0.08f) 1f + (0.08f - age) * 4f else 1f
            // Frases longas (o objetivo inteiro) encolhem para caber na largura da tela.
            var base = d(20f)
            textPaint.textSize = base
            val noticeW = textPaint.measureText(notice.text)
            if (noticeW > w * 0.92f) base = base * (w * 0.92f) / noticeW
            textPaint.textSize = base * scale
            textPaint.color = Color.argb((a * 140).toInt(), 0, 0, 0)
            canvas.drawText(notice.text, w / 2 + d(2f), y + d(2f), textPaint)
            textPaint.color = Color.argb((a * 255).toInt(), Color.red(notice.color), Color.green(notice.color), Color.blue(notice.color))
            canvas.drawText(notice.text, w / 2, y, textPaint)
        }
        val combo = hud[Hud.COMBO].toInt()
        if (combo >= 2) {
            // À direita da mira (fora do braço de 48 dp), nunca sobre o aviso nem a barra do ninho.
            val cx = w / 2 + d(100f)
            val cy = h / 2 + d(4f)
            textPaint.textSize = d(13f)
            textPaint.color = Color.argb(150, 0, 0, 0)
            canvas.drawText("COMBO x$combo", cx + d(1f), cy + d(1f), textPaint)
            textPaint.color = Palette.gold
            canvas.drawText("COMBO x$combo", cx, cy, textPaint)
        }
    }

    // ------------------------------------------------------------------ tutorial (coach)

    /** Chamado pelo CoachController (thread de UI, até 10 Hz). `null` remove o cartão. Idempotente. */
    override fun showCoach(hint: CoachHint?) {
        if (hint == coach) return
        if (hint?.lesson != coach?.lesson || hint?.celebrate != coach?.celebrate) coachSince = SystemClock.uptimeMillis()
        coach = hint
        coachDirty = true
        if (hint == null) coachSkipActive = false
        postInvalidateOnAnimation()
    }

    private fun coachAccent(f: CoachFocus): Int = when (f) {
        CoachFocus.NEST_MARKER, CoachFocus.NEST_CORE -> NEST_COLOR
        CoachFocus.CRYSTAL -> Palette.cyan
        CoachFocus.PORTAL -> Palette.purple
        else -> Palette.lime
    }

    /** Retângulo do cartão (cacheado: muda só com o layout ou quando o cartão aparece). */
    private fun coachCard(w: Float, h: Float): CoachPlacement.Rect? {
        if (coachDirty) {
            coachDirty = false
            val blockers = HudControl.entries.filter { it != HudControl.HOTBAR && available(it) }.map { c ->
                val gc = g(c)
                HudTouch.area(c, gc.x, gc.y, gc.r, density)
            }
            val bottom = if (available(HudControl.HOTBAR)) hotbarY - slotSize / 2f - d(6f) else h - d(24f)
            coachRect = CoachPlacement.place(w, h, bottom, blockers, density)
            coachRect?.let { r ->
                val t = CoachPlacement.skipTouch(r, density)
                coachSkip.set(t.left, t.top, t.right, t.bottom)
            } ?: coachSkip.setEmpty()
        }
        return coachRect
    }

    private fun wrapText(paint: Paint, text: String, maxW: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val cand = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || paint.measureText(cand) <= maxW) line = cand else { lines.add(line); line = word }
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines
    }

    private fun drawCoach(canvas: Canvas, w: Float, h: Float) {
        val hint = coach ?: return
        val now = SystemClock.uptimeMillis()
        val fade = ((now - coachSince) / 220f).coerceIn(0f, 1f)
        val accent = coachAccent(if (hint.celebrate) CoachFocus.NONE else hint.focus)
        if (!hint.celebrate) drawCoachFocus(canvas, w, h, hint, fade)

        val card = coachCard(w, h)
        if (card == null) {
            // Nenhum retângulo legível (layout muito customizado): faixa curta de uma linha, abaixo da mira.
            textPaint.textSize = d(12f)
            val text = fit(if (hint.celebrate) hint.body else hint.title, w * 0.5f)
            textPaint.color = Color.argb((fade * 255).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawText(text, w / 2, h * 0.72f, textPaint)
            return
        }
        val slide = (1f - fade) * d(8f)
        val a = (fade * 255).toInt()
        rect.set(card.left, card.top + slide, card.right, card.bottom + slide)
        p.color = Color.argb((fade * 228).toInt(), 12, 8, 30)
        canvas.drawRoundRect(rect, d(12f), d(12f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.4f)
        p.color = Color.argb((fade * 170).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawRoundRect(rect, d(12f), d(12f), p)
        p.style = Paint.Style.FILL
        // faixa de destaque à esquerda
        p.color = Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent))
        rect.set(card.left + d(7f), card.top + slide + d(10f), card.left + d(10f), card.bottom + slide - d(10f))
        canvas.drawRoundRect(rect, d(1.5f), d(1.5f), p)

        val tx = card.left + d(18f)
        val innerW = card.width - d(18f) - d(10f)
        val y0 = card.top + slide
        val headerPaint = coachPaint
        headerPaint.textAlign = Paint.Align.CENTER
        headerPaint.textSize = d(8.5f)
        headerPaint.color = Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent))
        val header = if (hint.celebrate) "CONCLUÍDO · ${hint.title}" else "PASSO ${hint.step}/${hint.total} · ${hint.title}"
        val cx = card.left + card.width / 2f
        canvas.drawText(fit2(headerPaint, header, innerW), cx, y0 + d(16f), headerPaint)

        // corpo: reduz a letra até caber em até 3 linhas
        var size = 10f
        var lines = emptyList<String>()
        while (true) {
            headerPaint.textSize = d(size)
            lines = wrapText(headerPaint, hint.body, innerW)
            if ((lines.size <= 3 && (lines.size - 1) * size * 1.2f <= 33f) || size <= 8f) break
            size -= 0.5f
        }
        headerPaint.color = Color.argb(a, 244, 247, 255)
        for ((i, line) in lines.take(3).withIndex()) canvas.drawText(line, cx, y0 + d(33f) + i * d(size * 1.2f), headerPaint)
        headerPaint.textAlign = Paint.Align.CENTER

        hint.progress?.let { f ->
            val bx0 = tx
            val bx1 = card.right - d(10f)
            val by = card.bottom + slide - d(7f)
            rect.set(bx0, by, bx1, by + d(3f))
            p.color = Color.argb((fade * 120).toInt(), 255, 255, 255)
            canvas.drawRoundRect(rect, d(1.5f), d(1.5f), p)
            rect.right = bx0 + (bx1 - bx0) * f.coerceIn(0f, 1f)
            p.color = Color.argb(a, Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawRoundRect(rect, d(1.5f), d(1.5f), p)
        }

        coachSkipActive = hint.skippable && !hint.celebrate
        if (coachSkipActive) {
            val s = CoachPlacement.skipRect(card, density)
            rect.set(s.left, s.top + slide, s.right, s.bottom + slide)
            p.color = Color.argb((fade * 235).toInt(), 30, 22, 58)
            canvas.drawRoundRect(rect, d(11f), d(11f), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(1f)
            p.color = Color.argb((fade * 160).toInt(), 154, 166, 192)
            canvas.drawRoundRect(rect, d(11f), d(11f), p)
            p.style = Paint.Style.FILL
            headerPaint.textSize = d(9f)
            headerPaint.color = Color.argb(a, 200, 208, 226)
            canvas.drawText("PULAR TUTORIAL", (s.left + s.right) / 2, s.top + slide + d(15f), headerPaint)
        }
    }

    private fun fit2(paint: Paint, text: String, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    /** Anel pulsante em volta de ([cx], [cy]); [grow] escala a expansão da pulsação (menor em alvos pequenos). */
    private fun focusRing(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float = 1f, grow: Float = 1f) {
        val ph = (SystemClock.uptimeMillis() % 1100L) / 1100f
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(3f) * grow.coerceAtLeast(0.6f)
        p.color = color
        p.alpha = ((0.9f - 0.6f * ph) * 255f * alpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r + d(4f) * grow + d(10f) * grow * ph, p)
        p.strokeWidth = d(2f) * grow.coerceAtLeast(0.6f)
        p.color = color
        p.alpha = (230 * alpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r + d(3f) * grow, p)
        p.style = Paint.Style.FILL
        p.alpha = 255
    }

    private fun focusLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int, alpha: Float) {
        textPaint.textSize = d(11f)
        textPaint.color = Color.argb((alpha * 170).toInt(), 0, 0, 0)
        canvas.drawText(text, x + d(1f), y + d(1f), textPaint)
        textPaint.color = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawText(text, x, y, textPaint)
    }

    /** Seta na borda da tela apontando para um ponto do mundo que está fora da tela. */
    private fun edgeArrow(canvas: Canvas, w: Float, h: Float, dx: Float, dz: Float, color: Int, alpha: Float) {
        val rel = atan2(dx, -dz) - hud[Hud.CAM_YAW]
        val x = w / 2 + sin(rel) * w * 0.40f
        val y = h / 2 - cos(rel) * h * 0.36f
        val s = d(12f)
        val bob = 1f + 0.12f * sin(SystemClock.uptimeMillis() / 180.0).toFloat()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(rel.toDouble()).toFloat())
        canvas.scale(bob, bob)
        path.reset()
        path.moveTo(0f, -s); path.lineTo(s * 0.85f, s * 0.75f); path.lineTo(0f, s * 0.35f); path.lineTo(-s * 0.85f, s * 0.75f); path.close()
        p.color = color
        p.alpha = (alpha * 235).toInt().coerceIn(0, 255)
        canvas.drawPath(path, p)
        p.alpha = 255
        canvas.restore()
    }

    /** Realce do que a lição ensina (anel/seta); só em controles visíveis. */
    private fun drawCoachFocus(canvas: Canvas, w: Float, h: Float, hint: CoachHint, alpha: Float) {
        val color = coachAccent(hint.focus)
        val px = hud[Hud.PLAYER_X]
        val pz = hud[Hud.PLAYER_Z]
        when (hint.focus) {
            CoachFocus.JOYSTICK -> if (g(HudControl.JOYSTICK).visible && !stickActive) focusRing(canvas, stickHomeX, stickHomeY, stickRadius, color, alpha)
            CoachFocus.LOOK_AREA -> {
                // Gesto de arrastar na metade oposta ao joystick (respeita o modo canhoto).
                val cx = if (stickHomeX < w / 2) w * 0.76f else w * 0.24f
                val cy = h * 0.40f
                val t = SystemClock.uptimeMillis() / 700.0
                val dx = sin(t).toFloat() * d(46f)
                p.color = color
                p.alpha = (alpha * 70).toInt().coerceIn(0, 255)
                rect.set(cx - d(70f), cy - d(26f), cx + d(70f), cy + d(26f))
                canvas.drawRoundRect(rect, d(26f), d(26f), p)
                p.alpha = (alpha * 235).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx + dx, cy, d(13f), p)
                for (side in intArrayOf(-1, 1)) {
                    val ax = cx + side * d(58f)
                    path.reset()
                    path.moveTo(ax + side * d(8f), cy); path.lineTo(ax, cy - d(7f)); path.lineTo(ax, cy + d(7f)); path.close()
                    canvas.drawPath(path, p)
                }
                p.alpha = 255
            }
            CoachFocus.FIRE_BUTTON -> {
                val c = if (g(HudControl.FIRE).visible) HudControl.FIRE else HudControl.FIRE_LEFT
                if (g(c).visible) focusRing(canvas, g(c).x, g(c).y, g(c).r, color, alpha)
            }
            CoachFocus.MAP_BUTTON -> if (g(HudControl.MAP).visible) focusRing(canvas, mapX, mapY, mapSize / 2f, color, alpha)
            CoachFocus.NEST_MARKER -> {
                val i = hint.focusIndex
                if (i in 0 until hud[Hud.OBJ_COUNT].toInt().coerceIn(0, 4)) {
                    val o = Hud.OBJ + i * Hud.OBJ_STRIDE
                    val spot = markerSpot(hud[o + 3], hud[o + 4], hud[o + 5] > 0.5f, w, h, false)
                    if (spot != null) focusRing(canvas, spot.first, spot.second, d(14f), color, alpha)
                    else edgeArrow(canvas, w, h, hud[o] - px, hud[o + 1] - pz, color, alpha)
                }
            }
            CoachFocus.NEST_CORE -> {
                val i = hint.focusIndex
                if (i in 0 until hud[Hud.OBJ_COUNT].toInt().coerceIn(0, 4)) {
                    val o = Hud.OBJ + i * Hud.OBJ_STRIDE
                    if (hud[o + 2].toInt() == 1 && hud[o + 5] > 0.5f) {
                        // O marcador é projetado 1,5 m acima do núcleo: desce para o centro do núcleo.
                        val dist = hypot(hud[o] - px, hud[o + 1] - pz).coerceAtLeast(2f)
                        val focal = (h / 2) / tan(hud[Hud.FOV].coerceAtLeast(0.3f) / 2)
                        val cx = hud[o + 3] * w
                        val cy = hud[o + 4] * h + 1.5f * focal / dist
                        val r = (focal * 1.1f / dist).coerceIn(d(14f), d(40f))
                        focusRing(canvas, cx, cy, r, color, alpha)
                        focusLabel(canvas, "NÚCLEO", cx, cy + r + d(20f), color, alpha)
                    }
                }
            }
            CoachFocus.CRYSTAL -> {
                var best = -1
                var bestD = Float.MAX_VALUE
                for (k in 0 until crystalCount()) {
                    val o = Hud.CRYSTAL_ITEMS + k * Hud.CRYSTAL_STRIDE
                    val dd = hypot(hud[o] - px, hud[o + 1] - pz)
                    if (dd < bestD) { bestD = dd; best = k }
                }
                if (best >= 0) {
                    val o = Hud.CRYSTAL_ITEMS + best * Hud.CRYSTAL_STRIDE
                    if (hud[o + 4] > 0.5f) focusRing(canvas, hud[o + 2] * w, hud[o + 3] * h, d(16f), color, alpha)
                    else edgeArrow(canvas, w, h, hud[o] - px, hud[o + 1] - pz, color, alpha)
                }
            }
            CoachFocus.PORTAL -> {
                if (hud[Hud.PORTAL_ONSCREEN] > 0.5f) focusRing(canvas, hud[Hud.PORTAL_SX] * w, hud[Hud.PORTAL_SY] * h, d(22f), color, alpha)
                else edgeArrow(canvas, w, h, hud[Hud.PORTAL_X] - px, hud[Hud.PORTAL_Z] - pz, color, alpha)
            }
            CoachFocus.NONE -> Unit
        }
    }

    private fun drawTip(canvas: Canvas, w: Float, h: Float) {
        val (text, age) = tipState.current(System.currentTimeMillis()) ?: return
        val a = if (age < 4300) 1f else ((5000 - age) / 700f).coerceIn(0f, 1f)
        textPaint.textSize = d(13f)
        val tw = textPaint.measureText(text) + d(30f)
        val y = h * 0.72f
        rect.set(w / 2 - tw / 2, y - d(20f), w / 2 + tw / 2, y + d(10f))
        p.color = Color.argb((a * 210).toInt(), 20, 12, 30)
        canvas.drawRoundRect(rect, d(12f), d(12f), p)
        textPaint.color = Color.argb((a * 255).toInt(), 255, 214, 110)
        canvas.drawText(text, w / 2, y, textPaint)
    }

    private fun drawButton(canvas: Canvas, x: Float, y: Float, r: Float, fill: Int, active: Boolean) {
        p.color = Color.argb(110, 0, 0, 0)
        canvas.drawCircle(x, y + d(3f), r, p)
        p.color = if (active) fill else Color.argb(150, 12, 20, 36)
        canvas.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2.2f)
        p.color = if (active) Color.WHITE else Color.argb(170, Color.red(fill), Color.green(fill), Color.blue(fill))
        canvas.drawCircle(x, y, r, p)
        // aro interno discreto: dá relevo sem mudar a área do botão
        p.strokeWidth = d(1f)
        p.color = Color.argb(if (active) 70 else 36, 255, 255, 255)
        canvas.drawCircle(x, y, (r - d(4f)).coerceAtLeast(d(2f)), p)
        p.style = Paint.Style.FILL
    }

    private fun drawControls(canvas: Canvas) {
        val alpha = opacity
        val save = if (alpha < 0.99f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt()) else -1
        if (editing) for (c in HudControl.entries) if (!g(c).visible) drawGhost(canvas, c)
        drawJoystick(canvas)
        val firing = hud[Hud.FIRING] > 0.5f || touches.values.any { it.role == Role.FIRE || it.role == Role.FIRE_LEFT }
        if (available(HudControl.FIRE)) drawFireButton(canvas, HudControl.FIRE, firing, true)
        if (available(HudControl.FIRE_LEFT)) drawFireButton(canvas, HudControl.FIRE_LEFT, firing, false)
        if (available(HudControl.AIM)) drawAim(canvas)
        if (available(HudControl.JUMP)) drawJump(canvas)
        if (available(HudControl.RELOAD)) drawReload(canvas)
        if (available(HudControl.CROUCH)) drawCrouch(canvas)
        if (available(HudControl.SPRINT)) drawSprint(canvas)
        if (available(HudControl.MELEE)) drawMelee(canvas)
        if (available(HudControl.GRENADE)) drawGrenade(canvas)
        if (available(HudControl.MEDKIT)) drawMedkit(canvas)
        if (available(HudControl.PAUSE)) drawPause(canvas)
        drawClock(canvas)
        if (available(HudControl.BAG)) drawBag(canvas)
        if (save >= 0) canvas.restoreToCount(save)
    }

    // ------------------------------------------------------------------ inventário, arremesso e sobrevivência

    private fun drawHotbar(canvas: Canvas) {
        val left = hotbarX - slotSize * 3f
        val top = hotbarY - slotSize / 2f
        val weaponSlot = hud[Hud.WEAPON_SLOT].toInt()
        val throwSlot = hud[Hud.THROW_SLOT].toInt()
        rect.set(left - d(4f), top - d(4f), left + slotSize * 6f + d(4f), top + slotSize + d(4f))
        p.color = Color.argb(150, 0, 0, 0)
        canvas.drawRoundRect(rect, d(8f), d(8f), p)
        for (i in 0 until 6) {
            val x = left + i * slotSize
            rect.set(x + d(2f), top + d(2f), x + slotSize - d(2f), top + slotSize - d(2f))
            p.color = Color.argb(200, 30, 36, 50)
            canvas.drawRoundRect(rect, d(5f), d(5f), p)
            val id = if (editing) listOf(Items.RIFLE, Items.PISTOL, Items.GRENADE, Items.BANDAGE, Items.MEDKIT, 0)[i] else hud[Hud.INV + i * 2].toInt()
            val count = if (editing) (if (i in 2..3) 3 else 1) else hud[Hud.INV + i * 2 + 1].toInt()
            val pressed = touches.values.any { it.role == Role.HOTBAR && it.slot == i }
            val equipped = id != 0 && i == weaponSlot && Items.isWeapon(id)
            if (equipped) {
                // arma em mãos: fundo esverdeado e sublinhado
                p.color = Color.argb(70, 168, 255, 62)
                canvas.drawRoundRect(rect, d(5f), d(5f), p)
                p.color = Palette.lime
                canvas.drawRoundRect(rect.left + d(6f), rect.bottom + d(0.5f), rect.right - d(6f), rect.bottom + d(3f), d(1.2f), d(1.2f), p)
            }
            val border = when {
                pressed -> Color.WHITE
                id != 0 && i == weaponSlot && Items.isWeapon(id) -> Palette.lime
                id != 0 && i == throwSlot && Items.isThrowable(id) -> Color.rgb(255, 150, 60)
                else -> Color.argb(90, 255, 255, 255)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(if (border == Color.argb(90, 255, 255, 255)) 1.2f else 2.6f)
            p.color = border
            canvas.drawRoundRect(rect, d(5f), d(5f), p)
            p.style = Paint.Style.FILL
            if (id != 0 && count > 0) {
                ItemIcons.draw(canvas, id, rect.centerX(), rect.centerY(), rect.width() * 0.82f)
                if (count > 1) {
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.textSize = slotSize * 0.28f
                    textPaint.color = Color.argb(200, 0, 0, 0)
                    canvas.drawText("$count", rect.right - d(2f) + d(1f), rect.bottom - d(3f) + d(1f), textPaint)
                    textPaint.color = Color.WHITE
                    canvas.drawText("$count", rect.right - d(2f), rect.bottom - d(3f), textPaint)
                    textPaint.textAlign = Paint.Align.CENTER
                }
            }
        }
        // Arma em mãos e munição acima da barra.
        val weapon = if (editing) Items.RIFLE else hud[Hud.WEAPON].toInt()
        val def = Items.get(weapon) ?: return
        if (weapon != lastWeapon && lastWeapon >= 0 && !editing) haptic(false)
        lastWeapon = weapon
        val ammo = hud[Hud.AMMO].toInt()
        val mag = hud[Hud.MAG].toInt()
        val reloading = hud[Hud.RELOAD] >= 0f
        val text = def.name.uppercase() + if (reloading) "  •  RECARREGANDO" else "  •  $ammo/$mag"
        textPaint.textSize = d(11f)
        val tw = textPaint.measureText(text) + d(16f)
        rect.set(hotbarX - tw / 2, top - d(24f), hotbarX + tw / 2, top - d(7f))
        p.color = Color.argb(160, 8, 14, 26)
        canvas.drawRoundRect(rect, d(8f), d(8f), p)
        textPaint.color = if (!reloading && ammo <= mag / 4) Palette.gold else def.color
        canvas.drawText(text, hotbarX, top - d(11.5f), textPaint)
    }

    private fun drawBag(canvas: Canvas) {
        val gc = g(HudControl.BAG)
        val s = k(HudControl.BAG)
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.gold, false)
        // Mochila: corpo, tampa e alça.
        p.color = Color.rgb(170, 120, 60)
        rect.set(gc.x - d(9f) * s, gc.y - d(6f) * s, gc.x + d(9f) * s, gc.y + d(10f) * s)
        canvas.drawRoundRect(rect, d(3f) * s, d(3f) * s, p)
        p.color = Color.rgb(130, 88, 40)
        rect.set(gc.x - d(9f) * s, gc.y - d(6f) * s, gc.x + d(9f) * s, gc.y + d(1f) * s)
        canvas.drawRoundRect(rect, d(3f) * s, d(3f) * s, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f) * s
        canvas.drawArc(gc.x - d(5f) * s, gc.y - d(12f) * s, gc.x + d(5f) * s, gc.y - d(2f) * s, 180f, 180f, false, p)
        p.style = Paint.Style.FILL
        p.color = Palette.gold
        canvas.drawRect(gc.x - d(2f) * s, gc.y, gc.x + d(2f) * s, gc.y + d(3f) * s, p)
    }

    /** Durante a mira do arremesso: X para cancelar, instrução e distância no ponto de queda. */
    private fun drawThrowAim(canvas: Canvas, w: Float, h: Float) {
        if (hud[Hud.THROW_AIM] < 0.5f) return
        val (cx, cy) = cancelSpot()
        val r = g(HudControl.GRENADE).r * 0.9f
        val over = touches.values.any { it.role == Role.GRENADE && it.cancel }
        drawButton(canvas, cx, cy, r, Palette.red, over)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(3f)
        p.color = if (over) Palette.dark else Palette.red
        canvas.drawLine(cx - r * 0.4f, cy - r * 0.4f, cx + r * 0.4f, cy + r * 0.4f, p)
        canvas.drawLine(cx + r * 0.4f, cy - r * 0.4f, cx - r * 0.4f, cy + r * 0.4f, p)
        p.style = Paint.Style.FILL
        val kind = hud[Hud.THROW_KIND].toInt()
        val molotov = kind == Items.MOLOTOV
        val smoke = kind == Items.SMOKE
        val itemName = when {
            molotov -> "O MOLOTOV"
            smoke -> "A FUMAÇA"
            else -> "A GRANADA"
        }
        val text = if (over) "SOLTE PARA CANCELAR" else "SOLTE PARA LANÇAR $itemName • ARRASTE ATÉ ✕ PARA CANCELAR"
        textPaint.textSize = d(13f)
        val tw = textPaint.measureText(text) + d(24f)
        rect.set(w / 2 - tw / 2, h * 0.64f - d(18f), w / 2 + tw / 2, h * 0.64f + d(8f))
        p.color = Color.argb(190, 30, 14, 6)
        canvas.drawRoundRect(rect, d(12f), d(12f), p)
        textPaint.color = if (over) Palette.red else Color.rgb(255, 214, 120)
        canvas.drawText(text, w / 2, h * 0.64f, textPaint)
        if (hud[Hud.THROW_ONSCREEN] > 0.5f) {
            val x = hud[Hud.THROW_SX] * w
            val y = hud[Hud.THROW_SY] * h
            val label = "${hud[Hud.THROW_DIST].toInt()} m"
            textPaint.textSize = d(14f)
            textPaint.color = Color.argb(160, 0, 0, 0)
            canvas.drawText(label, x + d(1.5f), y - d(10f) + d(1.5f), textPaint)
            textPaint.color = when {
                molotov -> Color.rgb(255, 170, 80)
                smoke -> Color.rgb(205, 220, 230)
                else -> Color.rgb(255, 230, 120)
            }
            canvas.drawText(label, x, y - d(10f), textPaint)
        }
    }

    /** Luneta do rifle de precisão: tudo escuro fora do círculo, com retícula fina. */
    private fun drawScope(canvas: Canvas, w: Float, h: Float) {
        if (hud[Hud.WEAPON].toInt() != Items.SNIPER || aimStage == 0 || hud[Hud.AIM] < 0.85f) return
        val r = h * 0.42f
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addCircle(w / 2, h / 2, r, Path.Direction.CW)
        p.color = Color.argb(235, 0, 0, 0)
        canvas.drawPath(path, p)
        path.fillType = Path.FillType.WINDING
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2.2f)
        p.color = Color.argb(235, 10, 10, 10)
        canvas.drawCircle(w / 2, h / 2, r, p)
        p.strokeWidth = d(1.1f)
        canvas.drawLine(w / 2 - r, h / 2, w / 2 + r, h / 2, p)
        canvas.drawLine(w / 2, h / 2 - r, w / 2, h / 2 + r, p)
        val tick = if (aimStage >= 2) d(15f) else d(11f)
        for (i in -3..3) {
            if (i == 0) continue
            val offset = i * tick
            canvas.drawLine(w / 2 + offset, h / 2 - d(4f), w / 2 + offset, h / 2 + d(4f), p)
            canvas.drawLine(w / 2 - d(4f), h / 2 + offset, w / 2 + d(4f), h / 2 + offset, p)
        }
        p.style = Paint.Style.FILL
        p.color = Color.rgb(220, 38, 38)
        canvas.drawCircle(w / 2, h / 2, d(2.2f), p)
        textPaint.textSize = d(13f)
        textPaint.color = Color.WHITE
        canvas.drawText(if (aimStage >= 2) "8×" else "4×", w / 2, h / 2 + r - d(18f), textPaint)
    }

    /** Sobrevivência: direções de onde a horda está vindo (setas vermelhas numa faixa). */
    private fun drawFronts(canvas: Canvas, w: Float) {
        val n = hud[Hud.FRONT_COUNT].toInt()
        if (n <= 0) return
        val yaw = hud[Hud.CAM_YAW]
        val cw = d(300f)
        val top = d(84f)
        val left = w / 2 - cw / 2
        rect.set(left, top, left + cw, top + d(16f))
        p.color = Color.argb(120, 40, 6, 6)
        canvas.drawRoundRect(rect, d(8f), d(8f), p)
        val half = (PI / 2).toFloat()
        for (i in 0 until n) {
            val rel = wrapAngle(hud[Hud.FRONTS + i] - yaw)
            val pulse = 0.75f + 0.25f * sin(System.currentTimeMillis() / 150.0).toFloat()
            p.color = Color.argb((255 * pulse).toInt(), 255, 70, 60)
            val cy = top + d(8f)
            val s = d(6f)
            if (abs(rel) <= half) {
                val x = w / 2 + rel / half * (cw / 2 - d(8f))
                path.reset()
                path.moveTo(x, cy + s); path.lineTo(x - s, cy - s); path.lineTo(x + s, cy - s); path.close()
                canvas.drawPath(path, p)
            } else {
                val right = rel > 0
                val x = if (right) left + cw - d(8f) else left + d(8f)
                path.reset()
                if (right) { path.moveTo(x + s, cy); path.lineTo(x - s * 0.6f, cy - s); path.lineTo(x - s * 0.6f, cy + s) }
                else { path.moveTo(x - s, cy); path.lineTo(x + s * 0.6f, cy - s); path.lineTo(x + s * 0.6f, cy + s) }
                path.close()
                canvas.drawPath(path, p)
            }
        }
        textPaint.textSize = d(9f)
        textPaint.color = Color.argb(200, 255, 140, 120)
        canvas.drawText("HORDA VINDO DAQUI", w / 2, top + d(26f), textPaint)
    }

    /** Sobrevivência: onda atual, barra de progresso com bandeira (estilo Plants vs Zombies), tempo e pontos. */
    private fun drawSurvivalTop(canvas: Canvas, w: Float, h: Float) {
        val wave = hud[Hud.WAVE].toInt()
        val state = hud[Hud.WAVE_STATE].toInt()
        val big = hud[Hud.WAVE_BIG] > 0.5f
        val t = hud[Hud.SURV_TIME].toInt()
        val kills = hud[Hud.KILLS].toInt()
        val score = hud[Hud.SCORE].toInt()
        val title = if (state == 1) "ONDA $wave" + if (big) " • GRANDE HORDA" else "" else "ONDA ${wave + 1} EM ${kotlin.math.ceil(hud[Hud.WAVE_TIMER]).toInt()}s"
        val text = "$title   •   ☠ $kills   •   %d:%02d   •   ★ $score".format(t / 60, t % 60)
        textPaint.textSize = d(14f)
        val tw = textPaint.measureText(text) + d(36f)
        rect.set(w / 2 - tw / 2, d(12f), w / 2 + tw / 2, d(44f))
        p.color = if (big && state == 1) Color.argb(220, 90, 20, 20) else Color.argb(200, 8, 14, 26)
        canvas.drawRoundRect(rect, d(16f), d(16f), p)
        textPaint.color = if (state == 1) (if (big) Color.rgb(255, 150, 120) else Palette.lime) else Palette.gold
        canvas.drawText(text, w / 2, d(33f), textPaint)
        // Barra de progresso da onda com a cabeça de zumbi avançando e a bandeira no fim.
        val bw = d(240f)
        val top = d(52f)
        val prog = if (state == 1) hud[Hud.WAVE_PROGRESS].coerceIn(0f, 1f) else if (state == 2) 1f else 0f
        rect.set(w / 2 - bw / 2, top, w / 2 + bw / 2, top + d(10f))
        p.color = Color.argb(170, 0, 0, 0)
        canvas.drawRoundRect(rect, d(5f), d(5f), p)
        rect.set(w / 2 - bw / 2, top, w / 2 - bw / 2 + bw * prog, top + d(10f))
        p.color = if (big) Color.rgb(230, 70, 60) else Color.rgb(140, 210, 70)
        canvas.drawRoundRect(rect, d(5f), d(5f), p)
        val fx = w / 2 + bw / 2
        p.color = Color.rgb(200, 200, 200)
        canvas.drawRect(fx - d(1f), top - d(8f), fx + d(1f), top + d(12f), p)
        p.color = if (big) Palette.red else Palette.gold
        path.reset()
        path.moveTo(fx + d(1f), top - d(8f)); path.lineTo(fx + d(12f), top - d(4f)); path.lineTo(fx + d(1f), top); path.close()
        canvas.drawPath(path, p)
        val hx = w / 2 - bw / 2 + bw * prog
        p.color = Color.rgb(120, 190, 90)
        canvas.drawCircle(hx, top + d(5f), d(8f), p)
        p.color = Color.rgb(30, 40, 20)
        canvas.drawCircle(hx - d(3f), top + d(3.5f), d(1.6f), p)
        canvas.drawCircle(hx + d(3f), top + d(3.5f), d(1.6f), p)
        canvas.drawRect(hx - d(3f), top + d(7.5f), hx + d(3f), top + d(9f), p)
        if (state == 1) {
            textPaint.textSize = d(10f)
            textPaint.color = Palette.muted
            canvas.drawText("RESTAM ${hud[Hud.WAVE_LEFT].toInt()} ZUMBIS", w / 2, top + d(24f), textPaint)
        }
        // Aviso grande no começo das grandes hordas e na contagem final do intervalo.
        val timer = hud[Hud.WAVE_TIMER]
        val banner = when {
            state == 1 && big && timer < 4f -> "⚠ UMA GRANDE HORDA DE ZUMBIS ESTÁ CHEGANDO!"
            state == 1 && timer < 3f -> "ONDA $wave!"
            state != 1 && timer <= 5f -> "PREPARE-SE: ${kotlin.math.ceil(timer).toInt()}"
            state == 2 && timer > 12f -> "ONDA ${wave} VENCIDA! +VIDA E UM ITEM"
            else -> null
        }
        if (banner != null) {
            val pulse = 1f + 0.06f * sin(System.currentTimeMillis() / 120.0).toFloat()
            textPaint.textSize = d(24f) * pulse
            textPaint.color = Color.argb(160, 0, 0, 0)
            // Usa a faixa do aviso de combate (que fica suprimido enquanto o banner existe): um só aviso por vez.
            bannerShown = true
            val by = d(CombatFeed.noticeBaselineDp(h / d(1f)))
            canvas.drawText(banner, w / 2 + d(2f), by + d(2f), textPaint)
            textPaint.color = if (big && state == 1) Palette.red else if (state == 2) Palette.lime else Palette.gold
            canvas.drawText(banner, w / 2, by, textPaint)
        }
    }

    /** Escala do controle (para textos e ícones acompanharem o tamanho escolhido). */
    private fun k(c: HudControl): Float = g(c).r / d(c.baseDp)

    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        textPaint.textSize = d(maxOf(size, 7f))   // legível mesmo com o controle reduzido
        textPaint.color = color
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawJoystick(canvas: Canvas) {
        val r = stickRadius
        val lit = stickActive || autoRun
        if (!g(HudControl.JOYSTICK).visible && !lit) return
        p.color = Color.argb(if (lit) 70 else 40, 255, 255, 255)
        canvas.drawCircle(stickBaseX, stickBaseY, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = if (autoRun) Palette.gold else Color.argb(if (lit) 160 else 90, 168, 255, 62)
        canvas.drawCircle(stickBaseX, stickBaseY, r, p)
        p.style = Paint.Style.FILL
        p.color = if (autoRun) Palette.gold else Color.argb(if (lit) 220 else 120, 168, 255, 62)
        // Corrida automática sem o polegar no joystick: a bolinha fica empurrada para a frente.
        canvas.drawCircle(stickX, if (autoRun && !stickActive) stickBaseY - r * 0.6f else stickY, r * 0.41f, p)
        val ks = k(HudControl.JOYSTICK)
        when {
            autoRun -> {
                val text = "» CORRIDA AUTOMÁTICA"
                textPaint.textSize = d(12f) * ks
                val tw = textPaint.measureText(text) + d(16f)
                val ty = stickBaseY - r - d(18f)
                rect.set(stickBaseX - tw / 2, ty - d(14f), stickBaseX + tw / 2, ty + d(6f))
                p.color = Color.argb(200, 40, 30, 8)
                canvas.drawRoundRect(rect, d(9f), d(9f), p)
                label(canvas, text, stickBaseX, ty, 12f * ks, Palette.gold)
            }
            hud[Hud.SPRINTING] > 0.5f -> label(canvas, "» CORRENDO", stickBaseX, stickBaseY - r - d(10f), 13f * ks, Palette.lime)
            !stickActive -> label(canvas, if (hud[Hud.SLOWED] > 0f) "LENTO (ÁCIDO)" else "MOVER", stickBaseX, stickBaseY + r + d(14f), 10f * ks, Color.argb(140, 255, 255, 255))
        }
    }

    private fun drawFireButton(canvas: Canvas, c: HudControl, firing: Boolean, main: Boolean) {
        val gc = g(c)
        val ks = k(c)
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.lime, firing)
        drawCrosshairIcon(canvas, gc.x, gc.y, gc.r * 0.35f, if (firing) Palette.dark else Palette.lime)
        val reload = hud[Hud.RELOAD]
        if (main && reload >= 0f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(5f)
            p.color = Palette.gold
            rect.set(gc.x - gc.r - d(5f), gc.y - gc.r - d(5f), gc.x + gc.r + d(5f), gc.y + gc.r + d(5f))
            canvas.drawArc(rect, -90f, 360f * reload, false, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun drawAim(canvas: Canvas) {
        val gc = g(HudControl.AIM)
        val s = k(HudControl.AIM)
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.cyan, aiming)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = if (aiming) Palette.dark else Palette.cyan
        canvas.drawCircle(gc.x, gc.y - d(3f) * s, d(9f) * s, p)
        canvas.drawLine(gc.x - d(13f) * s, gc.y - d(3f) * s, gc.x + d(13f) * s, gc.y - d(3f) * s, p)
        canvas.drawLine(gc.x, gc.y - d(16f) * s, gc.x, gc.y + d(10f) * s, p)
        p.style = Paint.Style.FILL
    }

    private fun drawJump(canvas: Canvas) {
        val gc = g(HudControl.JUMP)
        val s = k(HudControl.JUMP)
        val jumpReady = hud[Hud.JUMP_READY] > 0.5f || hud[Hud.IN_WATER] > 0.5f
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.lime, false)
        val x = gc.x
        val y = gc.y
        path.reset()
        path.moveTo(x, y - d(12f) * s); path.lineTo(x + d(10f) * s, y); path.lineTo(x + d(4f) * s, y)
        path.lineTo(x + d(4f) * s, y + d(8f) * s); path.lineTo(x - d(4f) * s, y + d(8f) * s); path.lineTo(x - d(4f) * s, y); path.lineTo(x - d(10f) * s, y); path.close()
        p.color = if (jumpReady) Palette.lime else Palette.muted
        canvas.drawPath(path, p)
    }

    private fun drawReload(canvas: Canvas) {
        val gc = g(HudControl.RELOAD)
        val s = k(HudControl.RELOAD)
        val ammo = hud[Hud.AMMO].toInt()
        val mag = hud[Hud.MAG].toInt().coerceAtLeast(1)
        val lowAmmo = ammo <= mag / 4
        drawButton(canvas, gc.x, gc.y, gc.r, if (lowAmmo) Palette.gold else Palette.lime, false)
        // Anel: cheio com o pente cheio, esvazia a cada tiro e some em zero; ao recarregar volta a encher.
        val reloading = hud[Hud.RELOAD] >= 0f
        val frac = if (reloading) 1f else (ammo.toFloat() / mag).coerceIn(0f, 1f)
        val ringR = gc.r + d(5f) * s
        rect.set(gc.x - ringR, gc.y - ringR, gc.x + ringR, gc.y + ringR)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = d(4f) * s
        p.color = Color.argb(90, 0, 0, 0)
        canvas.drawArc(rect, -90f, 360f, false, p)
        p.color = if (reloading) Palette.cyan else if (lowAmmo) Palette.gold else Palette.lime
        if (frac > 0f) canvas.drawArc(rect, -90f, 360f * frac, false, p)
        p.style = Paint.Style.FILL
        drawCartridge(canvas, gc.x, gc.y, d(26f) * s)
    }

    /** Cartucho de munição (corpo de latão com ponta), centralizado em (x, y). */
    private fun drawCartridge(canvas: Canvas, x: Float, y: Float, h: Float) {
        val w = h * 0.46f
        val top = y - h / 2f
        val neck = top + h * 0.34f
        p.color = Color.rgb(212, 170, 80)
        rect.set(x - w / 2f, neck, x + w / 2f, y + h / 2f)
        canvas.drawRoundRect(rect, w * 0.15f, w * 0.15f, p)
        p.color = Color.rgb(236, 236, 242)
        path.reset()
        path.moveTo(x - w / 2f, neck)
        path.lineTo(x, top)
        path.lineTo(x + w / 2f, neck)
        path.close()
        canvas.drawPath(path, p)
    }

    /** Tempo discreto, centralizado na base da tela (onde ficava a barra de itens). */
    private fun drawClock(canvas: Canvas) {
        val elapsed = hud[Hud.ELAPSED].toInt()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = d(11f)
        textPaint.color = Color.argb(170, 255, 255, 255)
        canvas.drawText("%02d:%02d".format(elapsed / 60, elapsed % 60), width / 2f, height - d(10f), textPaint)
    }

    private fun drawCrouch(canvas: Canvas) {
        val gc = g(HudControl.CROUCH)
        val s = k(HudControl.CROUCH)
        val on = hud[Hud.CROUCH] > 0.5f
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.lime, on)
        // Bonequinho abaixado.
        val color = if (on) Palette.dark else Palette.lime
        p.color = color
        canvas.drawCircle(gc.x + d(2f) * s, gc.y - d(9f) * s, d(3.2f) * s, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2.6f) * s
        p.strokeCap = Paint.Cap.ROUND
        path.reset()
        path.moveTo(gc.x + d(1f) * s, gc.y - d(5f) * s)
        path.lineTo(gc.x - d(4f) * s, gc.y + d(1f) * s)
        path.lineTo(gc.x + d(4f) * s, gc.y + d(3f) * s)
        path.lineTo(gc.x + d(2f) * s, gc.y + d(8f) * s)
        canvas.drawPath(path, p)
        canvas.drawLine(gc.x - d(4f) * s, gc.y + d(1f) * s, gc.x - d(8f) * s, gc.y + d(8f) * s, p)
        p.style = Paint.Style.FILL
    }

    private fun drawSprint(canvas: Canvas) {
        val gc = g(HudControl.SPRINT)
        val s = k(HudControl.SPRINT)
        val on = hud[Hud.SPRINT_LOCK] > 0.5f || hud[Hud.SPRINTING] > 0.5f || autoRun
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.gold, on)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(3f) * s
        p.strokeCap = Paint.Cap.ROUND
        p.color = if (on) Palette.dark else Palette.gold
        for (i in 0..1) {
            val y = gc.y - d(4f) * s + i * d(7f) * s
            canvas.drawLine(gc.x - d(7f) * s, y, gc.x, y - d(6f) * s, p)
            canvas.drawLine(gc.x, y - d(6f) * s, gc.x + d(7f) * s, y, p)
        }
        p.style = Paint.Style.FILL
    }

    private fun drawMelee(canvas: Canvas) {
        val gc = g(HudControl.MELEE)
        val s = k(HudControl.MELEE)
        val cooldown = hud[Hud.MELEE].coerceIn(0f, 1f)
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.red, false)
        // Faca na diagonal: lâmina e cabo.
        canvas.save()
        canvas.rotate(-45f, gc.x, gc.y - d(2f) * s)
        p.color = Color.rgb(220, 226, 236)
        path.reset()
        path.moveTo(gc.x - d(2.5f) * s, gc.y - d(2f) * s)
        path.lineTo(gc.x + d(2.5f) * s, gc.y - d(2f) * s)
        path.lineTo(gc.x + d(2.5f) * s, gc.y - d(12f) * s)
        path.lineTo(gc.x - d(2.5f) * s, gc.y - d(15f) * s)
        path.close()
        canvas.drawPath(path, p)
        p.color = Color.rgb(140, 90, 50)
        canvas.drawRect(gc.x - d(2.2f) * s, gc.y - d(2f) * s, gc.x + d(2.2f) * s, gc.y + d(6f) * s, p)
        canvas.restore()
        if (cooldown > 0f) {
            p.color = Color.argb(150, 0, 0, 0)
            rect.set(gc.x - gc.r, gc.y - gc.r, gc.x + gc.r, gc.y + gc.r)
            canvas.drawArc(rect, -90f, 360f * cooldown, true, p)
        }
    }

    private fun drawGrenade(canvas: Canvas) {
        val gc = g(HudControl.GRENADE)
        val s = k(HudControl.GRENADE)
        val count = hud[Hud.GRENADES].toInt()
        val aiming = hud[Hud.THROW_AIM] > 0.5f
        val kind = hud[Hud.THROW_KIND].toInt().takeIf { it != 0 } ?: Items.GRENADE
        drawButton(canvas, gc.x, gc.y, gc.r, Color.rgb(255, 170, 70), aiming)
        ItemIcons.draw(canvas, kind, gc.x, gc.y, d(26f) * s, if (count > 0) 255 else 110)
        // Contador.
        val bx = gc.x + gc.r * 0.72f
        val by = gc.y - gc.r * 0.72f
        p.color = if (count > 0) Palette.gold else Palette.stone
        canvas.drawCircle(bx, by, d(8f) * s, p)
        label(canvas, "$count", bx, by + d(3.5f) * s, 10f * s, Palette.dark)
    }

    /** Kit médico: usa o primeiro kit da barra de itens (o mesmo efeito de tocar no slot). */
    private fun useMedkit() {
        val slot = (0 until 6).firstOrNull { hud[Hud.INV + it * 2].toInt() == Items.MEDKIT && hud[Hud.INV + it * 2 + 1] > 0f }
        if (slot == null) message("SEM KIT MÉDICO NA BARRA", Palette.muted) else NativeBridge.useSlot(slot)
    }

    private fun drawMedkit(canvas: Canvas) {
        val gc = g(HudControl.MEDKIT)
        val s = k(HudControl.MEDKIT)
        val count = (0 until 6).sumOf { if (hud[Hud.INV + it * 2].toInt() == Items.MEDKIT) hud[Hud.INV + it * 2 + 1].toInt() else 0 }
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.lime, false)
        ItemIcons.draw(canvas, Items.MEDKIT, gc.x, gc.y, d(26f) * s, if (count > 0) 255 else 110)
        val bx = gc.x + gc.r * 0.72f
        val by = gc.y - gc.r * 0.72f
        p.color = if (count > 0) Palette.gold else Palette.stone
        canvas.drawCircle(bx, by, d(8f) * s, p)
        label(canvas, "$count", bx, by + d(3.5f) * s, 10f * s, Palette.dark)
    }

    private fun drawPause(canvas: Canvas) {
        val gc = g(HudControl.PAUSE)
        val s = k(HudControl.PAUSE)
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.lime, false)
        p.color = Color.WHITE
        canvas.drawRect(gc.x - d(6f) * s, gc.y - d(7f) * s, gc.x - d(2f) * s, gc.y + d(7f) * s, p)
        canvas.drawRect(gc.x + d(2f) * s, gc.y - d(7f) * s, gc.x + d(6f) * s, gc.y + d(7f) * s, p)
    }

    /** Controle oculto, mostrado só no editor para poder ser ligado de novo. */
    private fun drawGhost(canvas: Canvas, c: HudControl) {
        val gc = g(c)
        dash.color = Color.argb(150, 255, 255, 255)
        dash.strokeWidth = d(1.5f)
        dash.pathEffect = android.graphics.DashPathEffect(floatArrayOf(d(5f), d(4f)), 0f)
        canvas.drawCircle(gc.x, gc.y, gc.r, dash)
        label(canvas, c.label.uppercase(), gc.x, gc.y + d(3f), 8f, Color.argb(170, 255, 255, 255))
        label(canvas, "OCULTO", gc.x, gc.y + d(13f), 7f, Color.argb(130, 255, 255, 255))
    }

    private fun drawEditor(canvas: Canvas) {
        java.util.Arrays.fill(hud, 0f)
        hud[Hud.AMMO] = 30f
        hud[Hud.MAG] = 30f
        hud[Hud.RELOAD] = -1f
        hud[Hud.GRENADES] = 3f
        hud[Hud.MELEE] = 0f
        hud[Hud.SUPPLY] = 1f
        hud[Hud.ISLAND] = 1f
        hud[Hud.JUMP_READY] = 1f
        hud[Hud.INV_ENABLED] = 1f
        hud[Hud.WEAPON] = Items.RIFLE.toFloat()
        hud[Hud.WEAPON_SLOT] = 0f
        hud[Hud.THROW_KIND] = Items.GRENADE.toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = Color.argb(24, 255, 255, 255)
        val step = d(32f)
        var x = 0f
        while (x < w) { canvas.drawRect(x, 0f, x + 1f, h, p); x += step }
        var y = 0f
        while (y < h) { canvas.drawRect(0f, y, w, y + 1f, p); y += step }
        if (g(HudControl.MAP).visible) {
            rect.set(mapX - mapSize / 2, mapY - mapSize / 2, mapX + mapSize / 2, mapY + mapSize / 2)
            p.color = Color.argb(200, 8, 14, 26)
            canvas.drawRoundRect(rect, d(14f), d(14f), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(2f)
            p.color = Palette.line
            canvas.drawRoundRect(rect, d(14f), d(14f), p)
            p.style = Paint.Style.FILL
            label(canvas, "MINIMAPA", mapX, mapY + d(4f), 10f * k(HudControl.MAP), Palette.muted)
        }
        if (g(HudControl.SUPPLY).visible) drawSupplyButton(canvas)
        drawControls(canvas)
        val c = selected ?: return
        val gc = g(c)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2.5f)
        p.color = Palette.gold
        when (c) {
            HudControl.MAP -> { rect.set(gc.x - gc.r - d(5f), gc.y - gc.r - d(5f), gc.x + gc.r + d(5f), gc.y + gc.r + d(5f)); canvas.drawRoundRect(rect, d(16f), d(16f), p) }
            HudControl.SUPPLY -> { rect.set(supplyX - supplyW / 2 - d(5f), supplyY - supplyH / 2 - d(5f), supplyX + supplyW / 2 + d(5f), supplyY + supplyH / 2 + d(5f)); canvas.drawRoundRect(rect, d(12f), d(12f), p) }
            HudControl.HOTBAR -> { rect.set(hotbarX - slotSize * 3f - d(6f), hotbarY - slotSize / 2f - d(6f), hotbarX + slotSize * 3f + d(6f), hotbarY + slotSize / 2f + d(6f)); canvas.drawRoundRect(rect, d(10f), d(10f), p) }
            else -> canvas.drawCircle(gc.x, gc.y, gc.r + d(6f), p)
        }
        p.style = Paint.Style.FILL
        val top = if (c == HudControl.SUPPLY) supplyY - supplyH / 2 else gc.y - gc.r
        val text = "${c.label} • ${(layout[c].scale * 100).toInt()}%" + if (!gc.visible) " (oculto)" else ""
        textPaint.textSize = d(12f)
        val tw = textPaint.measureText(text) + d(16f)
        val ty = (top - d(14f)).coerceAtLeast(d(20f))
        rect.set(gc.x - tw / 2, ty - d(14f), gc.x + tw / 2, ty + d(6f))
        p.color = Color.argb(220, 40, 30, 8)
        canvas.drawRoundRect(rect, d(9f), d(9f), p)
        label(canvas, text, gc.x, ty, 12f, Palette.gold)
    }

    private fun drawCrosshairIcon(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(3f)
        p.color = color
        canvas.drawCircle(x, y - d(6f), r * 0.8f, p)
        canvas.drawLine(x - r * 1.2f, y - d(6f), x - r * 0.4f, y - d(6f), p)
        canvas.drawLine(x + r * 0.4f, y - d(6f), x + r * 1.2f, y - d(6f), p)
        canvas.drawLine(x, y - d(6f) - r * 1.2f, x, y - d(6f) - r * 0.4f, p)
        canvas.drawLine(x, y - d(6f) + r * 0.4f, x, y - d(6f) + r * 1.2f, p)
        p.style = Paint.Style.FILL
        p.color = color
        canvas.drawCircle(x, y - d(6f), d(2.5f), p)
    }

    companion object {
        private const val TAU = (PI * 2).toFloat()
        private val NEST_COLOR = Color.rgb(225, 75, 225)
        private val CARGO_COLOR = Palette.contractCrate   // dourado: a carga da Caixa perdida
        private const val KIND_NEST = 0
        private const val KIND_CRYSTAL = 1
        private const val KIND_PORTAL = 2
        private const val KIND_CARGO = 3

        /** Regiões do centro superior (dp): barra superior, bússola e, abaixo, os pontos de extensão. */
        const val TOP_BAR_TOP_DP = 6f
        const val TOP_BAR_BOTTOM_DP = 36f
        const val COMPASS_TOP_DP = 40f
    }
}
