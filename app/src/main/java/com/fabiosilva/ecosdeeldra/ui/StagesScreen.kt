package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.Stage

@SuppressLint("ViewConstructor")
class StagesScreen(private val app: MainActivity) : FrameLayout(app) {
    private var selected = app.progress.highestUnlockedStage()
    private val cards = ArrayList<TextView>()
    private val detailTitle = Ui.title(app, "", 20f)
    private val detailInfo = Ui.text(app, "", 12f, Palette.muted)
    private val detailZone = Ui.text(app, "", 11f, Palette.lime, Fonts.title)
    private val startButton: TextView

    init {
        setBackgroundColor(Color.argb(150, 6, 10, 20))
        Ui.blockTouches(this)
        val column = Ui.column(app)
        column.addView(Header.build(app, "CAMPANHA", "4 ZONAS • 20 FASES") { app.showHome() })

        val scroll = HorizontalScrollView(app).apply { isHorizontalScrollBarEnabled = false }
        val row = Ui.row(app, Gravity.CENTER_VERTICAL)
        row.setPadding(app.dpi(20), app.dpi(10), app.dpi(20), app.dpi(10))
        val unlocked = app.progress.highestUnlockedStage()
        for (stage in Campaign.stages) {
            val card = stageCard(stage, stage.number <= unlocked)
            cards.add(card)
            row.addView(card, Ui.lp(app.dpi(116), app.dpi(150)).apply { marginEnd = app.dpi(10) })
            if (stage.number % 5 == 0 && stage.number < 20) row.addView(View(app), Ui.lp(app.dpi(12), 1))
        }
        scroll.addView(row)
        column.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        val detail = Ui.panel(app)
        val detailRow = Ui.row(app)
        val texts = Ui.column(app)
        texts.addView(detailZone)
        texts.addView(detailTitle)
        texts.addView(detailInfo.apply { setPadding(0, app.dpi(4), 0, 0) })
        detailRow.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        startButton = Ui.button(app, "INICIAR ▶", Palette.lime, Palette.dark, 18f) { app.startStage(selected) }
        detailRow.addView(startButton, Ui.lp(app.dpi(190), app.dpi(56)))
        detail.addView(detailRow)
        column.addView(detail, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(app.dpi(20), 0, app.dpi(20), app.dpi(14)) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        select(selected)
        post { scroll.smoothScrollTo(((selected - 2).coerceAtLeast(0)) * app.dpi(126), 0) }
    }

    private fun stageCard(stage: Stage, unlocked: Boolean): TextView {
        val record = app.progress.stages[stage.number]
        val stars = record?.stars ?: 0
        val crystals = Integer.bitCount(record?.crystalMask ?: 0)
        val label = if (unlocked) {
            "%02d\n%s\n%s  ◆%d/3".format(stage.number, stage.title, "★".repeat(stars) + "☆".repeat(3 - stars), crystals)
        } else {
            "%02d\n%s\n🔒 BLOQUEADA".format(stage.number, stage.title)
        }
        return Ui.button(app, label, if (unlocked) Palette.panelSoft else Color.parseColor("#1A2032"), if (unlocked) Color.WHITE else Palette.muted, 12f) {
            if (unlocked) select(stage.number) else app.audio.playUi("click", 0.3f)
        }.apply {
            typeface = Fonts.bold
            setLineSpacing(0f, 1.15f)
            alpha = if (unlocked) 1f else 0.6f
            tag = stage.number
        }
    }

    private fun select(number: Int) {
        selected = number
        cards.forEach { card ->
            val n = card.tag as Int
            val bg = card.background as BlockDrawable
            val stage = Campaign.stage(n)
            bg.fill = when {
                n == number -> stage.zone.color
                n <= app.progress.highestUnlockedStage() -> Palette.panelSoft
                else -> Color.parseColor("#1A2032")
            }
            card.setTextColor(if (n == number) Palette.dark else if (n <= app.progress.highestUnlockedStage()) Color.WHITE else Palette.muted)
            bg.invalidateSelf()
        }
        val stage = Campaign.stage(number)
        val record = app.progress.stages[number]
        detailZone.text = "ZONA ${stage.zone.index + 1} • ${stage.zone.name.uppercase()}"
        detailZone.setTextColor(stage.zone.color)
        detailTitle.text = "Fase ${stage.number} · ${stage.title}"
        val best = record?.bestTime?.takeIf { it > 0 }?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "—"
        val target = "%d:%02d".format(stage.targetSeconds / 60, stage.targetSeconds % 60)
        val reward = if (record?.completed == true) "Já vencida" else "1ª vitória: ◆ ${stage.firstClearDiamonds}"
        detailInfo.text = "Elimine todos os zumbis e entre no portal • ★ vencer · ★ vida ≥ 50% · ★ tempo ≤ $target\nMelhor tempo: $best   •   $reward"
    }
}
