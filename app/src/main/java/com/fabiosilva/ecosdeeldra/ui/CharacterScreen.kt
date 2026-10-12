package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.UnlockKind

/**
 * Editor de personagem estilo Minecraft: o jogador cria até 6 personagens de blocos, escolhendo
 * pele, cabelo, rosto, roupa, calça, sapato e acessório. O herói em 3D (desenhado pelo motor à
 * esquerda) muda na hora. Peças especiais são liberadas por conquistas, anúncios ou diamantes.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CharacterScreen(private val app: MainActivity) : FrameLayout(app) {
    private enum class Tab(val label: String, val parts: List<LookPart>) {
        SKIN("PELE", listOf(LookPart.SKIN)),
        HAIR("CABELO", listOf(LookPart.HAIR_STYLE, LookPart.HAIR_COLOR)),
        FACE("ROSTO", listOf(LookPart.EYES, LookPart.FACE)),
        TOP("ROUPA", listOf(LookPart.TOP, LookPart.TOP_COLOR)),
        LEGS("CALÇA", listOf(LookPart.SHORTS, LookPart.PANTS_COLOR, LookPart.SHOES)),
        EXTRA("EXTRAS", listOf(LookPart.HAT)),
    }

    private val p = app.progress
    private var editing = p.selectedHero
    private var tab = Tab.SKIN
    private val heroesRow = Ui.row(app)
    private val nameInput = EditText(app)
    private val tabsRow = Ui.row(app)
    private val options = Ui.column(app)
    private val useButton: TextView
    private var header: LinearLayout

    private val hero get() = p.heroes[editing]
    private val look get() = Look.decode(hero.look)

    init {
        val column = Ui.column(app)
        header = Header.build(app, "PERSONAGEM", "CRIE SEU PERSONAGEM DE BLOCOS • ARRASTE PARA GIRAR") { leave() }
        column.addView(header)
        val body = Ui.row(app, Gravity.TOP)

        // Prévia 3D (o motor desenha o herói atrás); arrastar gira o modelo.
        val previewArea = View(app)
        var lastX = 0f
        previewArea.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> lastX = e.x
                MotionEvent.ACTION_MOVE -> {
                    app.rotatePreview((e.x - lastX) / resources.displayMetrics.density)
                    lastX = e.x
                }
            }
            true
        }
        body.addView(previewArea, Ui.lp(0, Ui.MATCH, 0.8f))

        val right = Ui.panel(app)
        right.addView(HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            addView(heroesRow)
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        val nameRow = Ui.row(app).apply { setPadding(0, app.dpi(6), 0, app.dpi(6)) }
        nameInput.apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Palette.muted)
            hint = "Nome do personagem"
            textSize = 15f
            typeface = Fonts.bold
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(14))
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = PanelDrawable(app, Color.argb(120, 0, 0, 0), Palette.line, 8f)
            setPadding(app.dpi(10), app.dpi(6), app.dpi(10), app.dpi(6))
            setOnFocusChangeListener { _, focused -> if (!focused) saveName() }
            setOnEditorActionListener { _, _, _ -> saveName(); clearFocus(); false }
        }
        nameRow.addView(nameInput, Ui.lp(0, Ui.WRAP, 1f))
        nameRow.addView(small("🎲 SORTEAR", Palette.stone, Color.WHITE) { randomize() }, Ui.lp(Ui.WRAP, app.dpi(40)).apply { marginStart = app.dpi(6) })
        nameRow.addView(small("🗑", Palette.red, Color.WHITE) { deleteHero() }, Ui.lp(app.dpi(46), app.dpi(40)).apply { marginStart = app.dpi(6) })
        right.addView(nameRow)
        right.addView(tabsRow)
        right.addView(ScrollView(app).apply { addView(options) }, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = app.dpi(6) })
        useButton = Ui.button(app, "", Palette.lime, Palette.dark, 14f) { useHero() }
        right.addView(useButton, Ui.lp(Ui.MATCH, app.dpi(46)).apply { topMargin = app.dpi(6) })
        body.addView(right, Ui.lp(0, Ui.MATCH, 1.25f))
        column.addView(body, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(20), app.dpi(8), app.dpi(20), app.dpi(12)) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        refreshAll()
    }

    private fun small(label: String, fill: Int, text: Int, onClick: () -> Unit) =
        Ui.button(app, label, fill, text, 12f, onClick).apply { setPadding(app.dpi(10), app.dpi(4), app.dpi(10), app.dpi(8)) }

    private fun refreshAll() {
        app.previewLook(hero.look)
        nameInput.setText(hero.name)
        refreshHeroes()
        refreshTabs()
        refreshOptions()
        refreshUse()
    }

    // ------------------------------------------------------------------ personagens salvos

    private fun refreshHeroes() {
        heroesRow.removeAllViews()
        p.heroes.forEachIndexed { i, h ->
            val chip = Ui.row(app).apply {
                background = BlockDrawable(app, if (i == editing) Palette.lime else Palette.stone, 6f, 3f)
                setPadding(app.dpi(8), app.dpi(4), app.dpi(10), app.dpi(7))
                isClickable = true
                setOnClickListener {
                    saveName()
                    editing = i
                    app.audio.playUi("click", 0.5f)
                    refreshAll()
                }
            }
            chip.addView(faceView(h.look), Ui.lp(app.dpi(24), app.dpi(24)))
            val label = h.name + if (i == p.selectedHero) " ✓" else ""
            chip.addView(Ui.text(app, label, 12f, if (i == editing) Palette.dark else Color.WHITE, Fonts.title).apply { setPadding(app.dpi(6), 0, 0, 0) })
            heroesRow.addView(chip, Ui.lp(Ui.WRAP, app.dpi(38)).apply { marginEnd = app.dpi(6) })
        }
        if (p.heroes.size < Rules.MAX_HEROES) {
            heroesRow.addView(small("+ NOVO", Palette.cyan, Palette.dark) { newHero() }, Ui.lp(Ui.WRAP, app.dpi(38)))
        }
    }

    private fun faceView(code: Int) = ImageView(app).apply {
        setImageDrawable(BitmapDrawable(resources, LookPainter.face(Look.decode(code))).apply { isFilterBitmap = false })
        scaleType = ImageView.ScaleType.FIT_CENTER
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

    private fun randomize() {
        hero.look = Look.random(p).encode()
        app.save()
        app.audio.playUi("click", 0.6f)
        refreshAll()
    }

    private fun saveName() {
        val clean = Rules.sanitizeName(nameInput.text.toString())
        if (clean != hero.name) {
            hero.name = clean
            app.save()
            refreshHeroes()
        }
    }

    private fun refreshUse() {
        val inUse = editing == p.selectedHero
        useButton.text = if (inUse) "EM USO ✓" else "USAR ESTE PERSONAGEM ▶"
        (useButton.background as BlockDrawable).apply { fill = if (inUse) Palette.limeDark else Palette.lime; invalidateSelf() }
        Ui.setEnabledLook(useButton, !inUse)
    }

    private fun useHero() {
        saveName()
        p.selectedHero = editing
        app.save()
        app.audio.playUi("reward", 0.8f)
        refreshHeroes()
        refreshUse()
    }

    // ------------------------------------------------------------------ peças

    private fun refreshTabs() {
        tabsRow.removeAllViews()
        for (t in Tab.entries) {
            val selected = t == tab
            val b = Ui.button(app, t.label, if (selected) Palette.lime else Palette.stone, if (selected) Palette.dark else Color.WHITE, 11f) {
                tab = t
                refreshTabs()
                refreshOptions()
            }.apply { setPadding(app.dpi(2), app.dpi(4), app.dpi(2), app.dpi(8)) }
            tabsRow.addView(b, Ui.lp(0, app.dpi(38), 1f).apply { marginEnd = app.dpi(4) })
        }
    }

    private fun refreshOptions() {
        options.removeAllViews()
        for (part in tab.parts) {
            options.addView(Ui.text(app, partTitle(part), 10f, Palette.lime, Fonts.title).apply { setPadding(0, app.dpi(6), 0, app.dpi(4)) })
            val count = LookOptions.count(part)
            val swatches = LookOptions.color(part, 0) != null
            val grid = GridLayout(app).apply { columnCount = if (swatches) 8 else 4 }
            val current = look[part]
            for (v in 0 until count) {
                val lock = Cosmetics.lockOf(part, v)
                val owned = Cosmetics.available(p, part, v)
                val selected = v == current
                val cell: View = if (swatches) {
                    val color = LookOptions.color(part, v)!!
                    val bright = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > 150
                    Ui.button(app, if (selected) "✓" else if (!owned) "🔒" else "", color, if (bright) Palette.dark else Color.WHITE, 14f) { pick(part, v) }
                        .apply { setPadding(0, 0, 0, app.dpi(4)) }
                } else {
                    val label = LookOptions.name(part, v) + if (!owned && lock != null) " 🔒" else ""
                    Ui.button(app, label, if (selected) Palette.lime else Palette.panelSoft, if (selected) Palette.dark else Color.WHITE, 11f) { pick(part, v) }
                        .apply { setPadding(app.dpi(4), app.dpi(4), app.dpi(4), app.dpi(8)) }
                }
                if (!owned) cell.alpha = 0.6f
                grid.addView(cell, GridLayout.LayoutParams().apply {
                    width = if (swatches) app.dpi(40) else app.dpi(112)
                    height = if (swatches) app.dpi(40) else app.dpi(40)
                    setMargins(app.dpi(2), app.dpi(2), app.dpi(2), app.dpi(2))
                })
            }
            options.addView(grid)
        }
        val special = tab.parts.mapNotNull { part -> (0 until LookOptions.count(part)).mapNotNull { Cosmetics.lockOf(part, it) }.takeIf { it.isNotEmpty() } }.flatten()
        if (special.isNotEmpty()) {
            options.addView(Ui.text(app, "🔒 Peças especiais: liberadas por conquistas, anúncios ou diamantes (veja em MISSÕES).", 10f, Palette.muted).apply { setPadding(0, app.dpi(8), 0, 0) })
        }
    }

    private fun partTitle(part: LookPart): String = when (part) {
        LookPart.SKIN -> "TOM DE PELE"
        LookPart.HAIR_STYLE -> "PENTEADO"
        LookPart.HAIR_COLOR -> "COR DO CABELO"
        LookPart.EYES -> "OLHOS"
        LookPart.FACE -> "DETALHE NO ROSTO"
        LookPart.TOP -> "ROUPA"
        LookPart.TOP_COLOR -> "COR DA ROUPA (E DO BONÉ/GORRO)"
        LookPart.PANTS_COLOR -> "COR DA CALÇA"
        LookPart.SHORTS -> "MODELO"
        LookPart.SHOES -> "SAPATO"
        LookPart.HAT -> "ACESSÓRIO"
    }

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
        refreshOptions()
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
                    refreshHeader()
                } else if (ok) {
                    Popup.show(app, "Diamantes insuficientes", "Você tem ◆ ${p.diamonds}. Ganhe mais vencendo fases e missões.", "OK")
                }
            }
            c.unlock == UnlockKind.ADS -> Popup.show(app, c.name, "$text\n\nCada anúncio assistido conta para liberar.", "▶ ASSISTIR", "DEPOIS") { ok ->
                if (ok) app.watchAd { refreshOptions() }
            }
            else -> Popup.show(app, c.name, text, "OK")
        }
    }

    private fun refreshHeader() {
        val parent = header.parent as LinearLayout
        val index = parent.indexOfChild(header)
        val fresh = Header.build(app, "PERSONAGEM", "CRIE SEU PERSONAGEM DE BLOCOS • ARRASTE PARA GIRAR") { leave() }
        parent.removeViewAt(index)
        parent.addView(fresh, index)
        header = fresh
    }

    fun leave() {
        saveName()
        app.showHome()
    }
}
