package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fabiosilva.ecosdeeldra.BuildConfig
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.ContractOption
import com.fabiosilva.ecosdeeldra.data.Contracts
import com.fabiosilva.ecosdeeldra.data.ExpeditionCode
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.ExpeditionOutcome
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import com.fabiosilva.ecosdeeldra.data.Inventory
import com.fabiosilva.ecosdeeldra.data.Items
import com.fabiosilva.ecosdeeldra.engine.NativeBridge

/** Ícone da identidade (zz_ic_*) como View tingida. */
class BrandIconView(context: Context, private val name: String, private val tint: Int) : View(context) {
    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        BrandIcons.draw(canvas, context, name, (width - s) / 2f, (height - s) / 2f, s, tint)
    }
}

/** Nome do ícone de cada contrato (docs/brand/IDENTIDADE.md, seção 7). */
fun contractIconName(id: ContractId): String = when (id) {
    ContractId.NONE -> BrandIcons.PORTAL_EXTRACAO
    ContractId.SILENT_RECOVERY -> BrandIcons.RECUPERACAO_SILENCIOSA
    ContractId.UNSTABLE_NEST -> BrandIcons.NINHO_INSTAVEL
    ContractId.RED_NIGHT -> BrandIcons.NOITE_VERMELHA
    ContractId.LOST_CRATE -> BrandIcons.CAIXA_PERDIDA
    ContractId.ENDURANCE -> BrandIcons.RESISTENCIA
}

/** Cor do contrato (token Palette.contract*); a expedição padrão usa o texto claro. */
fun contractColor(id: ContractId): Int = when (id) {
    ContractId.NONE -> Palette.text
    ContractId.SILENT_RECOVERY -> Palette.contractSilent
    ContractId.UNSTABLE_NEST -> Palette.contractNest
    ContractId.RED_NIGHT -> Palette.contractRedNight
    ContractId.LOST_CRATE -> Palette.contractCrate
    ContractId.ENDURANCE -> Palette.contractEndurance
}

/**
 * PREPARAR EXPEDIÇÃO: modo (campanha / sobrevivência), contrato com custo e recompensa, equipamento e código da ilha.
 * Nenhum anúncio aqui: escolher e iniciar um contrato é sempre grátis, e o bônus do contrato nunca é dobrado.
 */
@SuppressLint("ViewConstructor")
class ExpeditionPrepScreen(
    private val app: MainActivity,
    kind: ExpeditionKind,
    stage: Int,
    initial: ExpeditionPlan?,
    private val countPlayTap: Boolean,
    private val back: () -> Unit,
) : FrameLayout(app) {
    private var campaignPlan: ExpeditionPlan? = null
    private var survivalPlan: ExpeditionPlan? = null
    private var plan: ExpeditionPlan
    private var codeOverlay: View? = null

    private val tabCampaign: TextView
    private val tabSurvival: TextView
    private val missionBox = Ui.column(app)
    private val listBox = Ui.column(app)
    private val detailBox = Ui.column(app)
    private val startButton: TextView

    init {
        val p = app.progress
        when {
            initial != null -> {
                plan = initial
                if (initial.kind == ExpeditionKind.CAMPAIGN) campaignPlan = initial else survivalPlan = initial
            }
            kind == ExpeditionKind.CAMPAIGN -> {
                plan = ExpeditionPlan.standard(stage).copy(contract = Contracts.defaultContract(ExpeditionKind.CAMPAIGN, p))
                campaignPlan = plan
            }
            else -> {
                plan = ExpeditionPlan.survival(Contracts.defaultContract(ExpeditionKind.SURVIVAL, p))
                survivalPlan = plan
            }
        }
        setBackgroundColor(Color.argb(170, 6, 10, 20))
        Ui.blockTouches(this)
        val column = Ui.column(app)
        column.addView(Header.build(app, "PREPARAR EXPEDIÇÃO", "ESCOLHA O CONTRATO • ASSUMA O RISCO") { goBack() })

        val tabs = Ui.row(app)
        tabCampaign = tab { switchKind(ExpeditionKind.CAMPAIGN) }
        tabSurvival = tab { switchKind(ExpeditionKind.SURVIVAL) }
        tabs.addView(tabCampaign, Ui.lp(0, app.dpi(40), 1f).apply { marginEnd = app.dpi(8) })
        tabs.addView(tabSurvival, Ui.lp(0, app.dpi(40), 1f).apply { marginEnd = app.dpi(8) })
        tabs.addView(Ui.button(app, "RANKING", Palette.stone, Color.WHITE, 12f) { app.showSurvival() }, Ui.lp(app.dpi(110), app.dpi(40)))
        column.addView(tabs, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(app.dpi(20), app.dpi(8), app.dpi(20), 0) })

        val body = Ui.row(app, Gravity.TOP)
        val left = Ui.column(app)
        left.addView(missionBox)
        left.addView(Ui.text(app, "CONTRATO", 11f, Palette.lime, Fonts.title).apply { setPadding(0, app.dpi(10), 0, app.dpi(4)) })
        left.addView(listBox)
        body.addView(ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(left) }, Ui.lp(0, Ui.MATCH, 1f).apply { marginEnd = app.dpi(12) })
        val right = Ui.panel(app)
        right.addView(detailBox)
        body.addView(ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(right) }, Ui.lp(0, Ui.MATCH, 1.3f))
        column.addView(body, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(20), app.dpi(8), app.dpi(20), app.dpi(6)) })

        val bottom = Ui.row(app)
        startButton = Ui.button(app, "PARTIR ▶", Palette.lime, Palette.dark, 20f) { app.startExpedition(plan, countPlayTap) }
        bottom.addView(startButton, Ui.lp(app.dpi(230), app.dpi(54)))
        bottom.addView(
            Ui.text(app, "Contratos são grátis: sem anúncio para escolher ou receber. O bônus do contrato não dobra.", 11f, Palette.muted).apply {
                setPadding(app.dpi(14), 0, 0, 0)
            },
            Ui.lp(0, Ui.WRAP, 1f),
        )
        column.addView(bottom, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(app.dpi(20), 0, app.dpi(20), app.dpi(12)) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        Ui.pulse(startButton)
        if (BuildConfig.DEBUG) runContractSelfTest()
        refresh()
    }

    /** Só em builds de depuração: autoteste da reserva de munição (resultado no logcat, tag ContractSelfTest). */
    private fun runContractSelfTest() {
        runCatching {
            val r = NativeBridge.contractSelfTest()
            if (r == 0) android.util.Log.i("ContractSelfTest", "reserva de munição: OK")
            else android.util.Log.e("ContractSelfTest", "FALHOU: código $r (linha*1000+falhas em contract_rules.h)")
        }.onFailure { android.util.Log.w("ContractSelfTest", "indisponível: ${it.message}") }
    }

    fun goBack() {
        if (codeOverlay != null) closeCodeEntry() else back()
    }

    private fun tab(onClick: () -> Unit): TextView = Ui.button(app, "", Palette.stone, Color.WHITE, 13f, onClick)

    private fun switchKind(kind: ExpeditionKind) {
        if (plan.kind == kind) return
        if (plan.kind == ExpeditionKind.CAMPAIGN) campaignPlan = plan else survivalPlan = plan
        val p = app.progress
        plan = if (kind == ExpeditionKind.CAMPAIGN) {
            campaignPlan ?: ExpeditionPlan.standard(p.frontierStage()).copy(contract = Contracts.defaultContract(kind, p))
        } else {
            survivalPlan ?: ExpeditionPlan.survival(Contracts.defaultContract(kind, p))
        }
        refresh()
    }

    private fun refresh() {
        val p = app.progress
        val isCampaign = plan.kind == ExpeditionKind.CAMPAIGN
        val stageNumber = campaignPlan?.stage ?: plan.stage
        styleTab(tabCampaign, "CAMPANHA • FASE ${if (isCampaign) plan.stage else stageNumber}", isCampaign)
        styleTab(tabSurvival, "SOBREVIVÊNCIA", !isCampaign)
        buildMission()
        buildList(p)
        buildDetail(p)
    }

    private fun styleTab(view: TextView, label: String, active: Boolean) {
        view.text = label
        view.background = BlockDrawable(app, if (active) Palette.lime else Palette.stone)
        view.setTextColor(if (active) Palette.dark else Color.WHITE)
    }

    // ------------------------------------------------------------------ cartão da missão

    private fun buildMission() {
        missionBox.removeAllViews()
        val p = app.progress
        val card = Ui.column(app).apply {
            background = PanelDrawable(app, Color.argb(120, 0, 0, 0), Color.argb(40, 255, 255, 255), 8f)
            setPadding(app.dpi(10), app.dpi(8), app.dpi(10), app.dpi(8))
        }
        val zone = Campaign.zone(plan.stage)
        if (plan.kind == ExpeditionKind.CAMPAIGN) {
            val st = Campaign.stage(plan.stage)
            val diff = Contracts.engineRules(plan.contract, plan.stage).applyTo(Campaign.difficulty(st.depth, p))
            card.addView(Ui.text(app, "ZONA ${zone.index + 1} • ${zone.name.uppercase()}", 11f, zone.color, Fonts.title))
            card.addView(Ui.title(app, "Fase ${st.depth} · ${st.title}", 16f).apply { setPadding(0, app.dpi(2), 0, app.dpi(3)) })
            card.addView(Ui.text(app, "ILHA ${st.islandKm} • 3 NINHOS • PORTAL", 11f, Palette.text, Fonts.bold))
            card.addView(Ui.text(app, "Horda: ${diff.zombieCount} zumbis por perto • vida deles ${diff.zombieHealth.toInt()}", 11f, Palette.muted))
        } else {
            card.addView(Ui.text(app, "SOBREVIVÊNCIA • ONDAS INFINITAS", 11f, Palette.gold, Fonts.title))
            card.addView(Ui.title(app, "Ilha de ${zone.name}", 16f).apply { setPadding(0, app.dpi(2), 0, app.dpi(3)) })
            card.addView(Ui.text(app, "Sem ninhos nem portal: os zumbis chegam em ondas e a cada 5 ondas vem uma GRANDE HORDA.", 11f, Palette.muted))
        }
        missionBox.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    // ------------------------------------------------------------------ lista de contratos

    private fun buildList(p: com.fabiosilva.ecosdeeldra.data.Progress) {
        listBox.removeAllViews()
        for (opt in Contracts.options(plan.kind, p)) listBox.addView(contractRow(opt), Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = app.dpi(6) })
    }

    private fun contractRow(opt: ContractOption): View {
        val def = opt.def
        val selected = def.id == plan.contract
        val color = contractColor(def.id)
        val row = Ui.row(app).apply {
            background = PanelDrawable(app, if (selected) Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)) else Color.argb(110, 0, 0, 0), if (selected) color else Color.argb(50, 255, 255, 255), 8f)
            setPadding(app.dpi(8), app.dpi(6), app.dpi(8), app.dpi(6))
            isClickable = true
            alpha = if (opt.locked) 0.55f else 1f
        }
        row.addView(BrandIconView(app, contractIconName(def.id), if (opt.locked) Palette.muted else color), Ui.lp(app.dpi(32), app.dpi(32)).apply { marginEnd = app.dpi(8) })
        val texts = Ui.column(app)
        val head = Ui.row(app)
        head.addView(Ui.text(app, def.name.uppercase(), 12f, if (opt.locked) Palette.muted else Color.WHITE, Fonts.title), Ui.lp(0, Ui.WRAP, 1f))
        head.addView(Ui.text(app, "●".repeat(def.danger) + "○".repeat((3 - def.danger).coerceAtLeast(0)), 11f, Palette.danger))
        texts.addView(head)
        val card = Contracts.card(def.id, plan.kind, plan.stage)
        texts.addView(Ui.text(app, if (opt.locked) "🔒 ${opt.lockReason}" else card.summary, 10.5f, if (opt.locked) Palette.gold else Palette.muted))
        row.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        row.setOnClickListener {
            Ui.clickSound?.invoke()
            if (opt.locked) {
                Popup.show(app, "Contrato bloqueado", "${def.name}: ${opt.lockReason}. Continue vencendo fases.", "OK")
            } else {
                plan = plan.copy(contract = def.id)
                refresh()
            }
        }
        return row
    }

    // ------------------------------------------------------------------ detalhes do contrato

    private fun section(title: String, color: Int) {
        detailBox.addView(Ui.text(app, title, 10f, color, Fonts.title).apply { setPadding(0, app.dpi(8), 0, app.dpi(2)) })
    }

    private fun line(prefix: String, text: String, color: Int) {
        detailBox.addView(Ui.text(app, "$prefix $text", 12f, Palette.text).apply {
            setLineSpacing(0f, 1.1f)
            val span = android.text.SpannableString(this.text)
            span.setSpan(android.text.style.ForegroundColorSpan(color), 0, prefix.length, 0)
            this.text = span
        })
    }

    private fun buildDetail(p: com.fabiosilva.ecosdeeldra.data.Progress) {
        detailBox.removeAllViews()
        val def = Contracts.get(plan.contract)
        val color = contractColor(def.id)
        val card = Contracts.card(def.id, plan.kind, plan.stage)
        val head = Ui.row(app)
        head.addView(BrandIconView(app, contractIconName(def.id), color), Ui.lp(app.dpi(30), app.dpi(30)).apply { marginEnd = app.dpi(8) })
        val names = Ui.column(app)
        names.addView(Ui.title(app, def.name.uppercase(), 17f, color))
        names.addView(Ui.text(app, card.tagline, 11f, Palette.muted))
        head.addView(names, Ui.lp(0, Ui.WRAP, 1f))
        detailBox.addView(head)

        if (card.risks.isNotEmpty()) {
            section("CUSTO / RISCO", Palette.danger)
            card.risks.forEach { line("▸", it, Palette.danger) }
        }
        section("RECOMPENSA", Palette.cyan)
        card.rewards.forEach { line("+", it, Palette.cyan) }
        card.objective?.let {
            section("OBJETIVO OPCIONAL", Palette.gold)
            line("★", it, Palette.gold)
        }

        section("EQUIPAMENTO", Palette.lime)
        val bar = Ui.row(app)
        for (i in 0 until Inventory.HOTBAR) {
            val slot = p.inventory[i]
            val cell = FrameLayout(app).apply { background = PanelDrawable(app, Color.argb(120, 0, 0, 0), Color.argb(60, 255, 255, 255), 6f) }
            if (!slot.empty) {
                cell.addView(ItemIconView(app, slot.id), Ui.frame(Ui.MATCH, Ui.MATCH).apply { setMargins(app.dpi(3), app.dpi(3), app.dpi(3), app.dpi(3)) })
                if (slot.count > 1) cell.addView(Ui.text(app, "${slot.count}", 9f, Color.WHITE, Fonts.title), Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, app.dpi(3), app.dpi(1)) })
            }
            bar.addView(cell, Ui.lp(app.dpi(34), app.dpi(34)).apply { marginEnd = app.dpi(4) })
        }
        bar.addView(Ui.button(app, "MOCHILA", Palette.stone, Color.WHITE, 11f) {
            val current = plan
            app.showInventory(0) { app.showExpeditionPrep(current.kind, current.stage, current, countPlayTap, back) }
        }, Ui.lp(0, app.dpi(34), 1f).apply { marginStart = app.dpi(4) })
        detailBox.addView(bar)
        val weapon = Items.get(p.inventory[p.inventory.weaponSlot.coerceIn(0, Inventory.SIZE - 1)].id)
        detailBox.addView(Ui.text(app, "Arma: ${weapon?.name ?: "fuzil"}", 11f, Palette.muted).apply { setPadding(0, app.dpi(3), 0, 0) })
        Contracts.loadoutAdvice(def.id, p.inventory)?.let { detailBox.addView(Ui.text(app, it, 11f, Palette.gold).apply { setPadding(0, app.dpi(2), 0, 0) }) }

        val code = plan.code
        section("ILHA", Palette.lime)
        if (code != null) {
            detailBox.addView(Ui.text(app, code, 16f, Palette.text, Fonts.title).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        } else {
            detailBox.addView(Ui.text(app, "Esta fase é alta demais para ter código.", 11f, Palette.muted))
        }
        detailBox.addView(Ui.text(app, if (plan.sharedSeed) "Ilha de um código compartilhado: tentar de novo mantém a ilha." else "Mesma ilha e regras para quem digitar o código; suas melhorias contam.", 10.5f, Palette.muted))
        val buttons = Ui.row(app)
        if (code != null) {
            buttons.addView(Ui.button(app, "COPIAR", Palette.stone, Color.WHITE, 11f) { copyCode(code) }, Ui.lp(0, app.dpi(36), 1f).apply { marginEnd = app.dpi(6) })
        }
        buttons.addView(Ui.button(app, "NOVA ILHA", Palette.stone, Color.WHITE, 11f) { newIsland() }, Ui.lp(0, app.dpi(36), 1f).apply { marginEnd = app.dpi(6) })
        buttons.addView(Ui.button(app, "CÓDIGO…", Palette.cyan, Palette.dark, 11f) { openCodeEntry() }, Ui.lp(0, app.dpi(36), 1f))
        detailBox.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(4) })

        historyLine(def.id)?.let { detailBox.addView(Ui.text(app, it, 11f, Palette.muted).apply { setPadding(0, app.dpi(8), 0, 0) }) }
    }

    private fun historyLine(id: ContractId): String? {
        val log = app.progress.expeditions
        val plays = log.playsOf(id)
        if (plays == 0) return null
        val last = log.lastOf(id)
        val tail = last?.let {
            val day = it.day.takeIf { d -> d.length >= 10 }?.let { d -> "${d.substring(8, 10)}/${d.substring(5, 7)}" } ?: ""
            val result = if (it.outcome == ExpeditionOutcome.VICTORY) "◆ +${it.totalDiamonds}" else "derrota"
            " • último: $result" + if (day.isNotEmpty()) " em $day" else ""
        }.orEmpty()
        return "Histórico: jogado ${plays}x • vencido ${log.winsOf(id)}x$tail"
    }

    private fun copyCode(code: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Zombie Zone", code))
        Toast.makeText(app, "Código da ilha copiado.", Toast.LENGTH_SHORT).show()
    }

    private fun newIsland() {
        plan = if (plan.kind == ExpeditionKind.SURVIVAL) {
            ExpeditionPlan.survival(plan.contract)   // também sorteia outro bioma
        } else {
            plan.copy(seed = Contracts.randomSeed(), sharedSeed = false)
        }
        refresh()
    }

    // ------------------------------------------------------------------ código da ilha

    private fun openCodeEntry() {
        if (codeOverlay != null) return
        val overlay = CodeEntryOverlay(app, onCancel = { closeCodeEntry() }) { decoded ->
            closeCodeEntry()
            if (plan.kind == ExpeditionKind.CAMPAIGN) campaignPlan = plan else survivalPlan = plan
            plan = decoded
            if (decoded.kind == ExpeditionKind.CAMPAIGN) campaignPlan = decoded else survivalPlan = decoded
            refresh()
            Toast.makeText(app, "Código aceito.", Toast.LENGTH_SHORT).show()
        }
        codeOverlay = overlay
        addView(overlay, Ui.frame(Ui.MATCH, Ui.MATCH))
    }

    private fun closeCodeEntry() {
        codeOverlay?.let { removeView(it) }
        codeOverlay = null
    }
}

/** Campo para digitar ou colar o código de uma ilha. Valida o formato e a progressão antes de aceitar. */
@SuppressLint("ViewConstructor")
class CodeEntryOverlay(
    private val app: MainActivity,
    onCancel: () -> Unit,
    onAccepted: (ExpeditionPlan) -> Unit,
) : FrameLayout(app) {
    private val input = EditText(app)
    private val error = Ui.text(app, "", 12f, Palette.danger)

    init {
        setBackgroundColor(Color.argb(210, 4, 7, 14))
        isClickable = true
        val panel = Ui.panel(app)
        panel.addView(Ui.title(app, "CÓDIGO DA ILHA", 18f, Palette.lime))
        panel.addView(Ui.text(app, "Cole ou digite o código ZZ-XXXX-XXXX-XXXXX para jogar a mesma ilha e as mesmas regras.", 12f, Palette.muted).apply { setPadding(0, app.dpi(4), 0, app.dpi(8)) })
        input.apply {
            hint = "ZZ-XXXX-XXXX-XXXXX"
            setHintTextColor(Palette.muted)
            setTextColor(Palette.text)
            textSize = 18f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            background = PanelDrawable(app, Color.argb(160, 0, 0, 0), Palette.lineStrong, 8f)
            setPadding(app.dpi(12), app.dpi(8), app.dpi(12), app.dpi(8))
        }
        panel.addView(input, Ui.lp(Ui.MATCH, app.dpi(48)))
        panel.addView(error, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(4) })
        val row = Ui.row(app, Gravity.CENTER)
        row.addView(Ui.button(app, "COLAR", Palette.stone, Color.WHITE, 13f) { paste() }, Ui.lp(0, app.dpi(44), 1f).apply { marginEnd = app.dpi(8) })
        row.addView(Ui.button(app, "CANCELAR", Palette.stone, Color.WHITE, 13f) { onCancel() }, Ui.lp(0, app.dpi(44), 1f).apply { marginEnd = app.dpi(8) })
        row.addView(Ui.button(app, "USAR", Palette.lime, Palette.dark, 14f) { submit(onAccepted) }, Ui.lp(0, app.dpi(44), 1f))
        panel.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(10) })
        addView(panel, Ui.frame(app.dpi(440), Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
    }

    private fun paste() {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
        if (text.isBlank()) {
            error.text = "A área de transferência está vazia."
            return
        }
        input.setText(ExpeditionCode.extract(text) ?: text.trim())
        input.setSelection(input.text.length)
        error.text = ""
    }

    private fun submit(onAccepted: (ExpeditionPlan) -> Unit) {
        val text = input.text.toString()
        when (val d = ExpeditionCode.decode(ExpeditionCode.extract(text) ?: text)) {
            is ExpeditionCode.Decoded.Bad -> error.text = ExpeditionCode.message(d.problem)
            is ExpeditionCode.Decoded.Ok -> {
                val check = Contracts.check(d.plan, app.progress)
                if (!check.ok) error.text = check.message.orEmpty() else onAccepted(d.plan)
            }
        }
    }
}
