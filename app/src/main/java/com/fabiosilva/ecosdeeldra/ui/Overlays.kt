package com.fabiosilva.ecosdeeldra.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.RewardSummary
import com.fabiosilva.ecosdeeldra.data.SkinDef
import com.fabiosilva.ecosdeeldra.data.Stage
import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.NativeBridge
import kotlin.math.hypot

private fun dimmed(activity: Activity, alpha: Int = 190): FrameLayout = FrameLayout(activity).apply {
    setBackgroundColor(Color.argb(alpha, 4, 7, 14))
    isClickable = true
    isFocusable = true
}

/** Caixa de diálogo do jogo, sempre acima de tudo. */
object Popup {
    fun show(
        activity: Activity,
        title: String,
        message: String,
        ok: String,
        cancel: String? = null,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val layer = dimmed(activity)
        val panel = Ui.panel(activity)
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.addView(Ui.title(activity, title, 20f, Palette.lime).apply { gravity = Gravity.CENTER })
        panel.addView(Ui.text(activity, message, 14f, Palette.text).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(0, activity.dpi(10), 0, activity.dpi(14))
        })
        val row = Ui.row(activity, Gravity.CENTER)
        fun close(result: Boolean) {
            content.removeView(layer)
            onResult?.invoke(result)
        }
        if (cancel != null) {
            row.addView(Ui.button(activity, cancel, Palette.stone, Color.WHITE, 13f) { close(false) }, Ui.lp(0, activity.dpi(48), 1f).apply { marginEnd = activity.dpi(10) })
        }
        row.addView(Ui.button(activity, ok, Palette.lime, Palette.dark, 14f) { close(true) }, Ui.lp(0, activity.dpi(48), 1f))
        panel.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        layer.addView(panel, Ui.frame(activity.dpi(440), Ui.WRAP, Gravity.CENTER))
        content.addView(layer, FrameLayout.LayoutParams(-1, -1))
        Ui.popIn(panel)
    }
}

@SuppressLint("ViewConstructor")
class StoryOverlay(app: MainActivity, stage: Stage, onStart: () -> Unit) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(120, 4, 7, 14))
        isClickable = true
        val panel = Ui.panel(app)
        val row = Ui.row(app)
        val avatar = Ui.title(app, stage.speaker.first().toString(), 26f, Palette.dark).apply {
            gravity = Gravity.CENTER
            background = BlockDrawable(app, stage.zone.color, 8f, 3f)
        }
        row.addView(avatar, Ui.lp(app.dpi(58), app.dpi(58)))
        val texts = Ui.column(app).apply { setPadding(app.dpi(14), 0, 0, 0) }
        texts.addView(Ui.text(app, "RÁDIO • ${stage.speaker.uppercase()}", 11f, stage.zone.color, Fonts.title))
        texts.addView(Ui.title(app, stage.title, 20f))
        stage.story.forEach { texts.addView(Ui.text(app, it, 13f, Palette.text).apply { setPadding(0, app.dpi(3), 0, 0) }) }
        texts.addView(Ui.text(app, "Objetivo: elimine todos os zumbis da ilha e atravesse o portal de obsidiana.", 12f, Palette.lime).apply { setPadding(0, app.dpi(6), 0, 0) })
        row.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        row.addView(Ui.button(app, "COMEÇAR ▶", Palette.lime, Palette.dark, 16f) { onStart() }, Ui.lp(app.dpi(170), app.dpi(56)).apply { marginStart = app.dpi(12) })
        panel.addView(row)
        addView(panel, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { setMargins(app.dpi(24), 0, app.dpi(24), app.dpi(20)) })
        Ui.popIn(panel)
    }
}

@SuppressLint("ViewConstructor")
class LoadingOverlay(app: MainActivity, zoneLabel: String, title: String, zoneColor: Int, tipIndex: Int) : FrameLayout(app) {
    constructor(app: MainActivity, stage: Stage) : this(app, "ZONA ${stage.zone.index + 1} • ${stage.zone.name.uppercase()}", "Fase ${stage.number} · ${stage.title}", stage.zone.color, stage.number)

    private val tips = listOf(
        "Empurre o joystick até o fim para correr.",
        "Tiros na cabeça causam dano em dobro.",
        "Zumbis derrubam cristais e maçãs de vida.",
        "Arraste o botão ATIRAR para mirar enquanto dispara.",
        "O tiro chama a atenção da horda — cuidado!",
        "Volte amanhã: o calendário libera skins exclusivas.",
        "Na água você nada mais devagar. Use as trilhas!",
    )

    init {
        setBackgroundColor(Palette.bg)
        isClickable = true
        val column = Ui.column(app).apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(Ui.text(app, zoneLabel, 12f, zoneColor, Fonts.title))
        column.addView(Ui.title(app, title, 26f).apply { setPadding(0, app.dpi(6), 0, app.dpi(18)) })
        val blocks = Ui.row(app, Gravity.CENTER)
        val views = (0 until 5).map { i ->
            View(app).apply { background = BlockDrawable(app, if (i % 2 == 0) Palette.lime else Palette.limeDark, 3f, 3f) }.also {
                blocks.addView(it, Ui.lp(app.dpi(22), app.dpi(22)).apply { marginEnd = app.dpi(6) })
            }
        }
        ValueAnimator.ofFloat(0f, 5f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                views.forEachIndexed { i, v -> v.translationY = -app.dp(10f) * maxOf(0f, 1f - kotlin.math.abs(t - i)) }
            }
            start()
        }
        column.addView(blocks)
        column.addView(Ui.text(app, "Gerando o mundo aberto...", 14f, Palette.muted).apply { setPadding(0, app.dpi(18), 0, app.dpi(6)) })
        column.addView(Ui.text(app, "Dica: ${tips[tipIndex % tips.size]}", 13f, Palette.lime))
        addView(column, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
    }
}

@SuppressLint("ViewConstructor")
class PauseOverlay(app: MainActivity, stage: Stage) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(170, 4, 7, 14))
        isClickable = true
        val panel = Ui.panel(app)
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.addView(Ui.title(app, "PAUSADO", 26f, Palette.lime))
        panel.addView(Ui.text(app, "Fase ${stage.number} · ${stage.title}", 13f, Palette.muted).apply { setPadding(0, app.dpi(4), 0, app.dpi(12)) })
        val buttons = listOf(
            Triple("CONTINUAR ▶", Palette.lime) { app.resumeMission() },
            Triple("REINICIAR", Palette.stone) { app.abandonMission(retry = true) },
            Triple("AJUSTES", Palette.stone) { app.showPauseSettings() },
            Triple("SAIR DA FASE", Palette.red) { app.abandonMission(retry = false) },
        )
        for ((label, color, action) in buttons) {
            panel.addView(Ui.button(app, label, color, if (color == Palette.lime) Palette.dark else Color.WHITE, 15f, action), Ui.lp(Ui.MATCH, app.dpi(50)).apply { bottomMargin = app.dpi(8) })
        }
        addView(panel, Ui.frame(app.dpi(320), Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
    }
}

@SuppressLint("ViewConstructor")
class VictoryOverlay(
    private val app: MainActivity,
    stage: Stage,
    result: MissionResult,
    summary: RewardSummary,
    unlocked: List<SkinDef>,
) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(190, 4, 7, 14))
        isClickable = true
        val panel = Ui.panel(app)
        val top = Ui.row(app)
        val left = Ui.column(app)
        left.addView(Ui.text(app, "FASE ${stage.number} CONCLUÍDA", 11f, Palette.lime, Fonts.title))
        left.addView(Ui.title(app, "VITÓRIA!", 30f, Palette.gold))
        val stars = Ui.row(app)
        repeat(3) { i ->
            val star = Ui.title(app, if (i < summary.stars) "★" else "☆", 40f, if (i < summary.stars) Palette.gold else Palette.muted)
            stars.addView(star)
            star.alpha = 0f
            star.scaleX = 0.3f
            star.scaleY = 0.3f
            star.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(250L + i * 280L).setDuration(260).start()
        }
        left.addView(stars)
        val labels = listOf("Vencer a fase", "Terminar com vida ≥ 50%", "Tempo ≤ %d:%02d".format(stage.targetSeconds / 60, stage.targetSeconds % 60))
        summary.conditions.forEachIndexed { i, ok ->
            left.addView(Ui.text(app, (if (ok) "✓ " else "✗ ") + labels[i], 12f, if (ok) Palette.lime else Palette.muted))
        }
        top.addView(left, Ui.lp(0, Ui.WRAP, 1f))

        val right = Ui.column(app)
        val time = result.elapsedSeconds.toInt()
        val accuracy = if (result.shots > 0) result.hits * 100 / result.shots else 0
        right.addView(Ui.text(app, "Tempo %d:%02d  •  Abates %d  •  Na cabeça %d  •  Precisão %d%%".format(time / 60, time % 60, result.kills, result.headshots, accuracy), 12f, Palette.muted))
        fun reward(label: String, value: Int) {
            if (value <= 0) return
            val row = Ui.row(app)
            row.addView(Ui.text(app, label, 13f, Palette.text), Ui.lp(0, Ui.WRAP, 1f))
            row.addView(Ui.text(app, "◆ $value", 13f, Palette.cyan, Fonts.title))
            right.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(4) })
        }
        reward("Primeira vitória", summary.firstClear)
        reward("Estrelas novas", summary.starBonus)
        reward("Cristais novos (${summary.newCrystals})", summary.crystalBonus)
        reward("Zumbis abatidos", summary.killBonus)
        val totalRow = Ui.row(app)
        totalRow.addView(Ui.title(app, "TOTAL", 16f), Ui.lp(0, Ui.WRAP, 1f))
        val totalText = Ui.title(app, "◆ ${summary.total}", 18f, Palette.cyan)
        totalRow.addView(totalText)
        right.addView(totalRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(8) })
        if (unlocked.isNotEmpty()) {
            right.addView(Ui.text(app, "NOVA SKIN: ${unlocked.joinToString { it.name }}!", 14f, Palette.gold, Fonts.title).apply { setPadding(0, app.dpi(8), 0, 0) })
        }
        top.addView(right, Ui.lp(0, Ui.WRAP, 1.2f).apply { marginStart = app.dpi(16) })
        panel.addView(top)

        val buttons = Ui.row(app)
        if (summary.total > 0) {
            lateinit var double: android.widget.TextView
            double = Ui.button(app, "▶ DOBRAR ◆${summary.total}", Palette.cyan, Palette.dark, 13f) {
                app.doubleRewardWithAd(summary.total) {
                    totalText.text = "◆ ${summary.total * 2}"
                    Ui.setEnabledLook(double, false)
                    double.text = "DOBRADO ✓"
                }
            }
            buttons.addView(double, Ui.lp(0, app.dpi(50), 1.2f).apply { marginEnd = app.dpi(8) })
        }
        buttons.addView(Ui.button(app, "MENU", Palette.stone, Color.WHITE, 14f) { app.leaveAfterVictory(next = false) }, Ui.lp(0, app.dpi(50), 0.8f).apply { marginEnd = app.dpi(8) })
        if (stage.number < 20) {
            buttons.addView(Ui.button(app, "PRÓXIMA FASE ▶", Palette.lime, Palette.dark, 14f) { app.leaveAfterVictory(next = true) }, Ui.lp(0, app.dpi(50), 1.2f))
        }
        panel.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(12) })
        addView(panel, Ui.frame(app.dpi(640), Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
        if (unlocked.isNotEmpty()) postDelayed({ app.audio.playUi("unlock", 1f) }, 1200)
    }
}

@SuppressLint("ViewConstructor")
class DefeatOverlay(app: MainActivity, stage: Stage, canRevive: Boolean) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(170, 40, 4, 8))
        isClickable = true
        val panel = Ui.panel(app, Palette.panel, Color.argb(120, 255, 90, 90))
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.addView(Ui.title(app, "VOCÊ CAIU!", 28f, Palette.red))
        panel.addView(Ui.text(app, "A horda da fase ${stage.number} te pegou. Seus abates contam para as missões.", 13f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, app.dpi(6), 0, app.dpi(14))
        })
        if (canRevive) {
            panel.addView(Ui.button(app, "▶ REVIVER (ANÚNCIO)", Palette.cyan, Palette.dark, 15f) { app.reviveWithAd() }, Ui.lp(Ui.MATCH, app.dpi(52)).apply { bottomMargin = app.dpi(8) })
        }
        panel.addView(Ui.button(app, "TENTAR DE NOVO", Palette.lime, Palette.dark, 15f) { app.abandonMission(retry = true) }, Ui.lp(Ui.MATCH, app.dpi(50)).apply { bottomMargin = app.dpi(8) })
        panel.addView(Ui.button(app, "MENU", Palette.stone, Color.WHITE, 14f) { app.abandonMission(retry = false) }, Ui.lp(Ui.MATCH, app.dpi(46)))
        addView(panel, Ui.frame(app.dpi(360), Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
    }
}

/** Mapa grande da ilha (pausa o jogo). Toque para fechar. */
@SuppressLint("ViewConstructor")
class MapOverlay(app: MainActivity, private val map: Bitmap?, onClose: () -> Unit) : FrameLayout(app) {
    private val hud = FloatArray(Hud.SIZE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.title; textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val rect = RectF()
    private val path = Path()

    init {
        setBackgroundColor(Color.argb(215, 4, 7, 14))
        setWillNotDraw(false)
        setOnClickListener { onClose() }
        NativeBridge.copyHud(hud)
        addView(Ui.text(app, "MAPA DA ILHA • toque para voltar", 12f, Palette.lime, Fonts.title), Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = app.dpi(10) })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = map ?: return
        val size = minOf(width * 0.9f, height - context.dp(60f))
        val left = (width - size) / 2
        val top = (height - size) / 2 + context.dp(14f)
        rect.set(left, top, left + size, top + size)
        paint.isFilterBitmap = false
        canvas.drawBitmap(bmp, null, rect, paint)
        val k = size / bmp.width
        val alive = hud[Hud.ALIVE].toInt()
        val n = hud[Hud.ENEMY_COUNT].toInt()
        val px = hud[Hud.PLAYER_X]
        val pz = hud[Hud.PLAYER_Z]
        for (i in 0 until n) {
            val ex = hud[Hud.ENEMIES + i * 3]
            val ez = hud[Hud.ENEMIES + i * 3 + 1]
            if (alive > 3 && hypot(ex - px, ez - pz) > 40f && hud[Hud.ENEMIES + i * 3 + 2] < 0.5f) continue
            paint.color = Palette.red
            canvas.drawCircle(left + ex * k, top + ez * k, context.dp(3f), paint)
        }
        if (hud[Hud.PORTAL_OPEN] > 0.5f) {
            paint.color = Palette.purple
            canvas.drawCircle(left + hud[Hud.PORTAL_X] * k, top + hud[Hud.PORTAL_Z] * k, context.dp(6f), paint)
            text.textSize = context.dp(11f)
            canvas.drawText("PORTAL", left + hud[Hud.PORTAL_X] * k, top + hud[Hud.PORTAL_Z] * k - context.dp(9f), text)
        }
        val x = left + px * k
        val y = top + pz * k
        val yaw = hud[Hud.CAM_YAW]
        val s = context.dp(9f)
        canvas.save()
        canvas.rotate(Math.toDegrees(yaw.toDouble()).toFloat(), x, y)
        path.reset()
        path.moveTo(x, y - s); path.lineTo(x + s * 0.7f, y + s * 0.7f); path.lineTo(x, y + s * 0.3f); path.lineTo(x - s * 0.7f, y + s * 0.7f); path.close()
        paint.color = Color.WHITE
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(2f)
        paint.color = Palette.line
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.FILL
    }
}

@Suppress("unused")
private fun LinearLayout.spacer(size: Int) = addView(View(context), LinearLayout.LayoutParams(size, size))
