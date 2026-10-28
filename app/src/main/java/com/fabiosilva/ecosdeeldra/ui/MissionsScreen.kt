package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.Upgrades
import java.util.Calendar

/** Missões diárias e seu progresso. */
@SuppressLint("ViewConstructor")
class MissionsScreen(private val app: MainActivity) : FrameLayout(app) {

    init {
        setBackgroundColor(Color.argb(170, 6, 10, 20))
        Ui.blockTouches(this)
        build()
    }

    private fun build() {
        removeAllViews()
        val column = Ui.column(app)
        column.addView(Header.build(app, "MISSÕES", "COMPLETE OBJETIVOS • GANHE DIAMANTES") { app.showHome() })
        val body = Ui.row(app, Gravity.TOP)
        body.addView(sidebar(), Ui.lp(app.dpi(200), Ui.MATCH).apply { setMargins(app.dpi(20), app.dpi(8), app.dpi(10), app.dpi(14)) })
        body.addView(contentArea(), Ui.lp(0, Ui.MATCH, 1f).apply { setMargins(0, app.dpi(8), app.dpi(20), app.dpi(14)) })
        column.addView(body, Ui.lp(Ui.MATCH, 0, 1f))
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
    }

    private fun refresh() = build()

    private fun sidebar(): View {
        val side = Ui.column(app).apply {
            background = PanelDrawable(app, Palette.panel, Palette.line, 14f)
            setPadding(app.dpi(10), app.dpi(12), app.dpi(10), app.dpi(12))
        }
        side.addView(Ui.text(app, "CATEGORIAS", 10f, Palette.muted, Fonts.title).apply {
            letterSpacing = 0.1f
            setPadding(app.dpi(4), 0, 0, app.dpi(8))
        })
        val claimed = app.progress.daily.count { it.claimed }
        side.addView(View(app), Ui.lp(0, 0, 1f))
        val summary = Ui.column(app).apply {
            background = PanelDrawable(app, Palette.panelSoft, Palette.line, 10f)
            setPadding(app.dpi(10), app.dpi(10), app.dpi(10), app.dpi(10))
        }
        summary.addView(Ui.text(app, "HOJE  $claimed/3", 10f, Palette.lime, Fonts.title))
        summary.addView(
            ProgressBarView(app, Palette.lime).apply { fraction = claimed / 3f },
            Ui.lp(Ui.MATCH, app.dpi(8)).apply { topMargin = app.dpi(6) },
        )
        summary.addView(Ui.text(app, "Complete as 3 para ganhar ◆ ${Rules.DAILY_BONUS}", 10f, Palette.muted).apply {
            setPadding(0, app.dpi(6), 0, 0)
        })
        side.addView(summary)
        return side
    }

    private fun contentArea(): View {
        val area = Ui.column(app).apply {
            background = PanelDrawable(app, Palette.panel, Palette.line, 14f)
            setPadding(app.dpi(16), app.dpi(14), app.dpi(16), app.dpi(10))
        }
        area.addView(Ui.title(app, "Missões diárias", 22f))
        area.addView(
            Ui.text(app, captionText(), 12f, Palette.muted).apply {
                setLineSpacing(0f, 1.15f)
                setPadding(0, app.dpi(4), 0, app.dpi(10))
            },
            Ui.lp(Ui.MATCH, Ui.WRAP),
        )
        area.addView(dailyView(), Ui.lp(Ui.MATCH, 0, 1f))
        return area
    }

    private fun captionText(): String {
        val midnight = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
        val hours = ((midnight.timeInMillis - System.currentTimeMillis()) / 3_600_000).coerceAtLeast(0)
        return "Renovam em ${hours}h • complete as 3 missões para ganhar ◆ ${Rules.DAILY_BONUS}"
    }

    private fun rowCard(title: String, subtitle: String, fraction: Float, progressText: String, color: Int, button: View?): LinearLayout {
        val card = Ui.panel(app, Palette.panelSoft)
        val row = Ui.row(app)
        val texts = Ui.column(app)
        texts.addView(Ui.title(app, title, 15f))
        if (subtitle.isNotEmpty()) texts.addView(Ui.text(app, subtitle, 11f, Palette.muted).apply { setPadding(0, app.dpi(2), 0, app.dpi(4)) })
        val barRow = Ui.row(app)
        barRow.addView(ProgressBarView(app, color).apply { this.fraction = fraction }, Ui.lp(0, app.dpi(9), 1f))
        barRow.addView(Ui.text(app, progressText, 11f, Palette.text, Fonts.bold).apply { setPadding(app.dpi(8), 0, 0, 0) })
        texts.addView(barRow)
        row.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        if (button != null) row.addView(button, Ui.lp(app.dpi(150), app.dpi(48)).apply { marginStart = app.dpi(12) })
        card.addView(row)
        return card
    }

    private fun dailyView(): View {
        val p = app.progress
        Rules.refreshDaily(p, app.today())
        val list = Ui.column(app)
        for (mission in p.daily) {
            val def = Rules.dailyDef(mission.id)
            val done = mission.progress >= def.goal
            val shown = if (def.id == "play") "${mission.progress / 60}/${def.goal / 60} min" else "${mission.progress}/${def.goal}"
            val button = when {
                mission.claimed -> Ui.button(app, "RESGATADO ✓", Palette.limeDark, Palette.dark, 12f) {}.also { Ui.setEnabledLook(it, false) }
                done -> Ui.button(app, "RESGATAR ◆${def.reward}", Palette.gold, Palette.dark, 12f) {
                    if (Rules.claimDaily(p, mission.id) > 0) app.audio.playUi("reward", 1f)
                    app.save()
                    refresh()
                }
                else -> Ui.button(app, "◆ ${def.reward}", Palette.stone, Color.WHITE, 12f) {}.also { Ui.setEnabledLook(it, false) }
            }
            list.addView(rowCard(def.title, "", mission.progress.toFloat() / def.goal, shown, Palette.lime, button), Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = app.dpi(8) })
        }
        val allClaimed = p.daily.size == 3 && p.daily.all { it.claimed }
        val bonus = when {
            p.dailyBonusClaimed -> Ui.button(app, "BÔNUS ✓", Palette.limeDark, Palette.dark, 12f) {}.also { Ui.setEnabledLook(it, false) }
            allClaimed -> Ui.button(app, "BÔNUS ◆${Rules.DAILY_BONUS}", Palette.gold, Palette.dark, 12f) {
                if (Rules.claimDailyBonus(p) > 0) app.audio.playUi("unlock", 1f)
                app.save()
                refresh()
            }
            else -> Ui.button(app, "BÔNUS ◆${Rules.DAILY_BONUS}", Palette.stone, Color.WHITE, 12f) {}.also { Ui.setEnabledLook(it, false) }
        }
        val claimed = p.daily.count { it.claimed }
        list.addView(rowCard("Baú do dia", "Resgate as três missões", claimed / 3f, "$claimed/3", Palette.gold, bonus))
        return ScrollView(app).apply { addView(list) }
    }

}

@SuppressLint("ViewConstructor")
class UpgradesScreen(private val app: MainActivity) : FrameLayout(app) {
    private var scrollY = 0

    init {
        setBackgroundColor(Color.argb(150, 6, 10, 20))
        Ui.blockTouches(this)
        build()
    }

    private fun build() {
        removeAllViews()
        val p = app.progress
        val cap = Rules.upgradeCap(p)
        val column = Ui.column(app)
        column.addView(Header.build(app, "MELHORIAS", "FIQUE MAIS FORTE A CADA ZONA") { app.showHome() })
        column.addView(Ui.text(app, "Limite atual: nível $cap • o limite sobe +3 a cada nova zona liberada (próxima: zona ${Rules.nextCapZone(p)}). As zonas também deixam os zumbis mais fortes!", 12f, Palette.gold).apply {
            setPadding(app.dpi(20), app.dpi(8), app.dpi(20), app.dpi(4))
        })
        val grid = Ui.column(app)
        Upgrades.all.chunked(5).forEach { chunk ->
            val row = Ui.row(app, Gravity.TOP)
            chunk.forEachIndexed { i, def ->
                val level = Rules.upgradeLevel(p, def.key)
                val cost = Rules.upgradeCost(level)
                val card = Ui.panel(app)
                card.addView(Ui.text(app, "NÍVEL $level/$cap", 10f, def.color, Fonts.title))
                card.addView(Ui.title(app, def.title, 13f).apply { setPadding(0, app.dpi(3), 0, app.dpi(2)) })
                card.addView(Ui.text(app, def.desc, 10f, Palette.muted).apply { minLines = 3 })
                card.addView(ProgressBarView(app, def.color).apply { fraction = level.toFloat() / cap.coerceAtLeast(1) }, Ui.lp(Ui.MATCH, app.dpi(7)).apply { setMargins(0, app.dpi(6), 0, app.dpi(4)) })
                card.addView(Ui.text(app, if (level > 0) Upgrades.effectText(def.key, level) else "—", 10f, Palette.text, Fonts.bold))
                card.addView(Ui.text(app, "Próximo: " + Upgrades.effectText(def.key, level + 1), 9f, Palette.muted).apply { setPadding(0, 0, 0, app.dpi(6)) })
                val button = when {
                    level >= cap -> Ui.button(app, "LIMITE", Palette.limeDark, Palette.dark, 12f) {}.also { Ui.setEnabledLook(it, false) }
                    else -> Ui.button(app, "◆ $cost", Palette.gold, Palette.dark, 13f) {
                        if (Rules.buyUpgrade(p, def.key)) {
                            app.audio.playUi("unlock", 0.9f)
                            app.save()
                            build()
                        }
                    }.also { Ui.setEnabledLook(it, cost != null && p.diamonds >= cost) }
                }
                card.addView(button, Ui.lp(Ui.MATCH, app.dpi(42)))
                row.addView(card, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = if (i < chunk.size - 1) app.dpi(8) else 0 })
            }
            grid.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = app.dpi(8) })
        }
        grid.addView(Ui.text(app, "Dica: diamantes vêm de vitórias, ondas da sobrevivência, missões diárias, baú grátis e anúncios.", 12f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, app.dpi(6), 0, app.dpi(10))
        })
        val scroll = ScrollView(app).apply { addView(grid) }
        column.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(20), app.dpi(6), app.dpi(20), 0) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        scroll.post { scroll.scrollTo(0, scrollY) }
        scroll.viewTreeObserver.addOnScrollChangedListener { scrollY = scroll.scrollY }
    }
}
