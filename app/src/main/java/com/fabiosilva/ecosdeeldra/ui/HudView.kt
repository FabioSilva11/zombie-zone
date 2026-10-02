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
 * - MIRAR (alterna), PULAR, RECARREGAR, PAUSA e MINIMAPA.
 */
@SuppressLint("ViewConstructor")
class HudView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun onPauseRequested()
        fun onMapRequested()
    }

    private enum class Role { NONE, JOYSTICK, LOOK, FIRE, AIM, JUMP, RELOAD, PAUSE, MAP }
    private class Touch(var role: Role, var x: Float, var y: Float, val startX: Float, val startY: Float)

    private val hud = FloatArray(Hud.SIZE)
    private val touches = HashMap<Int, Touch>()
    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.title; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()
    private var vignette: RadialGradient? = null
    private var vignettePaint = Paint()

    var minimap: Bitmap? = null
    var pvpNames: Map<Int, String> = emptyMap()
    var playerName = ""
    var showFps = false
    var leftHanded = false
    var vibration = true
    private var aiming = false
    private var stickBaseX = 0f
    private var stickBaseY = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var stickActive = false
    private val feed = ArrayList<Pair<String, Long>>()
    private var feedColor = Palette.lime
    private var lastHealth = -1f

    // Geometria dos controles (px), recalculada no onSizeChanged.
    private var fireX = 0f; private var fireY = 0f; private var fireR = 0f
    private var aimX = 0f; private var aimY = 0f
    private var jumpX = 0f; private var jumpY = 0f
    private var reloadX = 0f; private var reloadY = 0f
    private var smallR = 0f
    private var pauseX = 0f; private var pauseY = 0f
    private var mapX = 0f; private var mapY = 0f; private var mapSize = 0f
    private var stickRadius = 0f
    private var stickHomeX = 0f; private var stickHomeY = 0f

    private fun d(v: Float) = v * density

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutControls()
        vignette = RadialGradient(w / 2f, h / 2f, hypot(w / 2f, h / 2f), intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(220, 180, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        vignettePaint.shader = vignette
    }

    private fun layoutControls() {
        val w = width.toFloat()
        val h = height.toFloat()
        fun mx(x: Float) = if (leftHanded) w - x else x
        fireR = d(46f)
        smallR = d(30f)
        fireX = mx(w - d(104f)); fireY = h - d(96f)
        jumpX = mx(w - d(204f)); jumpY = h - d(52f)
        aimX = mx(w - d(190f)); aimY = h - d(146f)
        reloadX = mx(w - d(86f)); reloadY = h - d(200f)
        pauseX = mx(w - d(40f)); pauseY = d(36f)
        mapSize = d(112f)
        mapX = mx(d(28f) + mapSize / 2f); mapY = d(14f) + mapSize / 2f
        stickRadius = d(58f)
        stickHomeX = mx(d(132f)); stickHomeY = h - d(116f)
        if (!stickActive) { stickBaseX = stickHomeX; stickBaseY = stickHomeY; stickX = stickBaseX; stickY = stickBaseY }
    }

    fun applySettings(leftHanded: Boolean, vibration: Boolean, showFps: Boolean) {
        this.leftHanded = leftHanded
        this.vibration = vibration
        this.showFps = showFps
        if (width > 0) layoutControls()
    }

    fun reset() {
        touches.clear()
        aiming = false
        stickActive = false
        feed.clear()
        lastHealth = -1f
        NativeBridge.setMove(0f, 0f)
        NativeBridge.setFire(false)
        NativeBridge.setAim(false)
        if (width > 0) layoutControls()
    }

    fun message(text: String, color: Int = Palette.lime) {
        feedColor = color
        feed.add(text to System.currentTimeMillis())
        if (feed.size > 3) feed.removeAt(0)
    }

    fun haptic(strong: Boolean) {
        if (!vibration) return
        performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ------------------------------------------------------------------ toque

    private fun inside(x: Float, y: Float, cx: Float, cy: Float, r: Float) = hypot(x - cx, y - cy) <= r

    private fun roleAt(x: Float, y: Float): Role {
        val w = width.toFloat()
        val h = height.toFloat()
        if (inside(x, y, fireX, fireY, fireR * 1.15f)) return Role.FIRE
        if (inside(x, y, aimX, aimY, smallR * 1.25f)) return Role.AIM
        if (inside(x, y, jumpX, jumpY, smallR * 1.25f)) return Role.JUMP
        if (inside(x, y, reloadX, reloadY, smallR * 1.25f)) return Role.RELOAD
        if (inside(x, y, pauseX, pauseY, d(30f))) return Role.PAUSE
        if (abs(x - mapX) < mapSize / 2 && abs(y - mapY) < mapSize / 2) return Role.MAP
        val leftSide = if (leftHanded) x > w * 0.55f else x < w * 0.45f
        if (leftSide && y > h * 0.28f && touches.values.none { it.role == Role.JOYSTICK }) return Role.JOYSTICK
        return Role.LOOK
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
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
                        stickActive = true
                        stickBaseX = x; stickBaseY = y; stickX = x; stickY = y
                    }
                    Role.FIRE -> { NativeBridge.setFire(true); haptic(false) }
                    Role.AIM -> { aiming = !aiming; NativeBridge.setAim(aiming); haptic(false) }
                    Role.JUMP -> { NativeBridge.pressJump(); haptic(false) }
                    Role.RELOAD -> { NativeBridge.pressReload(); haptic(false) }
                    Role.PAUSE -> listener.onPauseRequested()
                    Role.MAP -> listener.onMapRequested()
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
                        Role.LOOK, Role.FIRE -> NativeBridge.addLook((x - t.x) / density, (y - t.y) / density)
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
                        Role.FIRE -> NativeBridge.setFire(false)
                        else -> Unit
                    }
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ desenho

    override fun onDraw(canvas: Canvas) {
        NativeBridge.copyHud(hud)
        val w = width.toFloat()
        val h = height.toFloat()
        val health = hud[Hud.HEALTH]
        val maxHealth = hud[Hud.MAX_HEALTH].coerceAtLeast(1f)
        if (lastHealth >= 0 && health < lastHealth - 0.5f) haptic(true)
        lastHealth = health

        drawVignette(canvas, w, h, health / maxHealth)
        drawDamageDirection(canvas, w, h)
        drawWorldLabels(canvas, w, h)
        drawPortalMarker(canvas, w, h)
        drawReticle(canvas, w, h)
        drawMinimap(canvas)
        drawStatus(canvas, health, maxHealth)
        if (hud[Hud.PVP] > 0.5f) {
            drawPvpNames(canvas, w, h)
            drawPvpTop(canvas, w)
            drawPvpScoreboard(canvas, w)
        } else {
            drawObjective(canvas, w)
        }
        drawFeed(canvas, w, h)
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
        // Barras de vida dos zumbis atingidos.
        val bars = hud[Hud.BAR_COUNT].toInt()
        for (i in 0 until bars) {
            val o = Hud.BARS + i * 4
            val x = hud[o] * w
            val y = hud[o + 1] * h
            val f = hud[o + 2]
            val a = hud[o + 3]
            val bw = d(38f)
            val bh = d(5f)
            p.color = Color.argb((a * 170).toInt(), 0, 0, 0)
            rect.set(x - bw / 2 - d(1f), y - d(1f), x + bw / 2 + d(1f), y + bh + d(1f))
            canvas.drawRoundRect(rect, d(2f), d(2f), p)
            p.color = if (f > 0.5f) Color.argb((a * 255).toInt(), 168, 255, 62) else Color.argb((a * 255).toInt(), 255, 90, 70)
            rect.set(x - bw / 2, y, x - bw / 2 + bw * f, y + bh)
            canvas.drawRoundRect(rect, d(2f), d(2f), p)
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
            val rx = w * 0.42f
            val ry = h * 0.38f
            x = w / 2 + sin(rel) * rx
            y = h / 2 - cos(rel) * ry
        }
        val s = d(11f) * pulse
        path.reset()
        path.moveTo(x, y - s); path.lineTo(x + s, y); path.lineTo(x, y + s); path.lineTo(x - s, y); path.close()
        p.color = Palette.purple
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = Color.WHITE
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        textPaint.textSize = d(12f)
        textPaint.color = Color.WHITE
        canvas.drawText("PORTAL ${dist}m", x, y + s + d(14f), textPaint)
    }

    private fun drawReticle(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2
        val cy = h / 2
        val fov = hud[Hud.FOV].coerceAtLeast(0.3f)
        val focal = (h / 2) / tan(fov / 2)
        val gap = (hud[Hud.SPREAD] * focal).coerceIn(d(5f), d(40f))
        val locked = hud[Hud.TARGET_LOCKED] > 0.5f
        val color = if (locked) Palette.red else Color.WHITE
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
        val scale = mapSize / 64f // 64 blocos visíveis
        canvas.save()
        path.reset()
        path.addRoundRect(rect, d(14f), d(14f), Path.Direction.CW)
        canvas.clipPath(path)
        canvas.translate(mapX, mapY)
        canvas.rotate(-yawDeg)
        canvas.scale(scale, scale)
        canvas.translate(-px, -pz)
        if (bmp != null) canvas.drawBitmap(bmp, 0f, 0f, null)
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
        if (hud[Hud.PORTAL_OPEN] > 0.5f) {
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
        val left = if (leftHanded) width - d(28f) - mapSize - d(176f) else mapX + mapSize / 2 + d(10f)
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
        val alive = hud[Hud.ALIVE].toInt()
        val total = hud[Hud.TOTAL].toInt()
        val kills = hud[Hud.KILLS].toInt()
        val text = if (portal) "PORTAL ABERTO! Siga o marcador ◆   ☠ $kills" else "☠ $kills ABATES   •   RESTAM $alive DE $total"
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
        // Joystick
        p.color = Color.argb(if (stickActive) 70 else 40, 255, 255, 255)
        canvas.drawCircle(stickBaseX, stickBaseY, stickRadius, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = Color.argb(if (stickActive) 160 else 90, 168, 255, 62)
        canvas.drawCircle(stickBaseX, stickBaseY, stickRadius, p)
        p.style = Paint.Style.FILL
        p.color = Color.argb(if (stickActive) 220 else 120, 168, 255, 62)
        canvas.drawCircle(stickX, stickY, d(24f), p)
        if (hud[Hud.SPRINTING] > 0.5f) {
            textPaint.textSize = d(13f)
            textPaint.color = Palette.lime
            canvas.drawText("» CORRENDO", stickBaseX, stickBaseY - stickRadius - d(10f), textPaint)
        } else if (!stickActive) {
            textPaint.textSize = d(10f)
            textPaint.color = Color.argb(140, 255, 255, 255)
            canvas.drawText("MOVER", stickBaseX, stickBaseY + stickRadius + d(14f), textPaint)
        }

        // Atirar (com anel de recarga)
        val firing = hud[Hud.FIRING] > 0.5f || touches.values.any { it.role == Role.FIRE }
        drawButton(canvas, fireX, fireY, fireR, Palette.lime, firing)
        drawCrosshairIcon(canvas, fireX, fireY, d(16f), if (firing) Palette.dark else Palette.lime)
        val reload = hud[Hud.RELOAD]
        if (reload >= 0f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(5f)
            p.color = Palette.gold
            rect.set(fireX - fireR - d(5f), fireY - fireR - d(5f), fireX + fireR + d(5f), fireY + fireR + d(5f))
            canvas.drawArc(rect, -90f, 360f * reload, false, p)
            p.style = Paint.Style.FILL
        }
        textPaint.textSize = d(10f)
        textPaint.color = if (firing) Palette.dark else Color.WHITE
        canvas.drawText("ATIRAR", fireX, fireY + d(30f), textPaint)

        // Mirar
        drawButton(canvas, aimX, aimY, smallR, Palette.cyan, aiming)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(2f)
        p.color = if (aiming) Palette.dark else Palette.cyan
        canvas.drawCircle(aimX, aimY - d(3f), d(9f), p)
        canvas.drawLine(aimX - d(13f), aimY - d(3f), aimX + d(13f), aimY - d(3f), p)
        canvas.drawLine(aimX, aimY - d(16f), aimX, aimY + d(10f), p)
        p.style = Paint.Style.FILL
        textPaint.textSize = d(8f)
        textPaint.color = if (aiming) Palette.dark else Color.WHITE
        canvas.drawText("MIRAR", aimX, aimY + d(19f), textPaint)

        // Pular
        val jumpReady = hud[Hud.JUMP_READY] > 0.5f || hud[Hud.IN_WATER] > 0.5f
        drawButton(canvas, jumpX, jumpY, smallR, Palette.lime, false)
        path.reset()
        path.moveTo(jumpX, jumpY - d(12f)); path.lineTo(jumpX + d(10f), jumpY); path.lineTo(jumpX + d(4f), jumpY)
        path.lineTo(jumpX + d(4f), jumpY + d(8f)); path.lineTo(jumpX - d(4f), jumpY + d(8f)); path.lineTo(jumpX - d(4f), jumpY); path.lineTo(jumpX - d(10f), jumpY); path.close()
        p.color = if (jumpReady) Palette.lime else Palette.muted
        canvas.drawPath(path, p)
        textPaint.color = Color.WHITE
        canvas.drawText("PULAR", jumpX, jumpY + d(19f), textPaint)

        // Recarregar + munição
        val ammo = hud[Hud.AMMO].toInt()
        val mag = hud[Hud.MAG].toInt()
        val lowAmmo = ammo <= mag / 4
        drawButton(canvas, reloadX, reloadY, smallR, if (lowAmmo) Palette.gold else Palette.lime, false)
        textPaint.textSize = d(13f)
        textPaint.color = if (lowAmmo) Palette.gold else Color.WHITE
        canvas.drawText(if (reload >= 0f) "..." else "$ammo", reloadX, reloadY + d(2f), textPaint)
        textPaint.textSize = d(8f)
        textPaint.color = Palette.muted
        canvas.drawText("RECARREGAR", reloadX, reloadY + d(16f), textPaint)

        // Pausa
        drawButton(canvas, pauseX, pauseY, d(20f), Palette.lime, false)
        p.color = Color.WHITE
        canvas.drawRect(pauseX - d(6f), pauseY - d(7f), pauseX - d(2f), pauseY + d(7f), p)
        canvas.drawRect(pauseX + d(2f), pauseY - d(7f), pauseX + d(6f), pauseY + d(7f), p)
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
        @Suppress("unused")
        private const val TAU = (PI * 2).toFloat()
    }
}
