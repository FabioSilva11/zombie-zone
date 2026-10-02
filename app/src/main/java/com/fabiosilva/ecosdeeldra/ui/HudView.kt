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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.fabiosilva.ecosdeeldra.data.ZombieKinds
import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.NativeBridge
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
 * - MIRAR, PULAR, RECARREGAR, AGACHAR, CORRER (trava), FACA, GRANADA, TROCAR OMBRO,
 *   PAUSA, MINIMAPA e SUPRIMENTOS (anúncio); empurrar o joystick bem para cima trava a corrida.
 * Todos os controles podem ser movidos/redimensionados no editor (modo [editing]).
 * Na ilha mostra ninhos, portal, efeitos (fogo, sangramento, veneno) e zumbis elite.
 */
@SuppressLint("ViewConstructor")
class HudView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun onPauseRequested()
        fun onMapRequested()
        fun onSupplyRequested()
    }

    private enum class Role { NONE, JOYSTICK, LOOK, FIRE, FIRE_LEFT, AIM, JUMP, RELOAD, CROUCH, SPRINT, MELEE, GRENADE, SHOULDER, PAUSE, MAP, SUPPLY }
    private class Touch(var role: Role, var x: Float, var y: Float, val startX: Float, val startY: Float)
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
    private var autoRun = false        // trava de corrida: o personagem segue correndo sozinho
    private var autoRunArmed = false
    private var stickBaseX = 0f
    private var stickBaseY = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var stickActive = false
    private val feed = ArrayList<Pair<String, Long>>()
    private var feedColor = Palette.lime
    private var lastHealth = -1f
    private var tip: Pair<String, Long>? = null
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

    private fun d(v: Float) = v * density

    init {
        isClickable = true
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
        if (!stickActive) {
            stickBaseX = stickHomeX; stickBaseY = stickHomeY; stickX = stickBaseX; stickY = stickBaseY
            if (autoRun) stickY = stickBaseY - stickRadius
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
        stickActive = false
        autoRun = false
        autoRunArmed = false
        feed.clear()
        tip = null
        lastHealth = -1f
        NativeBridge.setMove(0f, 0f)
        NativeBridge.setFire(false)
        NativeBridge.setAim(false)
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

    /** Mostra/esconde o controle selecionado (só os opcionais); retorna o novo estado. */
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

    fun message(text: String, color: Int = Palette.lime) {
        feedColor = color
        feed.add(text to System.currentTimeMillis())
        if (feed.size > 3) feed.removeAt(0)
    }

    /** Dica longa (ex.: habilidade de um zumbi novo) mostrada na parte de baixo por alguns segundos. */
    fun tip(text: String) {
        tip = text to System.currentTimeMillis()
    }

    fun haptic(strong: Boolean) {
        if (!vibration) return
        performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private val island get() = hud[Hud.ISLAND] > 0.5f
    private val supplyReady get() = island && hud[Hud.SUPPLY] >= 1f

    // ------------------------------------------------------------------ toque

    private fun inside(x: Float, y: Float, cx: Float, cy: Float, r: Float) = hypot(x - cx, y - cy) <= r

    /** O controle aparece nesta partida? (granada e faca só na ilha; suprimentos só quando há.) */
    private fun available(c: HudControl): Boolean {
        if (!g(c).visible) return false
        return when (c) {
            HudControl.GRENADE -> hud[Hud.GRENADES] >= 0f
            HudControl.MELEE -> hud[Hud.MELEE] >= 0f
            HudControl.SUPPLY -> island && hud[Hud.SUPPLY] >= 0f
            else -> true
        }
    }

    private val buttons = listOf(
        HudControl.FIRE to Role.FIRE, HudControl.FIRE_LEFT to Role.FIRE_LEFT, HudControl.AIM to Role.AIM,
        HudControl.JUMP to Role.JUMP, HudControl.RELOAD to Role.RELOAD, HudControl.CROUCH to Role.CROUCH,
        HudControl.SPRINT to Role.SPRINT, HudControl.MELEE to Role.MELEE, HudControl.GRENADE to Role.GRENADE,
        HudControl.SHOULDER to Role.SHOULDER, HudControl.PAUSE to Role.PAUSE,
    )

    private fun roleAt(x: Float, y: Float): Role {
        val w = width.toFloat()
        val h = height.toFloat()
        for ((c, role) in buttons) {
            if (!available(c)) continue
            val gc = g(c)
            if (inside(x, y, gc.x, gc.y, gc.r * 1.2f + d(4f))) return role
        }
        if (available(HudControl.MAP) && abs(x - mapX) < mapSize / 2 && abs(y - mapY) < mapSize / 2) return Role.MAP
        if (available(HudControl.SUPPLY) && abs(x - supplyX) < supplyW / 2 && abs(y - supplyY) < supplyH / 2 + d(4f)) return Role.SUPPLY
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
                touches[id] = Touch(role, x, y, x, y)
                when (role) {
                    Role.JOYSTICK -> {
                        autoRun = false
                        autoRunArmed = false
                        stickActive = true
                        stickBaseX = x; stickBaseY = y; stickX = x; stickY = y
                    }
                    Role.FIRE, Role.FIRE_LEFT -> { NativeBridge.setFire(true); haptic(false) }
                    Role.AIM -> { aiming = !aiming; NativeBridge.setAim(aiming); haptic(false) }
                    Role.JUMP -> { NativeBridge.pressJump(); haptic(false) }
                    Role.RELOAD -> { NativeBridge.pressReload(); haptic(false) }
                    Role.CROUCH -> { NativeBridge.toggleCrouch(); haptic(false) }
                    Role.SPRINT -> { NativeBridge.toggleSprint(); haptic(false) }
                    Role.MELEE -> { NativeBridge.pressMelee(); haptic(false) }
                    Role.GRENADE -> { NativeBridge.pressGrenade(); haptic(false) }
                    Role.SHOULDER -> { NativeBridge.swapShoulder(); haptic(false) }
                    Role.PAUSE -> listener.onPauseRequested()
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
                            // Empurrar bem para cima arma a trava de corrida (solte para travar).
                            autoRunArmed = dy < -stickRadius * 1.3f && abs(dx) < stickRadius * 0.7f
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
                            if (autoRunArmed && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                                autoRun = true
                                stickY = stickBaseY - stickRadius
                                NativeBridge.setMove(0f, 1f)
                                haptic(true)
                            } else {
                                NativeBridge.setMove(0f, 0f)
                            }
                            autoRunArmed = false
                        }
                        Role.FIRE, Role.FIRE_LEFT -> if (touches.values.none { it.role == Role.FIRE || it.role == Role.FIRE_LEFT }) NativeBridge.setFire(false)
                        else -> Unit
                    }
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ desenho

    override fun onDraw(canvas: Canvas) {
        if (editing) {
            drawEditor(canvas)
            return
        }
        NativeBridge.copyHud(hud)
        val w = width.toFloat()
        val h = height.toFloat()
        val health = hud[Hud.HEALTH]
        val maxHealth = hud[Hud.MAX_HEALTH].coerceAtLeast(1f)
        if (lastHealth >= 0 && health < lastHealth - 2f) haptic(true)
        lastHealth = health

        drawStatusTint(canvas, w, h)
        drawVignette(canvas, w, h, health / maxHealth)
        drawDamageDirection(canvas, w, h)
        drawWorldLabels(canvas, w, h)
        if (island) drawObjectives(canvas, w, h) else drawPortalMarker(canvas, w, h)
        drawReticle(canvas, w, h)
        drawMinimap(canvas)
        drawStatus(canvas, health, maxHealth)
        if (hud[Hud.PVP] > 0.5f) {
            drawPvpNames(canvas, w, h)
            drawPvpTop(canvas, w)
            drawPvpScoreboard(canvas, w)
        } else {
            drawObjective(canvas, w)
            if (island) {
                drawNestBar(canvas, w)
                drawSupplyButton(canvas)
            }
        }
        drawFeed(canvas, w, h)
        drawTip(canvas, w, h)
        if (hud[Hud.PVP] > 0.5f) drawPvpRespawn(canvas, w, h)
        drawControls(canvas)
        if (showFps) {
            textPaint.textSize = d(11f)
            textPaint.color = Palette.muted
            canvas.drawText("${hud[Hud.FPS].toInt()} FPS", w / 2f, h - d(8f), textPaint)
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

    /**
     * Ninhos e portal: marcador 3D só na área central (longe dos botões) e uma bússola no topo
     * com a direção de todos os objetivos — nada de setas na borda por cima dos controles.
     */
    private fun drawObjectives(canvas: Canvas, w: Float, h: Float) {
        val n = hud[Hud.OBJ_COUNT].toInt()
        val px = hud[Hud.PLAYER_X]
        val pz = hud[Hud.PLAYER_Z]
        var target = -1
        var targetDist = Float.MAX_VALUE
        val portalOpen = hud[Hud.PORTAL_OPEN] > 0.5f
        for (i in 0 until n) {
            val o = Hud.OBJ + i * Hud.OBJ_STRIDE
            val state = hud[o + 2].toInt()
            val dist = hypot(hud[o] - px, hud[o + 1] - pz)
            val wanted = (state == 1 && !portalOpen) || state == 4
            if (wanted && dist < targetDist) { targetDist = dist; target = i }
            if (state == 2 || state == 3) continue
            val sx = hud[o + 3]
            val sy = hud[o + 4]
            if (hud[o + 5] > 0.5f && sx in 0.18f..0.82f && sy in 0.16f..0.66f) {
                val pulse = 1f + 0.12f * sin(System.currentTimeMillis() / 200.0).toFloat()
                val color = if (state == 4) Palette.purple else Color.rgb(200, 60, 200)
                drawMarker(canvas, sx * w, sy * h, color, (if (state == 4) "PORTAL " else "NINHO ") + formatDist(dist), pulse, state == 1)
            }
        }
        drawCompass(canvas, w, n, px, pz, target, targetDist)
    }

    private fun wrapAngle(a: Float): Float {
        var x = a
        while (x > PI) x -= TAU
        while (x < -PI) x += TAU
        return x
    }

    private fun drawCompass(canvas: Canvas, w: Float, n: Int, px: Float, pz: Float, target: Int, targetDist: Float) {
        val yaw = hud[Hud.CAM_YAW]
        val cw = d(320f)
        val ch = d(20f)
        val top = d(50f)
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
        for (i in 0 until n) {
            val o = Hud.OBJ + i * Hud.OBJ_STRIDE
            val state = hud[o + 2].toInt()
            if (state == 2) continue
            val rel = wrapAngle(atan2(hud[o] - px, -(hud[o + 1] - pz)) - yaw)
            val color = when (state) { 4 -> Palette.purple; 3 -> Color.argb(150, 150, 120, 210); else -> Color.rgb(225, 75, 225) }
            p.color = color
            val s = d(if (i == target) 7f else 5f)
            val cy = top + ch / 2
            if (abs(rel) <= half) {
                val x = xFor(rel)
                path.reset()
                path.moveTo(x, cy - s); path.lineTo(x + s, cy); path.lineTo(x, cy + s); path.lineTo(x - s, cy); path.close()
                canvas.drawPath(path, p)
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
        canvas.restore()
        p.color = Color.WHITE
        canvas.drawRect(w / 2 - d(1f), top - d(2f), w / 2 + d(1f), top + ch + d(2f), p)
        if (target >= 0) {
            val state = hud[Hud.OBJ + target * Hud.OBJ_STRIDE + 2].toInt()
            textPaint.textSize = d(11f)
            textPaint.color = Color.argb(160, 0, 0, 0)
            val label = (if (state == 4) "PORTAL " else "PRÓXIMO NINHO ") + formatDist(targetDist)
            canvas.drawText(label, w / 2 + d(1f), top + ch + d(14f), textPaint)
            textPaint.color = if (state == 4) Color.rgb(200, 160, 255) else Color.rgb(240, 150, 240)
            canvas.drawText(label, w / 2, top + ch + d(13f), textPaint)
        }
    }

    private fun formatDist(m: Float): String = if (m >= 1000f) "%.1f km".format(m / 1000f) else "${m.toInt()} m"

    private fun drawNestBar(canvas: Canvas, w: Float) {
        val hp = hud[Hud.NEST_HP]
        if (hp < 0f) return
        val bw = d(260f)
        val top = d(90f)
        rect.set(w / 2 - bw / 2 - d(6f), top, w / 2 + bw / 2 + d(6f), top + d(30f))
        p.color = Color.argb(210, 40, 8, 48)
        canvas.drawRoundRect(rect, d(10f), d(10f), p)
        textPaint.textSize = d(10f)
        textPaint.color = Color.rgb(230, 170, 255)
        canvas.drawText("NINHO DE INFECÇÃO — ATIRE NO NÚCLEO", w / 2, top + d(12f), textPaint)
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

    private fun drawPortalMarker(canvas: Canvas, w: Float, h: Float) {
        if (hud[Hud.PORTAL_OPEN] < 0.5f) return
        val dx = hud[Hud.PORTAL_X] - hud[Hud.PLAYER_X]
        val dz = hud[Hud.PORTAL_Z] - hud[Hud.PLAYER_Z]
        val dist = hypot(dx, dz).toInt()
        val pulse = 1f + 0.15f * sin(System.currentTimeMillis() / 200.0).toFloat()
        val x: Float
        val y: Float
        if (hud[Hud.PORTAL_ONSCREEN] > 0.5f) {
            x = hud[Hud.PORTAL_SX] * w
            y = hud[Hud.PORTAL_SY] * h
        } else {
            // Seta na borda apontando para o portal (ângulo relativo à câmera).
            val rel = atan2(dx, -dz) - hud[Hud.CAM_YAW]
            x = w / 2 + sin(rel) * w * 0.42f
            y = h / 2 - cos(rel) * h * 0.38f
        }
        drawMarker(canvas, x, y, Palette.purple, "PORTAL ${dist}m", pulse, false)
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

    private fun drawStatus(canvas: Canvas, health: Float, maxHealth: Float) {
        val left = if (leftHanded) width - d(28f) - d(112f) - d(176f) else d(150f)
        val top = d(16f)
        rect.set(left, top, left + d(168f), top + d(50f))
        p.color = Color.argb(200, 8, 14, 26)
        canvas.drawRoundRect(rect, d(12f), d(12f), p)
        // coração em pixel
        drawHeart(canvas, left + d(18f), top + d(18f), d(2.2f))
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = d(13f)
        textPaint.color = Color.WHITE
        canvas.drawText(playerName, left + d(34f), top + d(18f), textPaint)
        val bw = d(124f)
        val ratio = (health / maxHealth).coerceIn(0f, 1f)
        rect.set(left + d(34f), top + d(25f), left + d(34f) + bw, top + d(33f))
        p.color = Color.argb(160, 0, 0, 0)
        canvas.drawRoundRect(rect, d(4f), d(4f), p)
        rect.right = rect.left + bw * ratio
        p.color = if (ratio > 0.5f) Palette.lime else if (ratio > 0.25f) Palette.gold else Palette.red
        canvas.drawRoundRect(rect, d(4f), d(4f), p)
        textPaint.textSize = d(10f)
        textPaint.color = Palette.muted
        canvas.drawText("${health.toInt()}/${maxHealth.toInt()}", left + d(34f), top + d(45f), textPaint)
        if (hud[Hud.CRYSTAL_TOTAL] > 0f) {
            textPaint.color = Palette.cyan
            val crystals = "◆ ${hud[Hud.CRYSTALS].toInt()}/${hud[Hud.CRYSTAL_TOTAL].toInt()}"
            canvas.drawText(crystals, left + d(110f), top + d(45f), textPaint)
        }
        // Efeitos ativos (ícones com tempo).
        if (island) {
            var x = left
            val y = top + d(66f)
            fun chip(label: String, seconds: Float, color: Int) {
                if (seconds <= 0f) return
                val txt = "$label ${seconds.toInt()}s"
                textPaint.textSize = d(10f)
                val tw = textPaint.measureText(txt) + d(14f)
                rect.set(x, y - d(13f), x + tw, y + d(5f))
                p.color = Color.argb(200, Color.red(color) / 4, Color.green(color) / 4, Color.blue(color) / 4)
                canvas.drawRoundRect(rect, d(8f), d(8f), p)
                textPaint.color = color
                canvas.drawText(txt, x + d(7f), y, textPaint)
                x += tw + d(5f)
            }
            chip("🔥 QUEIMANDO", hud[Hud.BURNING], Color.rgb(255, 150, 60))
            chip("🩸 SANGRANDO", hud[Hud.BLEEDING], Color.rgb(255, 90, 90))
            chip("☠ VENENO", hud[Hud.POISON], Color.rgb(140, 255, 90))
            chip("💥 MUNIÇÃO INCENDIÁRIA", hud[Hud.BOOST], Color.rgb(255, 180, 70))
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, px: Float) {
        val rows = arrayOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
        p.color = Palette.red
        for ((r, row) in rows.withIndex()) for ((c, ch) in row.withIndex()) {
            if (ch == 'X') canvas.drawRect(cx + (c - 3.5f) * px, cy + (r - 3f) * px, cx + (c - 2.5f) * px, cy + (r - 2f) * px, p)
        }
    }

    private fun drawObjective(canvas: Canvas, w: Float) {
        val portal = hud[Hud.PORTAL_OPEN] > 0.5f
        val kills = hud[Hud.KILLS].toInt()
        val text = if (island) {
            val done = hud[Hud.NESTS_DONE].toInt()
            val total = hud[Hud.NEST_TOTAL].toInt()
            val crystals = hud[Hud.CRYSTALS].toInt()
            if (portal) "PORTAL ABERTO! Siga o marcador ◆   ☠ $kills"
            else "NINHOS $done/$total   •   ◆ $crystals/$total   •   ☠ $kills"
        } else {
            val alive = hud[Hud.ALIVE].toInt()
            val total = hud[Hud.TOTAL].toInt()
            if (portal) "PORTAL ABERTO! Siga o marcador ◆   ☠ $kills" else "☠ $kills ABATES   •   RESTAM $alive DE $total"
        }
        val elapsed = hud[Hud.ELAPSED].toInt()
        val time = "%02d:%02d".format(elapsed / 60, elapsed % 60)
        textPaint.textSize = d(14f)
        val tw = textPaint.measureText(text) + d(70f)
        rect.set(w / 2 - tw / 2, d(12f), w / 2 + tw / 2, d(44f))
        p.color = if (portal) Color.argb(220, 60, 24, 110) else Color.argb(200, 8, 14, 26)
        canvas.drawRoundRect(rect, d(16f), d(16f), p)
        textPaint.color = if (portal) Color.WHITE else Palette.lime
        canvas.drawText(text, w / 2 - d(22f), d(33f), textPaint)
        textPaint.textSize = d(12f)
        textPaint.color = Palette.muted
        canvas.drawText(time, rect.right - d(26f), d(33f), textPaint)
        if (island && hud[Hud.NEST_HP] < 0f) {
            textPaint.textSize = d(10f)
            textPaint.color = Color.argb(200, 255, 220, 120)
            val chests = hud[Hud.CHESTS].toInt()
            val gems = hud[Hud.CHEST_DIAMONDS].toInt()
            canvas.drawText("BAÚS $chests (◆$gems)   •   ${formatDist(hud[Hud.DISTANCE])} percorridos", w / 2, d(100f), textPaint)
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
        val right = if (leftHanded) d(16f) + d(150f) else w - d(70f)
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

    private fun drawFeed(canvas: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        feed.removeAll { now - it.second > 1800 }
        var y = h * 0.27f
        for ((text, at) in feed) {
            val age = (now - at) / 1800f
            val a = if (age < 0.8f) 1f else (1f - age) / 0.2f
            val scale = if (age < 0.08f) 1f + (0.08f - age) * 4f else 1f
            textPaint.textSize = d(20f) * scale
            textPaint.color = Color.argb((a * 140).toInt(), 0, 0, 0)
            canvas.drawText(text, w / 2 + d(2f), y + d(2f), textPaint)
            textPaint.color = Color.argb((a * 255).toInt(), Color.red(feedColor), Color.green(feedColor), Color.blue(feedColor))
            canvas.drawText(text, w / 2, y, textPaint)
            y += d(26f)
        }
        val combo = hud[Hud.COMBO].toInt()
        if (combo >= 2) {
            textPaint.textSize = d(15f)
            textPaint.color = Palette.gold
            canvas.drawText("COMBO x$combo", w / 2, h * 0.27f - d(26f), textPaint)
        }
    }

    private fun drawTip(canvas: Canvas, w: Float, h: Float) {
        val (text, at) = tip ?: return
        val age = System.currentTimeMillis() - at
        if (age > 5000) {
            tip = null
            return
        }
        val a = if (age < 4300) 1f else (5000 - age) / 700f
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
        p.style = Paint.Style.FILL
    }

    private fun drawControls(canvas: Canvas) {
        val alpha = opacity
        val save = if (alpha < 0.99f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt()) else -1
        if (editing) for (c in HudControl.entries) if (c.optional && !g(c).visible) drawGhost(canvas, c)
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
        if (available(HudControl.SHOULDER)) drawShoulder(canvas)
        if (available(HudControl.PAUSE)) drawPause(canvas)
        if (save >= 0) canvas.restoreToCount(save)
    }

    /** Escala do controle (para textos e ícones acompanharem o tamanho escolhido). */
    private fun k(c: HudControl): Float = g(c).r / d(c.baseDp)

    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        textPaint.textSize = d(size)
        textPaint.color = color
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawJoystick(canvas: Canvas) {
        val r = stickRadius
        val lit = stickActive || autoRun
        p.color = Color.argb(if (lit) 70 else 40, 255, 255, 255)
        canvas.drawCircle(stickBaseX, stickBaseY, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = if (autoRun) Palette.gold else Color.argb(if (lit) 160 else 90, 168, 255, 62)
        canvas.drawCircle(stickBaseX, stickBaseY, r, p)
        p.style = Paint.Style.FILL
        p.color = if (autoRun) Palette.gold else Color.argb(if (lit) 220 else 120, 168, 255, 62)
        canvas.drawCircle(stickX, stickY, r * 0.41f, p)
        val ks = k(HudControl.JOYSTICK)
        when {
            autoRunArmed || autoRun -> {
                val text = if (autoRun) "🔒 CORRIDA TRAVADA" else "🔒 SOLTE PARA TRAVAR"
                textPaint.textSize = d(12f) * ks
                val tw = textPaint.measureText(text) + d(16f)
                val ty = stickBaseY - r - d(18f)
                rect.set(stickBaseX - tw / 2, ty - d(14f), stickBaseX + tw / 2, ty + d(6f))
                p.color = Color.argb(200, 40, 30, 8)
                canvas.drawRoundRect(rect, d(9f), d(9f), p)
                label(canvas, text, stickBaseX, ty, 12f * ks, Palette.gold)
            }
            hud[Hud.SPRINTING] > 0.5f -> label(canvas, "» CORRENDO", stickBaseX, stickBaseY - r - d(10f), 13f * ks, Palette.lime)
            stickActive && stickY < stickBaseY - r * 0.9f -> label(canvas, "⇡ suba mais para travar", stickBaseX, stickBaseY - r - d(10f), 10f * ks, Color.argb(200, 255, 230, 140))
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
        label(canvas, "ATIRAR", gc.x, gc.y + gc.r * 0.65f, 10f * ks * (if (main) 1f else 0.85f), if (firing) Palette.dark else Color.WHITE)
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
        label(canvas, "MIRAR", gc.x, gc.y + d(19f) * s, 8f * s, if (aiming) Palette.dark else Color.WHITE)
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
        label(canvas, "PULAR", x, y + d(19f) * s, 8f * s, Color.WHITE)
    }

    private fun drawReload(canvas: Canvas) {
        val gc = g(HudControl.RELOAD)
        val s = k(HudControl.RELOAD)
        val ammo = hud[Hud.AMMO].toInt()
        val mag = hud[Hud.MAG].toInt()
        val lowAmmo = ammo <= mag / 4
        drawButton(canvas, gc.x, gc.y, gc.r, if (lowAmmo) Palette.gold else Palette.lime, false)
        label(canvas, if (hud[Hud.RELOAD] >= 0f) "..." else "$ammo", gc.x, gc.y + d(2f) * s, 13f * s, if (lowAmmo) Palette.gold else Color.WHITE)
        label(canvas, "RECARREGAR", gc.x, gc.y + d(16f) * s, 8f * s, Palette.muted)
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
        label(canvas, "AGACHAR", gc.x, gc.y + d(18f) * s, 7.5f * s, if (on) Palette.dark else Color.WHITE)
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
        label(canvas, "CORRER", gc.x, gc.y + d(18f) * s, 7.5f * s, if (on) Palette.dark else Color.WHITE)
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
        label(canvas, "FACA", gc.x, gc.y + d(18f) * s, 7.5f * s, Color.WHITE)
    }

    private fun drawGrenade(canvas: Canvas) {
        val gc = g(HudControl.GRENADE)
        val s = k(HudControl.GRENADE)
        val count = hud[Hud.GRENADES].toInt()
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.lime, false)
        val color = if (count > 0) Color.rgb(150, 190, 90) else Palette.muted
        p.color = color
        canvas.drawCircle(gc.x, gc.y - d(1f) * s, d(7f) * s, p)
        canvas.drawRect(gc.x - d(2.5f) * s, gc.y - d(11f) * s, gc.x + d(2.5f) * s, gc.y - d(7f) * s, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.8f) * s
        canvas.drawCircle(gc.x + d(5f) * s, gc.y - d(11f) * s, d(2.5f) * s, p)
        p.style = Paint.Style.FILL
        // Contador.
        val bx = gc.x + gc.r * 0.72f
        val by = gc.y - gc.r * 0.72f
        p.color = if (count > 0) Palette.gold else Palette.stone
        canvas.drawCircle(bx, by, d(8f) * s, p)
        label(canvas, "$count", bx, by + d(3.5f) * s, 10f * s, Palette.dark)
        label(canvas, "GRANADA", gc.x, gc.y + d(18f) * s, 7.5f * s, Color.WHITE)
    }

    private fun drawShoulder(canvas: Canvas) {
        val gc = g(HudControl.SHOULDER)
        val s = k(HudControl.SHOULDER)
        drawButton(canvas, gc.x, gc.y, gc.r, Palette.cyan, false)
        label(canvas, if (hud[Hud.SHOULDER] < 0f) "◧" else "◨", gc.x, gc.y + d(5f) * s, 14f * s, Palette.cyan)
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
        hud[Hud.SHOULDER] = 1f
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = Color.argb(24, 255, 255, 255)
        val step = d(32f)
        var x = 0f
        while (x < w) { canvas.drawRect(x, 0f, x + 1f, h, p); x += step }
        var y = 0f
        while (y < h) { canvas.drawRect(0f, y, w, y + 1f, p); y += step }
        rect.set(mapX - mapSize / 2, mapY - mapSize / 2, mapX + mapSize / 2, mapY + mapSize / 2)
        p.color = Color.argb(200, 8, 14, 26)
        canvas.drawRoundRect(rect, d(14f), d(14f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = Palette.line
        canvas.drawRoundRect(rect, d(14f), d(14f), p)
        p.style = Paint.Style.FILL
        label(canvas, "MINIMAPA", mapX, mapY + d(4f), 10f * k(HudControl.MAP), Palette.muted)
        drawSupplyButton(canvas)
        drawControls(canvas)
        val c = selected ?: return
        val gc = g(c)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2.5f)
        p.color = Palette.gold
        when (c) {
            HudControl.MAP -> { rect.set(gc.x - gc.r - d(5f), gc.y - gc.r - d(5f), gc.x + gc.r + d(5f), gc.y + gc.r + d(5f)); canvas.drawRoundRect(rect, d(16f), d(16f), p) }
            HudControl.SUPPLY -> { rect.set(supplyX - supplyW / 2 - d(5f), supplyY - supplyH / 2 - d(5f), supplyX + supplyW / 2 + d(5f), supplyY + supplyH / 2 + d(5f)); canvas.drawRoundRect(rect, d(12f), d(12f), p) }
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
    }
}
