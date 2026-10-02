package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.OverScroller
import android.widget.ScrollView
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.Skins
import com.fabiosilva.ecosdeeldra.data.ZombieKinds
import kotlin.math.abs
import kotlin.math.sin

/** Escolha de fase: mapa-múndi rolável com as zonas infinitas + painel com detalhes e bônus. */
@SuppressLint("ViewConstructor")
class StagesScreen(private val app: MainActivity) : FrameLayout(app) {
    private var selected = app.progress.highestUnlockedStage()
    private val map = StageMapView(app) { select(it) }
    private val zoneLabel = Ui.text(app, "", 11f, Palette.lime, Fonts.title)
    private val title = Ui.title(app, "", 20f)
    private val starsLine = Ui.text(app, "", 13f, Palette.gold, Fonts.title)
    private val facts = Ui.text(app, "", 11f, Palette.text, Fonts.bold)
    private val enemies = Ui.text(app, "", 11f, Palette.text)
    private val goals = Ui.text(app, "", 11f, Palette.muted)
    private val reward = Ui.text(app, "", 12f, Palette.cyan, Fonts.title)
    private val boostButton: TextView
    private val playButton: TextView
    private val skipButton: TextView

    init {
        setBackgroundColor(Palette.bg)
        Ui.blockTouches(this)
        addView(map, Ui.frame(Ui.MATCH, Ui.MATCH))
        val column = Ui.column(app)
        column.addView(Header.build(app, "MAPA DAS ZONAS", "FASES INFINITAS • ILHAS DE 5 KM") { app.showHome() })
        val body = Ui.row(app, Gravity.TOP)
        body.addView(View(app), Ui.lp(0, Ui.MATCH, 1f))

        val card = Ui.panel(app, Color.parseColor("#EE0E1628"))
        val inner = Ui.column(app)
        inner.addView(zoneLabel)
        inner.addView(title.apply { setPadding(0, app.dpi(2), 0, app.dpi(2)) })
        inner.addView(starsLine)
        inner.addView(facts.apply {
            setPadding(app.dpi(8), app.dpi(5), app.dpi(8), app.dpi(5))
            background = PanelDrawable(app, Color.argb(120, 0, 0, 0), Color.argb(40, 255, 255, 255), 8f)
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(6) })
        inner.addView(Ui.text(app, "INIMIGOS NA ILHA", 9f, Palette.muted, Fonts.title).apply { setPadding(0, app.dpi(8), 0, app.dpi(2)) })
        inner.addView(enemies.apply { setLineSpacing(0f, 1.15f) })
        inner.addView(goals.apply { setPadding(0, app.dpi(6), 0, 0); setLineSpacing(0f, 1.15f) })
        inner.addView(reward.apply { setPadding(0, app.dpi(6), 0, 0) })
        val scroll = ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(inner) }
        card.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))
        boostButton = Ui.button(app, "", Palette.cyan, Palette.dark, 12f) { onBoost() }
        card.addView(boostButton, Ui.lp(Ui.MATCH, app.dpi(42)).apply { topMargin = app.dpi(6) })
        skipButton = Ui.button(app, "▶ PULAR FASE (ANÚNCIO)", Palette.gold, Palette.dark, 12f) { onSkip() }
        card.addView(skipButton, Ui.lp(Ui.MATCH, app.dpi(40)).apply { topMargin = app.dpi(6) })
        playButton = Ui.button(app, "JOGAR ▶", Palette.lime, Palette.dark, 18f) { app.startStage(selected) }
        card.addView(playButton, Ui.lp(Ui.MATCH, app.dpi(52)).apply { topMargin = app.dpi(6) })
        body.addView(card, Ui.lp(app.dpi(300), Ui.MATCH))
        column.addView(body, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(20), app.dpi(8), app.dpi(20), app.dpi(12)) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        Ui.pulse(playButton)
        select(selected)
        map.post { map.centerOn(selected, animate = false) }
    }

    private fun select(number: Int) {
        selected = number
        map.selected = number
        map.invalidate()
        val p = app.progress
        val stage = Campaign.stage(number)
        val record = p.stages[number]
        zoneLabel.text = "ZONA ${stage.zone.index + 1} • ${stage.zone.name.uppercase()}"
        zoneLabel.setTextColor(stage.zone.color)
        title.text = "Fase ${stage.number} · ${stage.title}"
        val stars = record?.stars ?: 0
        val best = record?.bestTime?.takeIf { it > 0 }?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "—"
        starsLine.text = "★".repeat(stars) + "☆".repeat(3 - stars) + "   Melhor tempo: $best" + if (record?.skipped == true) "   (pulada)" else ""
        val diff = Campaign.difficulty(number, p.vitality, p.power, p.reload)
        facts.text = "ILHA ${stage.islandKm}  •  3 NINHOS  •  PORTAL\nHorda: ${diff.zombieCount} zumbis por perto  •  Vida deles: ${diff.zombieHealth.toInt()}"
        val sb = SpannableStringBuilder()
        for (z in ZombieKinds.available(number)) {
            val start = sb.length
            sb.append("■ ")
            sb.setSpan(ForegroundColorSpan(z.color), start, sb.length, 0)
            sb.append(z.name)
            if (z.minStage == number) {
                val s = sb.length
                sb.append(" NOVO")
                sb.setSpan(ForegroundColorSpan(Palette.gold), s, sb.length, 0)
            }
            sb.append("   ")
        }
        enemies.text = sb
        val target = "%d:%02d".format(stage.targetSeconds / 60, stage.targetSeconds % 60)
        goals.text = "Objetivo: destrua os 3 ninhos, pegue os cristais e entre no portal.\n★ vencer   ★ vida ≥ 50%   ★ tempo ≤ $target"
        reward.text = if (record?.completed == true && record.skipped != true) "Já vencida • baús e abates ainda dão ◆" else "1ª vitória: ◆ ${stage.firstClearDiamonds} + baús escondidos"
        refreshButtons()
    }

    private fun refreshButtons() {
        val boosted = app.boostReady
        boostButton.text = if (boosted) "BÔNUS DE COMBATE ATIVO ✓" else "▶ BÔNUS: +30% VIDA E +20% DANO"
        Ui.setEnabledLook(boostButton, !boosted)
        (boostButton.background as BlockDrawable).apply { fill = if (boosted) Palette.limeDark else Palette.cyan; invalidateSelf() }
        skipButton.visibility = if (Rules.canSkip(app.progress, selected)) View.VISIBLE else View.GONE
    }

    private fun onBoost() {
        if (app.boostReady) return
        app.watchAd {
            app.boostReady = true
            app.audio.playUi("reward", 1f)
            refreshButtons()
        }
    }

    private fun onSkip() {
        val stage = selected
        Popup.show(app, "Pular a fase $stage?", "Assista a um anúncio para liberar a próxima fase. Você não ganha estrelas nem o prêmio da 1ª vitória desta.", "ASSISTIR", "CANCELAR") { ok ->
            if (!ok) return@show
            app.watchAd {
                if (Rules.skipStage(app.progress, stage)) {
                    app.save()
                    app.audio.playUi("unlock", 1f)
                    app.showStages()
                }
            }
        }
    }
}

/** Mapa-múndi rolável: trilha sinuosa com os nós das fases sobre faixas de bioma em pixel art. */
@SuppressLint("ViewConstructor")
class StageMapView(private val app: MainActivity, private val onSelect: (Int) -> Unit) : View(app) {
    var selected = 1
    private val unlocked = app.progress.highestUnlockedStage()
    private val count = unlocked + 10
    private val density = resources.displayMetrics.density
    private val spacing = 96f * density
    private val margin = 90f * density
    private var offsetX = 0f
    private val scroller = OverScroller(app)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.title; textAlign = Paint.Align.CENTER }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val rect = RectF()
    private val skyShaders = HashMap<Int, LinearGradient>()
    private val hero: Bitmap? = runCatching {
        val skin = Skins.get(app.progress.selectedSkin)
        BitmapFactory.decodeResource(resources, resources.getIdentifier("skin_${skin.letter}", "drawable", app.packageName))
    }.getOrNull()

    private val skyTop = intArrayOf(0xFF3A6FC8.toInt(), 0xFF5A62B4.toInt(), 0xFF6F86AE.toInt(), 0xFF1C1430.toInt())
    private val skyBottom = intArrayOf(0xFFB8DEFF.toInt(), 0xFFFFB27A.toInt(), 0xFFD8E6F2.toInt(), 0xFF6A2430.toInt())
    private val ground = intArrayOf(0xFF4E9A32.toInt(), 0xFFE0C47E.toInt(), 0xFFF0F6FA.toInt(), 0xFF4A3E36.toInt())
    private val groundDark = intArrayOf(0xFF3B7426.toInt(), 0xFFC4A35E.toInt(), 0xFFB9C9D6.toInt(), 0xFF2E2622.toInt())

    private val gestures = GestureDetector(app, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            offsetX = (offsetX + dx).coerceIn(0f, maxOffset())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(offsetX.toInt(), 0, -vx.toInt(), 0, 0, maxOffset().toInt(), 0, 0)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            for (n in 1..count) {
                if (abs(e.x - nodeX(n)) < 36 * density && abs(e.y - nodeY(n)) < 40 * density) {
                    if (n <= unlocked) {
                        app.audio.playUi("click", 0.6f)
                        onSelect(n)
                    } else {
                        app.audio.playUi("click", 0.25f)
                    }
                    return true
                }
            }
            return false
        }
    })

    private fun maxOffset(): Float = (margin * 2 + (count - 1) * spacing + 140 * density - width).coerceAtLeast(0f)
    private fun nodeX(n: Int): Float = margin + (n - 1) * spacing - offsetX
    private fun nodeY(n: Int): Float = height * 0.56f + sin(n * 0.85f) * height * 0.17f

    fun centerOn(n: Int, animate: Boolean) {
        val target = (margin + (n - 1) * spacing - width * 0.32f).coerceIn(0f, maxOffset())
        if (animate) {
            scroller.startScroll(offsetX.toInt(), 0, (target - offsetX).toInt(), 0, 500)
            postInvalidateOnAnimation()
        } else {
            offsetX = target
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offsetX = scroller.currX.toFloat().coerceIn(0f, maxOffset())
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = System.currentTimeMillis() / 1000f
        val firstZone = ((offsetX - margin) / spacing / Campaign.STAGES_PER_ZONE).toInt().coerceAtLeast(0)
        val lastZone = ((offsetX + w) / spacing / Campaign.STAGES_PER_ZONE).toInt() + 1
        for (z in firstZone..lastZone) drawZone(canvas, z, h, t)

        // Trilha entre as fases.
        val first = ((offsetX - margin) / spacing).toInt().coerceAtLeast(1)
        val last = (((offsetX + w) / spacing).toInt() + 2).coerceAtMost(count)
        fun trail(from: Int, to: Int): Boolean {
            path.reset()
            if (to <= from) return false
            path.moveTo(nodeX(from), nodeY(from))
            for (n in from + 1..to) {
                val px = nodeX(n - 1)
                val py = nodeY(n - 1)
                val x = nodeX(n)
                val y = nodeY(n)
                path.cubicTo((px + x) / 2, py, (px + x) / 2, y, x, y)
            }
            return true
        }
        pathPaint.pathEffect = null
        if (trail(first, last)) {
            pathPaint.color = Color.argb(150, 0, 0, 0)
            pathPaint.strokeWidth = 12 * density
            canvas.drawPath(path, pathPaint)
        }
        if (trail(first, minOf(last, unlocked))) {
            pathPaint.color = Palette.gold
            pathPaint.strokeWidth = 6 * density
            canvas.drawPath(path, pathPaint)
        }
        if (trail(maxOf(first, unlocked), last)) {
            pathPaint.color = Color.argb(200, 230, 236, 245)
            pathPaint.strokeWidth = 5 * density
            pathPaint.pathEffect = DashPathEffect(floatArrayOf(10 * density, 9 * density), 0f)
            canvas.drawPath(path, pathPaint)
            pathPaint.pathEffect = null
        }

        for (n in first..last) drawNode(canvas, n, t)

        // Fim visível: fases infinitas.
        val endX = nodeX(count) + spacing
        if (endX < w + spacing) {
            text.textSize = 15 * density
            text.color = Color.WHITE
            canvas.drawText("∞", endX, nodeY(count + 1) - 6 * density, text)
            text.textSize = 10 * density
            canvas.drawText("INFINITAS", endX, nodeY(count + 1) + 10 * density, text)
        }
        if (!scroller.isFinished) postInvalidateOnAnimation() else postInvalidateDelayed(33)
    }

    private fun drawZone(canvas: Canvas, zone: Int, h: Float, t: Float) {
        val biome = zone % 4
        val x0 = nodeX(zone * Campaign.STAGES_PER_ZONE + 1) - spacing / 2
        val x1 = x0 + spacing * Campaign.STAGES_PER_ZONE
        if (x1 < 0 || x0 > width) return
        val shader = skyShaders.getOrPut(biome * 10000 + height) {
            LinearGradient(0f, 0f, 0f, h, skyTop[biome], skyBottom[biome], Shader.TileMode.CLAMP)
        }
        paint.shader = shader
        canvas.drawRect(x0, 0f, x1 + 1, h, paint)
        paint.shader = null
        // Estrelas e brilho na zona devastada (noite).
        if (biome == 3) {
            paint.color = Color.argb(160, 255, 255, 255)
            for (i in 0 until 18) {
                val sx = x0 + ((i * 97 + zone * 31) % 100) / 100f * (x1 - x0)
                val sy = ((i * 53 + zone * 17) % 100) / 100f * h * 0.45f
                canvas.drawRect(sx, sy, sx + 2 * density, sy + 2 * density, paint)
            }
        }
        // Morros em blocos (pixel art) e decoração do bioma.
        val block = 12f * density
        val baseY = h * 0.82f
        var x = x0
        var i = 0
        while (x < x1) {
            val hill = (2 + ((i * 7 + zone * 13) % 5) + ((i / 3 + zone) % 3)).toFloat()
            paint.color = groundDark[biome]
            canvas.drawRect(x, baseY - hill * block * 0.5f, x + block + 1, h, paint)
            paint.color = ground[biome]
            canvas.drawRect(x, baseY - hill * block * 0.5f, x + block + 1, baseY - hill * block * 0.5f + block * 0.45f, paint)
            if ((i + zone) % 6 == 2) drawProp(canvas, biome, x + block / 2, baseY - hill * block * 0.5f, t)
            x += block
            i++
        }
        // Placa da zona.
        val signX = x0 + spacing * 0.5f
        val zoneInfo = Campaign.zone(zone * Campaign.STAGES_PER_ZONE + 1)
        text.textSize = 11 * density
        val label = "ZONA ${zone + 1} • ${zoneInfo.name.uppercase()}"
        val tw = text.measureText(label) + 20 * density
        rect.set(signX - 10 * density, h * 0.17f, signX - 10 * density + tw, h * 0.17f + 24 * density)
        paint.color = Color.argb(190, 8, 14, 26)
        canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)
        text.color = zoneInfo.color
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, signX, h * 0.17f + 16 * density, text)
        text.textAlign = Paint.Align.CENTER
        // Divisória entre zonas.
        paint.color = Color.argb(60, 0, 0, 0)
        canvas.drawRect(x1 - 1 * density, 0f, x1 + 1 * density, h, paint)
    }

    private fun drawProp(canvas: Canvas, biome: Int, cx: Float, groundY: Float, t: Float) {
        val b = 6f * density
        when (biome) {
            0 -> { // árvore com copa balançando
                val sway = sin(t * 1.6f + cx * 0.01f) * b * 0.25f
                paint.color = 0xFF6B4E2E.toInt()
                canvas.drawRect(cx - b * 0.5f, groundY - b * 4, cx + b * 0.5f, groundY, paint)
                paint.color = 0xFF3F8A2A.toInt()
                canvas.drawRect(cx - b * 2 + sway, groundY - b * 7, cx + b * 2 + sway, groundY - b * 3.5f, paint)
                paint.color = 0xFF52A838.toInt()
                canvas.drawRect(cx - b * 1.2f + sway, groundY - b * 8, cx + b * 1.2f + sway, groundY - b * 6.5f, paint)
            }
            1 -> { // cacto
                paint.color = 0xFF3E8A35.toInt()
                canvas.drawRect(cx - b * 0.6f, groundY - b * 5, cx + b * 0.6f, groundY, paint)
                canvas.drawRect(cx + b * 0.6f, groundY - b * 3.5f, cx + b * 1.6f, groundY - b * 2.8f, paint)
                canvas.drawRect(cx + b * 1.0f, groundY - b * 4.6f, cx + b * 1.6f, groundY - b * 2.8f, paint)
            }
            2 -> { // pinheiro com neve
                paint.color = 0xFF2F5E3A.toInt()
                for (k in 0 until 3) canvas.drawRect(cx - b * (2 - k * 0.6f), groundY - b * (3 + k * 2), cx + b * (2 - k * 0.6f), groundY - b * (1.5f + k * 2), paint)
                paint.color = 0xFFF0F6FA.toInt()
                canvas.drawRect(cx - b * 0.8f, groundY - b * 7.5f, cx + b * 0.8f, groundY - b * 7f, paint)
                paint.color = 0xFF4A3420.toInt()
                canvas.drawRect(cx - b * 0.4f, groundY - b * 1.5f, cx + b * 0.4f, groundY, paint)
            }
            else -> { // árvore morta e brilho de lava
                paint.color = 0xFF5A5048.toInt()
                canvas.drawRect(cx - b * 0.4f, groundY - b * 5, cx + b * 0.4f, groundY, paint)
                canvas.drawRect(cx, groundY - b * 4, cx + b * 1.8f, groundY - b * 3.4f, paint)
                paint.color = Color.argb((120 + 60 * sin(t * 3 + cx)).toInt().coerceIn(0, 255), 255, 110, 40)
                canvas.drawRect(cx - b * 2, groundY - b * 0.4f, cx + b * 2, groundY, paint)
            }
        }
    }

    private fun drawNode(canvas: Canvas, n: Int, t: Float) {
        val x = nodeX(n)
        val y = nodeY(n)
        if (x < -spacing || x > width + spacing) return
        val p = app.progress
        val record = p.stages[n]
        val done = record?.completed == true
        val current = n == unlocked
        val locked = n > unlocked
        val finale = n % Campaign.STAGES_PER_ZONE == 0
        val zone = Campaign.zone(n)
        val r = (if (finale) 30f else 25f) * density
        if (current) {
            val pulse = 1f + 0.12f * sin(t * 4f)
            paint.color = Color.argb(90, 255, 255, 255)
            canvas.drawCircle(x, y, r * 1.45f * pulse, paint)
        }
        if (n == selected) {
            paint.color = Palette.lime
            rect.set(x - r - 5 * density, y - r - 5 * density, x + r + 5 * density, y + r + 5 * density)
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint)
        }
        // Bloco com relevo.
        paint.color = Color.argb(200, 0, 0, 0)
        rect.set(x - r, y - r, x + r, y + r + 4 * density)
        canvas.drawRoundRect(rect, 9 * density, 9 * density, paint)
        val fill = when {
            locked -> Palette.stone
            done -> zone.color
            else -> Color.WHITE
        }
        paint.color = BlockDrawable.shade(fill, 0.6f)
        rect.set(x - r + 2 * density, y - r + 2 * density, x + r - 2 * density, y + r + 2 * density)
        canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)
        paint.color = fill
        rect.set(x - r + 2 * density, y - r + 2 * density, x + r - 2 * density, y + r - 2 * density)
        canvas.drawRoundRect(rect, 8 * density, 8 * density, paint)
        paint.color = Color.argb(70, 255, 255, 255)
        rect.set(x - r + 6 * density, y - r + 4 * density, x + r - 6 * density, y - r + 7 * density)
        canvas.drawRoundRect(rect, 3 * density, 3 * density, paint)

        if (locked) {
            // Cadeado desenhado.
            paint.color = Color.argb(220, 200, 208, 224)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * density
            rect.set(x - 7 * density, y - 13 * density, x + 7 * density, y + 1 * density)
            canvas.drawArc(rect, 180f, 180f, false, paint)
            paint.style = Paint.Style.FILL
            rect.set(x - 10 * density, y - 5 * density, x + 10 * density, y + 11 * density)
            canvas.drawRoundRect(rect, 3 * density, 3 * density, paint)
        } else {
            text.textSize = (if (finale) 20f else 18f) * density
            text.color = if (done) Palette.dark else Color.parseColor("#14203A")
            canvas.drawText(n.toString(), x, y + 6 * density, text)
        }
        if (done) {
            val stars = record?.stars ?: 0
            text.textSize = 13 * density
            for (k in 0 until 3) {
                text.color = if (k < stars) Palette.gold else Color.argb(160, 20, 24, 36)
                canvas.drawText("★", x + (k - 1) * 13 * density, y + r + 17 * density, text)
            }
        }
        if (finale) {
            // Bandeira de fim de zona.
            paint.color = 0xFF3A2A1A.toInt()
            canvas.drawRect(x + r - 4 * density, y - r - 22 * density, x + r - 1 * density, y - r + 2 * density, paint)
            paint.color = zone.color
            path.reset()
            path.moveTo(x + r - 1 * density, y - r - 22 * density)
            path.lineTo(x + r + 15 * density, y - r - 16 * density)
            path.lineTo(x + r - 1 * density, y - r - 10 * density)
            path.close()
            canvas.drawPath(path, paint)
        }
        if (current) {
            val bob = sin(t * 3f) * 4 * density
            val size = 40 * density
            val hy = y - r - size - 8 * density + bob
            hero?.let {
                paint.isFilterBitmap = false
                rect.set(x - size / 2, hy, x + size / 2, hy + size)
                canvas.drawBitmap(it, null, rect, paint)
            }
            text.textSize = 10 * density
            text.color = Color.WHITE
            val label = if (Rules.canSkip(p, n)) "TRAVOU? PULE!" else "VOCÊ ESTÁ AQUI"
            canvas.drawText(label, x, y + r + (if (done) 32 else 18) * density, text)
        }
    }
}
