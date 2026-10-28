package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.ContractReward
import com.fabiosilva.ecosdeeldra.data.Contracts
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import com.fabiosilva.ecosdeeldra.data.ExpeditionSummary
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.Stage

/** ScrollView que nunca passa de [maxPx] de altura (o painel de resultado cabe em telas baixas). */
class CappedScrollView(context: Context, private val maxPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST))
    }
}

/** Chip do contrato (ícone + nome na cor do contrato). */
fun contractChip(app: MainActivity, id: ContractId): View {
    val def = Contracts.get(id)
    val color = contractColor(id)
    return Ui.row(app).apply {
        background = PanelDrawable(app, Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)), color, 12f)
        setPadding(app.dpi(8), app.dpi(4), app.dpi(10), app.dpi(4))
        addView(BrandIconView(app, contractIconName(id), color), Ui.lp(app.dpi(18), app.dpi(18)).apply { marginEnd = app.dpi(5) })
        addView(Ui.text(app, def.name.uppercase(), 11f, color, Fonts.title))
    }
}

/** Linha "ILHA: ZZ-… [COPIAR]" usada nos resultados. Devolve null quando a fase não cabe em código. */
fun islandCodeRow(app: MainActivity, plan: ExpeditionPlan): View? {
    val code = plan.code ?: return null
    return Ui.row(app).apply {
        addView(Ui.text(app, "ILHA ", 11f, Palette.muted, Fonts.title))
        addView(Ui.text(app, code, 13f, Palette.text, Fonts.title).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        addView(Ui.button(app, "COPIAR", Palette.stone, Color.WHITE, 10f) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Zombie Zone", code))
            Toast.makeText(app, "Código da ilha copiado.", Toast.LENGTH_SHORT).show()
        }, Ui.lp(app.dpi(70), app.dpi(28)).apply { marginStart = app.dpi(8) })
    }
}

/**
 * Resultado da expedição (vitória): objetivos cumpridos x pedidos, recompensa em duas colunas (EXPEDIÇÃO / CONTRATO),
 * código da ilha. O botão DOBRAR dobra SÓ a parte base; o bônus do contrato nunca é dobrado.
 */
@SuppressLint("ViewConstructor")
class ExpeditionResultOverlay(
    private val app: MainActivity,
    plan: ExpeditionPlan,
    stage: Stage,
    result: MissionResult,
    summary: ExpeditionSummary,
) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(190, 4, 7, 14))
        isClickable = true
        val base = summary.base
        val contract = summary.contract
        val hasContract = plan.contract != ContractId.NONE
        val panel = Ui.panel(app)

        val scroll = CappedScrollView(app, resources.displayMetrics.heightPixels - app.dpi(110)).apply { isVerticalScrollBarEnabled = false }
        val content = Ui.column(app)

        // Cabeçalho
        val header = Ui.row(app, Gravity.TOP)
        val titles = Ui.column(app)
        titles.addView(Ui.text(app, "EXPEDIÇÃO CONCLUÍDA • FASE ${stage.depth}", 11f, Palette.lime, Fonts.title))
        titles.addView(Ui.title(app, if (hasContract) "EXTRAÍDO!" else "VITÓRIA!", 28f, Palette.gold))
        header.addView(titles, Ui.lp(0, Ui.WRAP, 1f))
        val tags = Ui.column(app).apply { gravity = Gravity.END }
        if (hasContract) tags.addView(contractChip(app, plan.contract))
        islandCodeRow(app, plan)?.let { tags.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = app.dpi(4) }) }
        header.addView(tags, Ui.lp(Ui.WRAP, Ui.WRAP))
        content.addView(header)

        // Objetivos (✓ cumprido / ✗ não cumprido)
        val columns = Ui.row(app, Gravity.TOP)
        val goals = Ui.column(app)
        val nestTotal = stage.crystalTotal
        val crystals = Integer.bitCount(result.crystalMask and 0b111)
        fun goal(ok: Boolean, text: String) = goals.addView(Ui.text(app, (if (ok) "✓ " else "✗ ") + text, 12f, if (ok) Palette.lime else Palette.muted))
        goals.addView(Ui.text(app, "OBJETIVOS", 10f, Palette.muted, Fonts.title).apply { setPadding(0, app.dpi(6), 0, app.dpi(2)) })
        goal(result.nests >= nestTotal, "Ninhos destruídos ${result.nests.coerceAtMost(nestTotal)}/$nestTotal")
        goal(crystals >= nestTotal, "Cristais recolhidos $crystals/$nestTotal")
        goal(true, "Extraído pelo portal")
        val labels = listOf("Vencer a fase", "Terminar com vida ≥ 50%", "Tempo ≤ %d:%02d".format(stage.targetSeconds / 60, stage.targetSeconds % 60))
        val stars = Ui.row(app)
        repeat(3) { i ->
            val on = i < base.stars
            val star = Ui.title(app, if (on) "★" else "☆", 30f, if (on) Palette.gold else Palette.muted)
            stars.addView(star)
            star.alpha = 0f
            star.scaleX = 0.3f
            star.scaleY = 0.3f
            star.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(250L + i * 280L).setDuration(260).start()
        }
        goals.addView(stars, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = app.dpi(4) })
        base.conditions.forEachIndexed { i, ok -> goal(ok, labels[i]) }
        when (plan.contract) {
            ContractId.SILENT_RECOVERY ->
                goals.addView(Ui.text(app, "Silêncio: ${summary.record.alerts} zumbis alertados ⇒ ◆ +${contract.objective}", 12f, if (contract.objective > 0) Palette.contractSilent else Palette.muted).apply { setPadding(0, app.dpi(4), 0, 0) })
            ContractId.RED_NIGHT ->
                goals.addView(Ui.text(app, "Noite vermelha concluída", 12f, Palette.contractRedNight).apply { setPadding(0, app.dpi(4), 0, 0) })
            ContractId.UNSTABLE_NEST ->
                goals.addView(Ui.text(app, if (contract.moduleId != null) "Núcleo instável recuperado" else "Nenhum módulo: faltou destruir ninhos", 12f, Palette.contractNest).apply { setPadding(0, app.dpi(4), 0, 0) })
            ContractId.LOST_CRATE ->
                goals.addView(Ui.text(app, if (contract.objective > 0) "Carga entregue ⇒ ◆ +${contract.objective}" else "Carga não encontrada", 12f, Palette.contractCrate).apply { setPadding(0, app.dpi(4), 0, 0) })
            else -> Unit
        }
        columns.addView(goals, Ui.lp(0, Ui.WRAP, 1f))

        // Recompensa em duas colunas
        val rewards = Ui.column(app)
        val totalText = Ui.title(app, "◆ ${summary.total}", 20f, Palette.cyan)
        fun reward(into: LinearLayout, label: String, value: Int) {
            if (value <= 0) return
            val row = Ui.row(app)
            row.addView(Ui.text(app, label, 12f, Palette.text), Ui.lp(0, Ui.WRAP, 1f))
            row.addView(Ui.text(app, "◆ $value", 12f, Palette.cyan, Fonts.title))
            into.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(2) })
        }
        val split = Ui.row(app, Gravity.TOP)
        val expedition = Ui.column(app)
        expedition.addView(Ui.text(app, "EXPEDIÇÃO", 10f, Palette.muted, Fonts.title))
        reward(expedition, "Primeira vitória", base.firstClear)
        reward(expedition, "Estrelas novas", base.starBonus)
        reward(expedition, "Cristais novos (${base.newCrystals})", base.crystalBonus)
        reward(expedition, "Zumbis abatidos", base.killBonus)
        reward(expedition, "Baús (${result.chests})", base.chestBonus)
        reward(expedition, "Exploração", base.exploreBonus)
        split.addView(expedition, Ui.lp(0, Ui.WRAP, 1f))
        if (hasContract) {
            val col = Ui.column(app)
            col.addView(Ui.text(app, "CONTRATO", 10f, contractColor(plan.contract), Fonts.title))
            reward(col, "Ao extrair", contract.extraction)
            reward(col, "+${Contracts.get(plan.contract).reward.runPct}% do saque", contract.runBonus)
            reward(col, "Objetivo", contract.objective)
            contract.moduleId?.let { col.addView(Ui.text(app, "Módulo: ${Contracts.moduleName(it)}", 12f, Palette.gold, Fonts.title)) }
            if (contract.total == 0 && contract.moduleId == null) col.addView(Ui.text(app, "Sem bônus desta vez", 12f, Palette.muted))
            split.addView(col, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = app.dpi(12) })
        }
        rewards.addView(split)
        val totalRow = Ui.row(app)
        totalRow.addView(Ui.title(app, "TOTAL", 16f), Ui.lp(0, Ui.WRAP, 1f))
        totalRow.addView(totalText)
        rewards.addView(totalRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(6) })
        columns.addView(rewards, Ui.lp(0, Ui.WRAP, 1.3f).apply { marginStart = app.dpi(16) })
        content.addView(columns, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Resumo
        val time = result.elapsedSeconds.toInt()
        val accuracy = if (result.shots > 0) result.hits * 100 / result.shots else 0
        val km = if (result.distance >= 1000f) "%.1f km".format(result.distance / 1000f) else "${result.distance.toInt()} m"
        content.addView(Ui.text(app, "Tempo %d:%02d • Abates %d • Na cabeça %d • Precisão %d%% • Andou %s".format(time / 60, time % 60, result.kills, result.headshots, accuracy, km), 11f, Palette.muted).apply { setPadding(0, app.dpi(8), 0, 0) })
        scroll.addView(content)
        panel.addView(scroll, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Botões
        val buttons = Ui.row(app)
        if (summary.doublable > 0) {
            lateinit var double: TextView
            double = Ui.button(app, "▶ DOBRAR ◆${summary.doublable}", Palette.cyan, Palette.dark, 13f) {
                app.doubleRewardWithAd(summary.doublable) {
                    // Só a base dobra: o bônus do contrato fica como está.
                    totalText.text = "◆ ${summary.total + summary.doublable}"
                    Ui.setEnabledLook(double, false)
                    double.text = "DOBRADO ✓"
                }
            }
            val box = Ui.column(app)
            box.addView(double, Ui.lp(Ui.MATCH, app.dpi(46)))
            if (hasContract && contract.total > 0) box.addView(Ui.text(app, "o bônus do contrato não é dobrado", 9f, Palette.muted).apply { gravity = Gravity.CENTER })
            buttons.addView(box, Ui.lp(0, Ui.WRAP, 1.2f).apply { marginEnd = app.dpi(8) })
        }
        buttons.addView(Ui.button(app, "MAPA", Palette.stone, Color.WHITE, 14f) { app.leaveAfterVictory(next = false) }, Ui.lp(0, app.dpi(46), 0.8f).apply { marginEnd = app.dpi(8) })
        buttons.addView(Ui.button(app, "PRÓXIMA FASE ▶", Palette.lime, Palette.dark, 14f) { app.leaveAfterVictory(next = true) }, Ui.lp(0, app.dpi(46), 1.2f))
        panel.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(8) })

        val width = minOf(app.dpi(680), resources.displayMetrics.widthPixels - app.dpi(24))
        addView(panel, Ui.frame(width, Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
    }
}
