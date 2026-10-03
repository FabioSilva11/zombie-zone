package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.SurvivalRecord
import com.fabiosilva.ecosdeeldra.data.SurvivalResult
import com.fabiosilva.ecosdeeldra.data.SurvivalSummary

private fun formatTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** Tabela do ranking (posição, nome, tempo, onda, abates, pontos). */
private fun rankingTable(app: MainActivity, ranking: List<SurvivalRecord>, highlight: SurvivalRecord?): LinearLayout {
    val list = Ui.column(app)
    val head = Ui.row(app).apply { setPadding(0, 0, 0, app.dpi(4)) }
    fun cell(row: LinearLayout, text: String, weight: Float, color: Int, size: Float = 12f, gravity: Int = Gravity.START) {
        row.addView(Ui.text(app, text, size, color, Fonts.bold).apply { this.gravity = gravity }, Ui.lp(0, Ui.WRAP, weight))
    }
    cell(head, "#", 0.4f, Palette.muted, 10f)
    cell(head, "JOGADOR", 1.6f, Palette.muted, 10f)
    cell(head, "TEMPO", 0.9f, Palette.muted, 10f, Gravity.END)
    cell(head, "ONDA", 0.7f, Palette.muted, 10f, Gravity.END)
    cell(head, "☠", 0.7f, Palette.muted, 10f, Gravity.END)
    cell(head, "PONTOS", 1f, Palette.muted, 10f, Gravity.END)
    list.addView(head)
    if (ranking.isEmpty()) {
        list.addView(Ui.text(app, "Ninguém sobreviveu ainda.\nSeja o primeiro do ranking!", 13f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, app.dpi(24), 0, 0)
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        return list
    }
    ranking.forEachIndexed { i, r ->
        val row = Ui.row(app).apply { setPadding(app.dpi(4), app.dpi(5), app.dpi(4), app.dpi(5)) }
        val mine = highlight != null && r == highlight
        if (mine) row.background = PanelDrawable(app, Color.argb(90, 168, 255, 62), Palette.lime, 6f)
        val medal = when (i) { 0 -> Palette.gold; 1 -> Color.rgb(200, 210, 225); 2 -> Color.rgb(214, 140, 80); else -> Palette.text }
        cell(row, "${i + 1}º", 0.4f, medal, 13f)
        cell(row, r.name, 1.6f, if (mine) Palette.lime else Color.WHITE, 13f)
        cell(row, formatTime(r.seconds), 0.9f, Palette.text, 13f, Gravity.END)
        cell(row, "${r.wave}", 0.7f, Palette.text, 13f, Gravity.END)
        cell(row, "${r.kills}", 0.7f, Palette.text, 13f, Gravity.END)
        cell(row, "${r.score}", 1f, Palette.cyan, 13f, Gravity.END)
        list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
    }
    return list
}

/** Modo sobrevivência: explicação, recorde, bônus por anúncio e ranking. */
@SuppressLint("ViewConstructor")
class SurvivalScreen(private val app: MainActivity) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(170, 6, 10, 20))
        Ui.blockTouches(this)
        val column = Ui.column(app)
        column.addView(Header.build(app, "SOBREVIVÊNCIA", "ONDAS INFINITAS • QUANTO TEMPO VOCÊ AGUENTA?") { app.showHome() })
        val row = Ui.row(app, Gravity.TOP)
        val left = Ui.panel(app)
        left.addView(Ui.text(app, "COMO FUNCIONA", 10f, Palette.lime, Fonts.title))
        left.addView(Ui.text(app, "Uma ilha aleatória de mundo aberto. Os zumbis chegam em ondas, cada vez maiores e mais fortes, vindo de várias direções — a cada 5 ondas vem uma GRANDE HORDA.\nEntre as ondas você recupera vida e ganha itens. Use a mochila, granadas e construções a seu favor!", 12f, Palette.text).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(0, app.dpi(4), 0, app.dpi(8))
        })
        val s = app.progress.stats
        val record = Ui.row(app)
        fun stat(label: String, value: String) = Ui.column(app).apply {
            background = PanelDrawable(app, Color.argb(120, 0, 0, 0), Color.argb(40, 255, 255, 255), 8f)
            setPadding(app.dpi(10), app.dpi(6), app.dpi(10), app.dpi(6))
            addView(Ui.text(app, label, 9f, Palette.muted, Fonts.title))
            addView(Ui.title(app, value, 15f))
        }
        record.addView(stat("RECORDE", formatTime(s.bestSurvivalSeconds)), Ui.lp(0, Ui.WRAP, 1f))
        record.addView(android.view.View(app), Ui.lp(app.dpi(6), 1))
        record.addView(stat("MELHOR ONDA", "${s.bestWave}"), Ui.lp(0, Ui.WRAP, 1f))
        record.addView(android.view.View(app), Ui.lp(app.dpi(6), 1))
        record.addView(stat("PARTIDAS", "${s.survivalRuns}"), Ui.lp(0, Ui.WRAP, 1f))
        left.addView(record)
        lateinit var boost: TextView
        fun refreshBoost() {
            boost.text = if (app.boostReady) "BÔNUS ATIVO ✓" else "▶ +30% VIDA E +20% DANO (anúncio)"
            Ui.setEnabledLook(boost, !app.boostReady)
        }
        boost = Ui.button(app, "", Palette.cyan, Palette.dark, 12f) {
            if (!app.boostReady) app.watchAd {
                app.boostReady = true
                app.audio.playUi("reward", 1f)
                refreshBoost()
            }
        }
        refreshBoost()
        left.addView(boost, Ui.lp(Ui.MATCH, app.dpi(44)).apply { topMargin = app.dpi(10) })
        val buttons = Ui.row(app)
        buttons.addView(Ui.button(app, "🎒 MOCHILA", Palette.stone, Color.WHITE, 13f) { app.showInventory(0) { app.showSurvival() } }, Ui.lp(0, app.dpi(54), 0.8f).apply { marginEnd = app.dpi(8) })
        val play = Ui.button(app, "SOBREVIVER ▶", Palette.lime, Palette.dark, 18f) { app.startSurvival() }
        buttons.addView(play, Ui.lp(0, app.dpi(54), 1.2f))
        left.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(8) })
        Ui.pulse(play)
        row.addView(ScrollView(app).apply { addView(left) }, Ui.lp(0, Ui.MATCH, 1f).apply { marginEnd = app.dpi(12) })

        val right = Ui.panel(app)
        right.addView(Ui.text(app, "🏆 RANKING • QUEM SOBREVIVEU MAIS TEMPO", 10f, Palette.gold, Fonts.title))
        right.addView(ScrollView(app).apply { addView(rankingTable(app, Rules.survivalRanking(app.progress), null)) }, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = app.dpi(6) })
        right.addView(Ui.text(app, "Ranking dos jogadores deste aparelho.", 10f, Palette.muted).apply { setPadding(0, app.dpi(4), 0, 0) })
        row.addView(right, Ui.lp(0, Ui.MATCH, 1.25f))
        column.addView(row, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(20), app.dpi(10), app.dpi(20), app.dpi(14)) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
    }
}

/** Morreu na sobrevivência: reviver com anúncio (uma vez) ou encerrar e ver o ranking. */
@SuppressLint("ViewConstructor")
class SurvivalDefeatOverlay(app: MainActivity, wave: Int, seconds: Int, canRevive: Boolean) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(170, 40, 4, 8))
        isClickable = true
        val panel = Ui.panel(app, Palette.panel, Color.argb(120, 255, 90, 90))
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.addView(Ui.title(app, "A HORDA TE PEGOU!", 26f, Palette.red))
        panel.addView(Ui.text(app, "Você sobreviveu ${formatTime(seconds)} e chegou à onda $wave.", 13f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, app.dpi(6), 0, app.dpi(14))
        })
        if (canRevive) {
            panel.addView(Ui.button(app, "▶ REVIVER E CONTINUAR (anúncio)", Palette.cyan, Palette.dark, 14f) { app.reviveWithAd() }, Ui.lp(Ui.MATCH, app.dpi(50)).apply { bottomMargin = app.dpi(8) })
        }
        panel.addView(Ui.button(app, "VER RESULTADO", Palette.lime, Palette.dark, 15f) { app.finishSurvival() }, Ui.lp(Ui.MATCH, app.dpi(48)))
        addView(panel, Ui.frame(app.dpi(400), Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
    }
}

/** Resultado da sobrevivência: tempo, onda, pontos, recompensa e posição no ranking. */
@SuppressLint("ViewConstructor")
class SurvivalResultOverlay(
    app: MainActivity,
    result: SurvivalResult,
    summary: SurvivalSummary,
    ranking: List<SurvivalRecord>,
    mine: SurvivalRecord?,
) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(200, 4, 7, 14))
        isClickable = true
        val panel = Ui.panel(app)
        val row = Ui.row(app, Gravity.TOP)
        val left = Ui.column(app)
        left.addView(Ui.text(app, if (summary.newRecord) "NOVO RECORDE!" else "FIM DA SOBREVIVÊNCIA", 11f, if (summary.newRecord) Palette.gold else Palette.lime, Fonts.title))
        left.addView(Ui.title(app, formatTime(result.seconds.toInt()), 34f, Palette.gold))
        left.addView(Ui.text(app, "Onda ${result.wave} • ☠ ${result.kills} abates • ${result.headshots} na cabeça", 12f, Palette.text).apply { setPadding(0, app.dpi(2), 0, 0) })
        left.addView(Ui.text(app, "★ ${result.score} pontos", 15f, Palette.cyan, Fonts.title).apply { setPadding(0, app.dpi(6), 0, 0) })
        if (summary.rank > 0) left.addView(Ui.text(app, "${summary.rank}º lugar no ranking!", 14f, Palette.lime, Fonts.title).apply { setPadding(0, app.dpi(4), 0, 0) })
        val rewardText = Ui.title(app, "◆ ${summary.reward}", 18f, Palette.cyan)
        val rewardRow = Ui.row(app).apply { setPadding(0, app.dpi(8), 0, app.dpi(4)) }
        rewardRow.addView(Ui.text(app, "Recompensa ", 13f, Palette.text))
        rewardRow.addView(rewardText)
        left.addView(rewardRow)
        val buttons = Ui.column(app)
        if (summary.reward > 0) {
            lateinit var double: TextView
            double = Ui.button(app, "▶ DOBRAR ◆${summary.reward}", Palette.cyan, Palette.dark, 13f) {
                app.doubleRewardWithAd(summary.reward) {
                    rewardText.text = "◆ ${summary.reward * 2}"
                    Ui.setEnabledLook(double, false)
                    double.text = "DOBRADO ✓"
                }
            }
            buttons.addView(double, Ui.lp(Ui.MATCH, app.dpi(46)).apply { bottomMargin = app.dpi(6) })
        }
        buttons.addView(Ui.button(app, "JOGAR DE NOVO ▶", Palette.lime, Palette.dark, 15f) { app.leaveSurvival(again = true) }, Ui.lp(Ui.MATCH, app.dpi(48)).apply { bottomMargin = app.dpi(6) })
        buttons.addView(Ui.button(app, "MENU", Palette.stone, Color.WHITE, 13f) { app.leaveSurvival(again = false) }, Ui.lp(Ui.MATCH, app.dpi(44)))
        left.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(6) })
        row.addView(left, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = app.dpi(14) })
        val right = Ui.column(app)
        right.addView(Ui.text(app, "🏆 RANKING", 11f, Palette.gold, Fonts.title))
        right.addView(ScrollView(app).apply { addView(rankingTable(app, ranking, mine)) }, Ui.lp(Ui.MATCH, app.dpi(250)))
        row.addView(right, Ui.lp(0, Ui.WRAP, 1.4f))
        panel.addView(row)
        addView(panel, Ui.frame(app.dpi(680), Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
    }
}
