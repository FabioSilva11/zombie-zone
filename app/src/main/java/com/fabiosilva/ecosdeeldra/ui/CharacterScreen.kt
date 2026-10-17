package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Cosmetics
import com.fabiosilva.ecosdeeldra.data.Look
import com.fabiosilva.ecosdeeldra.data.LookOptions
import com.fabiosilva.ecosdeeldra.data.LookPainter
import com.fabiosilva.ecosdeeldra.data.LookPart
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.UnlockKind

/** Cores do editor: azul-marinho profundo com detalhes em verde-limão. */
private object EditorColors {
    val panel = Color.parseColor("#F2071522")
    val surface = Color.parseColor("#FF102338")
    val surfaceAlt = Color.parseColor("#FF0A1828")
    val card = Color.parseColor("#FF16293D")
    val border = Color.parseColor("#FF244158")
    val borderSoft = Color.parseColor("#FF1B3149")
    val lime = Color.parseColor("#9DFF32")
    val limeSoft = Color.parseColor("#FF1B3320")
    val confirm = Color.parseColor("#65D927")
    val text = Color.parseColor("#F4F7FB")
    val muted = Color.parseColor("#A8B8CB")
    val dark = Color.parseColor("#06210A")
}

/**
 * Editor de personagem: menu vertical fixo à esquerda (Skins, Cabelo, Rosto, Camisa, Calça e
 * Acessórios) com o conteúdo da categoria ao lado, e o herói 3D do motor à direita, dentro do
 * cenário do lobby. A prévia acompanha cada seleção na hora e continua usando o mesmo sistema do
 * [Progress]: equipar, desbloquear (conquistas, anúncios ou diamantes) e salvar são os de sempre.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CharacterScreen(private val app: MainActivity) : FrameLayout(app) {
    private enum class Category(val label: String, val icon: Icon, val parts: List<LookPart>) {
        SKINS("SKINS", Icon.SKIN, listOf(LookPart.SKIN)),
        HAIR("CABELO", Icon.HAIR, listOf(LookPart.HAIR_STYLE, LookPart.HAIR_COLOR)),
        FACE("ROSTO", Icon.FACE, listOf(LookPart.EYES, LookPart.FACE)),
        SHIRT("CAMISA", Icon.SHIRT, listOf(LookPart.TOP, LookPart.TOP_COLOR)),
        PANTS("CALÇA", Icon.PANTS, listOf(LookPart.SHORTS, LookPart.PANTS_COLOR, LookPart.SHOES)),
        EXTRAS("ACESSÓRIOS", Icon.ACCESSORY, listOf(LookPart.HAT)),
    }

    private val p: Progress = app.progress
    private var editing = p.selectedHero
    private var category = Category.SKINS

    private val rail = Ui.column(app)
    private val content = Ui.column(app)
    private val heroesRow = Ui.row(app)
    private val nameInput = EditText(app)
    private lateinit var resourcesRow: LinearLayout
    private lateinit var applyButton: TextView

    /** Como o editor foi aberto: o botão cancelar volta tudo a este ponto. */
    private val entryLooks = IntArray(p.heroes.size) { p.heroes[it].look }
    private val entryNames = p.heroes.map { it.name }
    private val entrySelected = p.selectedHero

    private val hero get() = p.heroes[editing]
    private val look get() = Look.decode(hero.look)

    init {
        // O herói é desenhado pelo motor atrás da tela: nada aqui pode ter fundo opaco.
        isClickable = true
        Ui.blockTouches(this)
        val row = Ui.row(app, Gravity.TOP)
        row.addView(
            buildPanel(),
            Ui.lp(0, Ui.MATCH, PANEL_WEIGHT).apply { setMargins(dpi(10), dpi(8), dpi(6), dpi(10)) },
        )
        row.addView(buildStage(), Ui.lp(0, Ui.MATCH, STAGE_WEIGHT))
        addView(row, Ui.frame(Ui.MATCH, Ui.MATCH))
        refreshAll()
    }

    override fun onDetachedFromWindow() {
        // Cobre também o botão voltar do sistema, que não passa por leave().
        saveName()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ estrutura

    private fun buildPanel(): View {
        val panel = Ui.column(app).apply {
            background = PanelDrawable(app, EditorColors.panel, EditorColors.border, 14f)
            setPadding(dpi(12), dpi(8), dpi(12), dpi(10))
        }
        panel.addView(buildHeader())
        panel.addView(buildNameRow(), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dpi(6) })
        panel.addView(
            HorizontalScrollView(app).apply {
                isHorizontalScrollBarEnabled = false
                addView(heroesRow)
            },
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dpi(6) },
        )
        val body = Ui.row(app, Gravity.TOP)
        rail.background = PanelDrawable(app, EditorColors.surfaceAlt, EditorColors.borderSoft, 12f)
        rail.setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
        body.addView(
            ScrollView(app).apply {
                isVerticalScrollBarEnabled = false
                addView(rail)
            },
            Ui.lp(dpi(railDp()), Ui.MATCH),
        )
        body.addView(
            ScrollView(app).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, 0, 0, dpi(6))
                addView(content, Ui.lp(Ui.MATCH, Ui.WRAP))
            },
            Ui.lp(0, Ui.MATCH, 1f).apply { marginStart = dpi(8) },
        )
        panel.addView(body, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = dpi(6) })
        panel.addView(buildActions(), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dpi(8) })
        return panel
    }

    private fun buildHeader(): View {
        val row = Ui.row(app)
        row.addView(
            Ui.iconButton(app, Icon.CLOSE, EditorColors.surface, EditorColors.text) { leave() },
            Ui.lp(dpi(36), dpi(36)),
        )
        val titles = Ui.column(app).apply { setPadding(dpi(10), 0, dpi(8), 0) }
        titles.addView(
            Ui.text(app, "PERSONAGEM", 20f, EditorColors.text, Fonts.title).apply {
                letterSpacing = 0.05f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        titles.addView(
            Ui.text(app, "Crie e personalize seu personagem", 10f, EditorColors.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        row.addView(titles, Ui.lp(0, Ui.WRAP, 1f))
        resourcesRow = Header.resources(app)
        row.addView(resourcesRow, Ui.lp(Ui.WRAP, Ui.WRAP))
        return row
    }

    private fun buildNameRow(): View {
        val row = Ui.row(app)
        nameInput.apply {
            setTextColor(EditorColors.text)
            setHintTextColor(EditorColors.muted)
            hint = "Nome do personagem"
            textSize = 14f
            typeface = Fonts.bold
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(14))
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = CardDrawable(app, EditorColors.surfaceAlt, EditorColors.border, 8f)
            setPadding(dpi(10), 0, dpi(10), 0)
            setOnFocusChangeListener { _, focused -> if (!focused) saveName() }
            setOnEditorActionListener { _, _, _ -> saveName(); clearFocus(); false }
        }
        row.addView(nameInput, Ui.lp(0, dpi(32), 1f))
        row.addView(iconish("🎲") { randomize() }, Ui.lp(dpi(38), dpi(32)).apply { marginStart = dpi(6) })
        row.addView(iconish("🗑") { deleteHero() }, Ui.lp(dpi(38), dpi(32)).apply { marginStart = dpi(6) })
        return row
    }

    private fun buildActions(): View {
        val row = Ui.row(app)
        applyButton = action("APLICAR", EditorColors.confirm, EditorColors.dark) { applyChanges() }
        row.addView(applyButton, Ui.lp(0, dpi(34), 1.25f).apply { marginEnd = dpi(6) })
        row.addView(action("CANCELAR", EditorColors.surface, EditorColors.muted) { cancelChanges() }, Ui.lp(0, dpi(34), 1f).apply { marginEnd = dpi(6) })
        row.addView(action("ALEATÓRIO", EditorColors.surface, EditorColors.muted) { randomize() }, Ui.lp(0, dpi(34), 1f))
        return row
    }

    /** Área do herói 3D: fundo transparente e arrasto horizontal girando o modelo. */
    private fun buildStage(): View {
        val stage = FrameLayout(app)
        stage.addView(
            Ui.text(app, "ARRASTE PARA GIRAR", 10f, EditorColors.muted, Fonts.title).apply {
                letterSpacing = 0.1f
                alpha = 0.65f
            },
            Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(16) },
        )
        var lastX = 0f
        val drag = View(app)
        drag.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> lastX = e.x
                MotionEvent.ACTION_MOVE -> {
                    app.rotatePreview((e.x - lastX) / resources.displayMetrics.density)
                    lastX = e.x
                }
            }
            true
        }
        stage.addView(drag, Ui.frame(Ui.MATCH, Ui.MATCH))
        return stage
    }

    // ------------------------------------------------------------------ medidas

    private fun screenDp(): Float = resources.displayMetrics.widthPixels / resources.displayMetrics.density
    private fun panelDp(): Float = (screenDp() * (PANEL_WEIGHT / (PANEL_WEIGHT + STAGE_WEIGHT)) - 20f).coerceAtLeast(190f)
    private fun railDp(): Int = (panelDp() * 0.42f).toInt().coerceIn(96, 132)
    private fun contentDp(): Float = (panelDp() - 24f - railDp() - 10f).coerceAtLeast(96f)
    private fun columns(swatch: Boolean): Int {
        val cell = if (swatch) 54f else 98f
        val min = if (swatch) 4 else 2
        val max = if (swatch) 8 else 4
        return (contentDp() / cell).toInt().coerceIn(min, max)
    }

    // ------------------------------------------------------------------ componentes

    private fun action(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        Ui.button(app, label, fill, textColor, 11f, onClick).apply {
            letterSpacing = 0.03f
            setPadding(dpi(6), dpi(4), dpi(6), dpi(6))
        }

    private fun iconish(label: String, onClick: () -> Unit): TextView =
        Ui.button(app, label, EditorColors.surface, EditorColors.text, 13f, onClick).apply {
            letterSpacing = 0f
            setPadding(dpi(2), dpi(2), dpi(2), dpi(4))
        }

    // ------------------------------------------------------------------ estado

    private fun refreshAll() {
        app.previewLook(hero.look)
        nameInput.setText(hero.name)
        refreshHeroes()
        refreshRail()
        refreshContent()
    }

    private fun refreshResources() {
        val parent = resourcesRow.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(resourcesRow)
        val params = resourcesRow.layoutParams
        val fresh = Header.resources(app)
        parent.removeViewAt(index)
        parent.addView(fresh, index, params)
        resourcesRow = fresh
    }

    private fun refreshHeroes() {
        heroesRow.removeAllViews()
        p.heroes.forEachIndexed { i, h ->
            val editingThis = i == editing
            val inUse = i == p.selectedHero
            val chip = Ui.row(app).apply {
                background = CardDrawable(
                    app,
                    if (editingThis) EditorColors.limeSoft else EditorColors.surfaceAlt,
                    if (editingThis) EditorColors.lime else EditorColors.borderSoft,
                    8f,
                )
                setPadding(dpi(6), dpi(3), dpi(8), dpi(3))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    saveName()
                    editing = i
                    app.audio.playUi("click", 0.5f)
                    refreshAll()
                }
            }
            chip.addView(faceView(h.look), Ui.lp(dpi(22), dpi(22)))
            chip.addView(
                Ui.text(app, h.name + if (inUse) " ✓" else "", 11f, if (editingThis) EditorColors.text else EditorColors.muted, Fonts.title).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dpi(6), 0, 0, 0)
                },
                Ui.lp(Ui.WRAP, Ui.WRAP, 1f),
            )
            heroesRow.addView(chip, Ui.lp(Ui.WRAP, dpi(28)).apply { marginEnd = dpi(6) })
        }
        if (p.heroes.size < Rules.MAX_HEROES) {
            heroesRow.addView(action("+ NOVO", EditorColors.surface, EditorColors.lime) { newHero() }, Ui.lp(Ui.WRAP, dpi(28)))
        }
    }

    private fun faceView(code: Int) = ImageView(app).apply {
        setImageDrawable(BitmapDrawable(resources, LookPainter.face(Look.decode(code))).apply { isFilterBitmap = false })
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    // ------------------------------------------------------------------ menu vertical

    private fun refreshRail() {
        rail.removeAllViews()
        for (c in Category.entries) {
            val selected = c == category
            val item = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = CardDrawable(
                    app,
                    if (selected) EditorColors.limeSoft else Color.TRANSPARENT,
                    if (selected) EditorColors.lime else EditorColors.borderSoft,
                    9f,
                )
                setPadding(dpi(8), 0, dpi(8), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (c == category) return@setOnClickListener
                    category = c
                    app.audio.playUi("click", 0.5f)
                    refreshRail()
                    refreshContent()
                    Ui.popIn(content)
                }
            }
            val marker = View(app).apply { background = BlockDrawable(app, EditorColors.lime, 2f, 0f, false) }
            marker.alpha = if (selected) 1f else 0f
            item.addView(marker, Ui.lp(dpi(3), dpi(16)))
            item.addView(
                IconView(app, c.icon, if (selected) EditorColors.lime else EditorColors.muted),
                Ui.lp(dpi(18), dpi(18)).apply { marginStart = dpi(7) },
            )
            item.addView(
                Ui.text(app, c.label, 11f, if (selected) EditorColors.text else EditorColors.muted, Fonts.title).apply {
                    letterSpacing = 0.02f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dpi(7), 0, 0, 0)
                },
                Ui.lp(0, Ui.WRAP, 1f),
            )
            rail.addView(item, Ui.lp(Ui.MATCH, dpi(30)).apply { bottomMargin = dpi(3) })
        }
    }

    // ------------------------------------------------------------------ conteúdo da categoria

    private fun refreshContent() {
        content.removeAllViews()
        for (part in category.parts) {
            content.addView(
                Ui.text(app, partTitle(part), 10f, EditorColors.lime, Fonts.title).apply {
                    letterSpacing = 0.06f
                    setPadding(dpi(2), dpi(2), 0, dpi(5))
                },
            )
            val swatch = LookOptions.color(part, 0) != null
            val columns = columns(swatch)
            val cellW = dpi((contentDp() / columns).toInt())
            val grid = GridLayout(app).apply { columnCount = columns }
            val current = look[part]
            for (v in 0 until LookOptions.count(part)) {
                grid.addView(
                    optionCard(part, v, v == current, swatch),
                    GridLayout.LayoutParams().apply {
                        width = cellW - dpi(6)
                        height = dpi(if (swatch) 52 else 76)
                        setMargins(dpi(3), dpi(3), dpi(3), dpi(3))
                    },
                )
            }
            content.addView(grid)
        }
        val locked = category.parts.flatMap { part ->
            (0 until LookOptions.count(part)).mapNotNull { Cosmetics.lockOf(part, it) }
        }
        if (locked.isNotEmpty()) {
            content.addView(
                Ui.text(
                    app,
                    "Peças bloqueadas: conquistas, anúncios ou diamantes (veja em MISSÕES). Toque para ver o objetivo.",
                    9f,
                    EditorColors.muted,
                ).apply { setPadding(dpi(2), dpi(8), dpi(2), 0) },
            )
        }
    }

    /** Cartão de uma opção: miniatura (cor ou corpo em pixel art), nome, seleção e cadeado. */
    private fun optionCard(part: LookPart, value: Int, selected: Boolean, swatch: Boolean): View {
        val lock = Cosmetics.lockOf(part, value)
        val owned = Cosmetics.available(p, part, value)
        val card = FrameLayout(app).apply {
            background = CardDrawable(
                app,
                if (selected) EditorColors.limeSoft else EditorColors.card,
                if (selected) EditorColors.lime else EditorColors.borderSoft,
                10f,
            )
            setPadding(dpi(5), dpi(5), dpi(5), dpi(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { pick(part, value) }
        }
        val box = Ui.column(app).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            if (!owned) alpha = 0.6f
        }
        if (swatch) {
            val color = LookOptions.color(part, value)!!
            box.addView(
                View(app).apply { background = CardDrawable(app, color, EditorColors.borderSoft, 8f) },
                Ui.lp(Ui.MATCH, dpi(24)),
            )
        } else {
            box.addView(
                ImageView(app).apply {
                    setImageDrawable(
                        BitmapDrawable(resources, LookPainter.body(look.with(part, value))).apply { isFilterBitmap = false },
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                Ui.lp(Ui.MATCH, dpi(42)),
            )
        }
        box.addView(
            Ui.text(app, LookOptions.name(part, value), 9f, if (selected) EditorColors.text else EditorColors.muted, Fonts.title).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 0.95f)
                setPadding(0, dpi(4), 0, 0)
            },
        )
        card.addView(box, Ui.frame(Ui.MATCH, Ui.MATCH))
        if (selected) {
            card.addView(IconView(app, Icon.CHECK, EditorColors.lime), Ui.frame(dpi(14), dpi(14), Gravity.TOP or Gravity.END))
        }
        if (!owned) {
            card.addView(
                IconView(app, Icon.LOCK, if (lock != null && lock.price > 0) Palette.gold else EditorColors.muted),
                Ui.frame(dpi(13), dpi(13), Gravity.TOP or Gravity.START),
            )
            if (lock != null && lock.price > 0) {
                card.addView(
                    Ui.text(app, "◆ ${lock.price}", 8f, Palette.gold, Fonts.title).apply {
                        gravity = Gravity.CENTER
                        background = CardDrawable(app, Color.argb(215, 4, 7, 14), Color.TRANSPARENT, 6f)
                        setPadding(dpi(4), dpi(1), dpi(4), dpi(2))
                    },
                    Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(4) },
                )
            }
        }
        return card
    }

    private fun partTitle(part: LookPart): String = when (part) {
        LookPart.SKIN -> "TOM DE PELE"
        LookPart.HAIR_STYLE -> "PENTEADO"
        LookPart.HAIR_COLOR -> "COR DO CABELO"
        LookPart.EYES -> "OLHOS"
        LookPart.FACE -> "DETALHE NO ROSTO"
        LookPart.TOP -> "MODELO DE CAMISA"
        LookPart.TOP_COLOR -> "COR DA CAMISA"
        LookPart.PANTS_COLOR -> "COR DA CALÇA"
        LookPart.SHORTS -> "MODELAGEM"
        LookPart.SHOES -> "SAPATO"
        LookPart.HAT -> "ACESSÓRIO"
    }

    // ------------------------------------------------------------------ ações

    private fun pick(part: LookPart, value: Int) {
        if (!Cosmetics.available(p, part, value)) {
            offerUnlock(part, value)
            return
        }
        hero.look = look.with(part, value).encode()
        app.save()
        app.audio.playUi("click", 0.5f)
        app.previewLook(hero.look)
        refreshHeroes()
        refreshContent()
    }

    /** Peça travada: mostra o objetivo e oferece compra/anúncio quando possível. */
    private fun offerUnlock(part: LookPart, value: Int) {
        val c = Cosmetics.lockOf(part, value) ?: return
        val (have, goal) = Cosmetics.progressOf(c, p)
        val text = "${Cosmetics.requirementText(c)}.\nProgresso: ${have.coerceAtMost(goal)}/$goal"
        when {
            c.price > 0 -> Popup.show(app, c.name, "$text\n\nOu libere agora por ◆ ${c.price}.", "COMPRAR ◆ ${c.price}", "DEPOIS") { ok ->
                if (ok && Rules.buyCosmetic(p, c.index)) {
                    app.audio.playUi("unlock", 1f)
                    app.save()
                    pick(part, value)
                    refreshResources()
                } else if (ok) {
                    Popup.show(app, "Diamantes insuficientes", "Você tem ◆ ${p.diamonds}. Ganhe mais vencendo fases e missões.", "OK")
                }
            }
            c.unlock == UnlockKind.ADS -> Popup.show(app, c.name, "$text\n\nCada anúncio assistido conta para liberar.", "▶ ASSISTIR", "DEPOIS") { ok ->
                if (ok) app.watchAd { refreshContent() }
            }
            else -> Popup.show(app, c.name, text, "OK")
        }
    }

    private fun applyChanges() {
        saveName()
        if (editing != p.selectedHero) p.selectedHero = editing
        app.save()
        app.audio.playUi("reward", 0.8f)
        refreshHeroes()
        refreshResources()
        applyButton.text = "APLICADO ✓"
        (applyButton.background as BlockDrawable).apply { fill = EditorColors.lime; invalidateSelf() }
        applyButton.postDelayed({
            applyButton.text = "APLICAR"
            (applyButton.background as BlockDrawable).apply { fill = EditorColors.confirm; invalidateSelf() }
        }, 1400)
    }

    /** Volta os personagens ao estado de quando o editor foi aberto (compras continuam). */
    private fun cancelChanges() {
        saveName()
        p.heroes.forEachIndexed { i, h ->
            entryLooks.getOrNull(i)?.let { h.look = it }
            entryNames.getOrNull(i)?.let { h.name = it }
        }
        p.selectedHero = entrySelected.coerceIn(0, p.heroes.size - 1)
        editing = p.selectedHero
        app.save()
        app.audio.playUi("click", 0.5f)
        refreshAll()
        Popup.show(app, "Alterações descartadas", "Seus personagens voltaram como estavam quando você abriu o editor.", "OK")
    }

    private fun randomize() {
        hero.look = Look.random(p).encode()
        app.save()
        app.audio.playUi("click", 0.6f)
        app.previewLook(hero.look)
        refreshHeroes()
        refreshContent()
    }

    private fun newHero() {
        saveName()
        if (Rules.createHero(p, Look.random(p), "Herói ${p.heroes.size + 1}")) {
            editing = p.selectedHero
            app.save()
            app.audio.playUi("reward", 0.7f)
            refreshAll()
        }
    }

    private fun deleteHero() {
        if (p.heroes.size <= 1) {
            Popup.show(app, "Último personagem", "Você precisa ter pelo menos um personagem. Edite este ou crie outro antes de excluir.", "OK")
            return
        }
        Popup.show(app, "Excluir personagem", "Excluir ${hero.name}? Isso não pode ser desfeito.", "EXCLUIR", "CANCELAR") { ok ->
            if (ok && Rules.deleteHero(p, editing)) {
                editing = p.selectedHero
                app.save()
                refreshAll()
            }
        }
    }

    private fun saveName() {
        val clean = Rules.sanitizeName(nameInput.text.toString())
        if (clean != hero.name) {
            hero.name = clean
            app.save()
            refreshHeroes()
        }
    }

    fun leave() {
        saveName()
        app.showHome()
    }

    private companion object {
        const val PANEL_WEIGHT = 1f
        const val STAGE_WEIGHT = 1.1f
    }
}
