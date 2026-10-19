package com.fabiosilva.ecosdeeldra.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Rules
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private enum class DayState { COLLECTED, CURRENT, LOCKED }

/** Fundo da prévia: faixa escura no meio com listras de aviso e abas roxas inclinadas nas laterais. */
private class PreviewBackdrop(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = h * 0.08f
        val bottom = h * 0.92f
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, top, 0f, bottom, Color.parseColor("#F0170F3A"), Color.parseColor("#F00C0724"), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, top, w, bottom, p)
        p.shader = null
        // listras de aviso nas bordas da faixa
        p.color = Color.argb(120, 200, 190, 255)
        var x = -d(10f)
        while (x < w) {
            Shapes.slanted(path, x, top, x + d(10f), top + d(5f), d(4f))
            canvas.drawPath(path, p)
            Shapes.slanted(path, x, bottom - d(5f), x + d(10f), bottom, d(4f))
            canvas.drawPath(path, p)
            x += d(14f)
        }
        // abas roxas laterais
        val wing = w * 0.075f
        val skew = d(26f)
        p.shader = LinearGradient(0f, 0f, wing, 0f, Color.parseColor("#5B2FD6"), Color.parseColor("#2E1A86"), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(0f, top + d(18f)); path.lineTo(wing, top + d(18f)); path.lineTo(wing - skew, bottom - d(6f)); path.lineTo(0f, bottom - d(6f)); path.close()
        canvas.drawPath(path, p)
        p.shader = LinearGradient(w - wing, 0f, w, 0f, Color.parseColor("#2E1A86"), Color.parseColor("#5B2FD6"), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(w, top + d(18f)); path.lineTo(w - wing, top + d(18f)); path.lineTo(w - wing + skew, bottom - d(6f)); path.lineTo(w, bottom - d(6f)); path.close()
        canvas.drawPath(path, p)
        p.shader = null
        p.color = Color.argb(70, 255, 255, 255)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.2f)
        canvas.drawLine(wing, top + d(18f), wing - skew, bottom - d(6f), p)
        canvas.drawLine(w - wing, top + d(18f), w - wing + skew, bottom - d(6f), p)
        p.style = Paint.Style.FILL
    }
}

/** Aba "DIA N": seta amarela (coletado/atual) ou aba roxa com cadeado (bloqueado). */
private class DayTab(context: Context, private val label: String, private val state: DayState) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tip = d(8f)
        p.style = Paint.Style.FILL
        if (state == DayState.LOCKED) {
            Shapes.slanted(path, 0f, 0f, w, h, tip)
            p.color = Palette.purple
        } else {
            Shapes.chevron(path, 0f, 0f, w, h, tip)
            p.color = Palette.accent
        }
        canvas.drawPath(path, p)
        if (state != DayState.LOCKED) {
            canvas.save()
            canvas.clipPath(path)
            p.color = Color.argb(55, 160, 90, 0)
            var x = -h
            while (x < w) {
                Shapes.slanted(path, x, 0f, x + d(5f), h, d(5f))
                canvas.drawPath(path, p)
                x += d(11f)
            }
            canvas.restore()
        }
        p.color = if (state == DayState.LOCKED) Color.WHITE else Palette.dark
        p.typeface = Fonts.title
        p.textAlign = Paint.Align.CENTER
        p.textSize = d(12f)
        val textX = if (state == DayState.LOCKED) w / 2f + d(7f) else w / 2f
        canvas.drawText(label, textX, h / 2f + d(4.2f), p)
        if (state == DayState.LOCKED) {
            Icons.draw(canvas, Icon.LOCK, textX - p.measureText(label) / 2f - d(9f), h / 2f, d(5.5f), Color.WHITE, p)
        }
    }
}

/** Cartão de recompensa de um dia. */
private class DayCard(context: Context, private val amount: Int, private val state: DayState) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#FF120C2C"), Color.parseColor("#FF2B1259"), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w / 2f, h * 0.45f, w * 0.7f, Color.argb(if (state == DayState.CURRENT) 150 else 80, 255, 120, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
        Icons.draw(canvas, Icon.DIAMOND, w / 2f, h * 0.38f, min(w, h) * 0.2f, Palette.cyan, p)
        p.color = Color.WHITE
        p.typeface = Fonts.title
        p.textAlign = Paint.Align.CENTER
        p.textSize = d(16f)
        canvas.drawText("$amount", w / 2f, h * 0.76f, p)
        // faixa inferior
        p.color = when (state) { DayState.CURRENT -> Palette.accent; DayState.COLLECTED -> Palette.accentDark; DayState.LOCKED -> Palette.purple }
        canvas.drawRect(0f, h - d(4f), w, h, p)
        if (state == DayState.COLLECTED) {
            p.color = Color.argb(185, 0, 0, 0)
            rect.set(0f, h * 0.3f, w, h * 0.52f)
            canvas.drawRect(rect, p)
            p.color = Palette.accent
            p.textSize = d(13f)
            canvas.drawText("Coletado", w / 2f, h * 0.3f + d(15f), p)
        }
        if (state == DayState.CURRENT) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(2.6f)
            p.color = Palette.accent
            canvas.drawRect(d(1.3f), d(1.3f), w - d(1.3f), h - d(1.3f), p)
            p.style = Paint.Style.FILL
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(1f)
            p.color = Color.argb(60, 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, p)
            p.style = Paint.Style.FILL
        }
    }
}

/** "PRÉVIA DE RECOMPENSAS": os 7 dias do calendário, com o dia de hoje em destaque. */
@SuppressLint("ViewConstructor")
class RewardPreviewOverlay(private val app: MainActivity, canClaim: Boolean, onClaim: () -> Unit, onClose: () -> Unit) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(205, 3, 2, 12))
        isClickable = true
        isFocusable = true

        val dialog = FrameLayout(app).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.parseColor("#F00C0724"))
        }
        dialog.addView(PreviewBackdrop(app), Ui.frame(Ui.MATCH, Ui.MATCH))

        val p = app.progress
        // Janela de 7 dias. A sequência segue (dia 8, 9…); só a janela anda a cada semana.
        // Se já resgatou hoje, a janela é a do último resgate, para mostrar o dia recém-coletado.
        val cycleStart = ((p.calendarClaims - if (canClaim) 0 else 1).coerceAtLeast(0)) / 7 * 7

        val column = Ui.column(app).apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(
            Ui.title(app, "PRÉVIA DE RECOMPENSAS", 28f, Color.parseColor("#D4FFF6")).apply {
                setShadowLayer(app.dp(14f), 0f, 0f, Color.parseColor("#8033E6FF"))
            },
        )
        column.addView(
            Ui.text(app, "Entre todo dia para coletar. A sequência não reinicia: cada dia paga mais que o anterior.", 12f, Palette.text).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setPadding(0, app.dpi(6), 0, app.dpi(10))
            },
        )
        val row = Ui.row(app, Gravity.TOP)
        for (offset in 0 until 7) {
            val index = cycleStart + offset
            val state = when {
                index < p.calendarClaims -> DayState.COLLECTED
                canClaim && index == p.calendarClaims -> DayState.CURRENT
                else -> DayState.LOCKED
            }
            val cell = Ui.column(app)
            cell.addView(DayTab(app, "DIA ${index + 1}", state), Ui.lp(Ui.MATCH, app.dpi(24)))
            val card = DayCard(app, Rules.calendarReward(index), state)
            cell.addView(card, Ui.lp(Ui.MATCH, app.dpi(104)).apply { topMargin = app.dpi(4); marginStart = app.dpi(6); marginEnd = app.dpi(6) })
            if (state == DayState.CURRENT) {
                card.isClickable = true
                card.setOnClickListener { Ui.clickSound?.invoke(); onClaim() }
                Ui.pulse(card)
            }
            row.addView(cell, Ui.lp(0, Ui.WRAP, 1f))
        }
        column.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        if (canClaim) {
            val claim = Ui.button(app, "RESGATAR  ◆ ${Rules.calendarReward(p.calendarClaims)}", Palette.accent, Palette.dark, 18f) { onClaim() }
            column.addView(claim, Ui.lp(app.dpi(260), app.dpi(50)).apply { topMargin = app.dpi(12) })
        } else {
            column.addView(
                Ui.text(app, "Prêmio de hoje já resgatado. Volte amanhã!", 13f, Palette.muted).apply { setPadding(0, app.dpi(14), 0, 0) },
            )
        }
        dialog.addView(column, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER).apply {
            setMargins(app.dpi(34), app.dpi(18), app.dpi(34), app.dpi(18))
        })
        dialog.addView(
            Ui.iconButton(app, Icon.CLOSE, Palette.stone, Color.WHITE) { onClose() },
            Ui.frame(app.dpi(42), app.dpi(42), Gravity.TOP or Gravity.END).apply { setMargins(0, app.dpi(8), app.dpi(8), 0) },
        )
        addView(dialog, Ui.frame(Ui.MATCH, app.dpi(312), Gravity.CENTER).apply {
            setMargins(app.dpi(48), app.dpi(24), app.dpi(48), app.dpi(24))
        })
        Ui.popIn(dialog)
    }
}

/** Fundo da tela "PARABÉNS": escuro, com riscos diagonais e uma linha com trecho amarelo sob o título. */
private class CongratsBackdrop(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streaks = run {
        val rnd = Random(7)
        List(46) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.05f + rnd.nextFloat() * 0.22f, 20f + rnd.nextFloat() * 70f) }
    }
    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#F2120B2E"), Color.parseColor("#F2070412"), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (s in streaks) {
            val x = s[0] * w
            val y = s[1] * h
            val len = d(s[3])
            p.strokeWidth = d(1.2f)
            p.color = if (s[2] > 0.18f) Color.argb(60, 255, 255, 255) else Color.argb(50, 160, 110, 255)
            canvas.drawLine(x, y, x + len, y + len * 0.9f, p)
        }
        p.style = Paint.Style.FILL
        // linha sob o título
        val lineY = h * 0.2f
        p.color = Color.argb(110, 200, 190, 255)
        canvas.drawRect(0f, lineY, w, lineY + d(2f), p)
        p.color = Palette.accent
        canvas.drawRect(w * 0.4f, lineY - d(1f), w * 0.4f + d(52f), lineY + d(3f), p)
        // faíscas ao lado do título
        p.color = Color.WHITE
        val cx = w / 2f
        val ty = h * 0.11f
        for (side in intArrayOf(-1, 1)) {
            for ((dx, dy, r) in listOf(Triple(120f, -4f, 1.6f), Triple(138f, 6f, 1f), Triple(150f, -10f, 1f), Triple(158f, 2f, 1.6f))) {
                canvas.drawCircle(cx + side * d(dx), ty + d(dy), d(r), p)
            }
        }
    }
}

/** Cartão de loot roxo com estrela, brilho pulsante e flutuação. */
private class LootCard(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var t = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            translationY = sin(t * 6.2832f) * d(5f)
            invalidate()
        }
    }
    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pulse = 0.65f + 0.35f * sin(t * 6.2832f * 1.5f)
        p.style = Paint.Style.FILL
        // moldura escura
        p.color = Color.parseColor("#0B0620")
        canvas.drawRect(0f, 0f, w, h, p)
        // brilho
        p.shader = RadialGradient(w / 2f, h * 0.45f, w * 0.85f, Color.argb((170 * pulse).toInt(), 200, 70, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // "ticket" roxo
        val tw = w * 0.5f
        val th = h * 0.5f
        val l = (w - tw) / 2f
        val tp = h * 0.2f
        Shapes.chamfer(path, l, tp, l + tw, tp + th, d(9f))
        p.shader = LinearGradient(0f, tp, 0f, tp + th, Color.parseColor("#9A62FF"), Color.parseColor("#5A2DC4"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.5f)
        p.color = Color.argb(150, 220, 200, 255)
        canvas.drawPath(path, p)
        p.style = Paint.Style.FILL
        // disco com estrela
        p.color = Color.parseColor("#E9E2FF")
        canvas.drawCircle(w / 2f, tp + th * 0.48f, tw * 0.34f, p)
        Icons.draw(canvas, Icon.STAR, w / 2f, tp + th * 0.48f, tw * 0.22f, Color.parseColor("#B23DE0"), p)
        // faixa rosa embaixo
        p.shader = LinearGradient(0f, 0f, w, 0f, Color.parseColor("#FF2FD0"), Color.parseColor("#8B3DFF"), Shader.TileMode.CLAMP)
        rect.set(0f, h - d(4f), w * (0.35f + 0.65f * pulse), h)
        canvas.drawRect(rect, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1f)
        p.color = Color.argb(110, 255, 255, 255)
        canvas.drawRect(0f, 0f, w, h, p)
        p.style = Paint.Style.FILL
    }
}

/** "PARABÉNS": mostra o item ganho no centro e um botão OK. */
@SuppressLint("ViewConstructor")
class RewardCongratsOverlay(app: MainActivity, label: String, caption: String, onOk: () -> Unit) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(205, 3, 2, 12))
        isClickable = true
        isFocusable = true
        val dialog = FrameLayout(app).apply {
            isClickable = true
            isFocusable = true
            background = PanelDrawable(app, Color.parseColor("#F00B0718"), Color.parseColor("#806B4BFF"), 8f)
        }
        dialog.addView(CongratsBackdrop(app), Ui.frame(Ui.MATCH, Ui.MATCH))
        dialog.addView(
            Ui.title(app, "PARABÉNS", 34f, Color.WHITE).apply {
                letterSpacing = 0.06f
                setShadowLayer(app.dp(12f), 0f, 0f, Color.parseColor("#99B07CFF"))
            },
            Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = app.dpi(4) },
        )
        val center = Ui.column(app).apply { gravity = Gravity.CENTER_HORIZONTAL }
        center.addView(LootCard(app), Ui.lp(app.dpi(90), app.dpi(120)))
        center.addView(Ui.title(app, label, 22f, Palette.accent).apply { setPadding(0, app.dpi(10), 0, 0) })
        center.addView(Ui.text(app, caption, 12f, Palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, app.dpi(2), 0, 0) })
        dialog.addView(center, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        val ok = Ui.button(app, "OK", Palette.accent, Palette.dark, 22f) { onOk() }
        dialog.addView(ok, Ui.frame(app.dpi(240), app.dpi(54), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = app.dpi(16) })
        addView(dialog, Ui.frame(Ui.MATCH, app.dpi(320), Gravity.CENTER).apply {
            setMargins(app.dpi(40), app.dpi(24), app.dpi(40), app.dpi(24))
        })
        Ui.popIn(dialog)
    }
}
