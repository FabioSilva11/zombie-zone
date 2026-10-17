package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Inventory
import com.fabiosilva.ecosdeeldra.data.ItemKind
import com.fabiosilva.ecosdeeldra.data.Items
import com.fabiosilva.ecosdeeldra.data.Rules

/**
 * Grade do inventário estilo Minecraft: 3 fileiras de mochila e a barra rápida embaixo.
 * Toque num item e depois noutro espaço para mover/trocar.
 */
@SuppressLint("ViewConstructor")
class InventoryGridView(context: Context, private val inventory: Inventory, private val onTap: (Int) -> Unit) : View(context) {
    var selected = -1
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.title; textAlign = Paint.Align.RIGHT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.title; textAlign = Paint.Align.LEFT }
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    init { ItemIcons.initialize(context) }

    private fun cell(): Float = minOf(width / 6f, (height - 34f * d) / 4f)

    /** Posição do espaço [i]: fileiras 0..2 = mochila (6..23), fileira 3 = barra rápida (0..5). */
    private fun slotRect(i: Int, out: RectF) {
        val c = cell()
        val left = (width - c * 6) / 2f
        val row = if (i < Inventory.HOTBAR) 3 else (i - Inventory.HOTBAR) / 6
        val col = if (i < Inventory.HOTBAR) i else (i - Inventory.HOTBAR) % 6
        val top = 16f * d + row * c + if (row == 3) 18f * d else 0f
        out.set(left + col * c + 2f * d, top + 2f * d, left + (col + 1) * c - 2f * d, top + c - 2f * d)
    }

    override fun onDraw(canvas: Canvas) {
        val c = cell()
        val left = (width - c * 6) / 2f
        label.textSize = 10f * d
        label.color = Palette.muted
        canvas.drawText("MOCHILA", left, 11f * d, label)
        label.color = Palette.lime
        canvas.drawText("BARRA RÁPIDA (aparece na partida)", left, 16f * d + 3 * c + 13f * d, label)
        for (i in 0 until Inventory.SIZE) {
            slotRect(i, rect)
            val stack = inventory[i]
            // Fundo do espaço com relevo.
            paint.color = Color.argb(220, 22, 28, 42)
            canvas.drawRoundRect(rect, 6f * d, 6f * d, paint)
            paint.color = Color.argb(90, 255, 255, 255)
            canvas.drawRect(rect.left + 3f * d, rect.top + 2f * d, rect.right - 3f * d, rect.top + 4f * d, paint)
            val border = when {
                i == selected -> Palette.gold
                !stack.empty && i == inventory.weaponSlot -> Palette.lime
                !stack.empty && i == inventory.throwSlot -> Color.rgb(255, 150, 60)
                i < Inventory.HOTBAR -> Color.argb(120, 168, 255, 62)
                else -> Color.argb(70, 255, 255, 255)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (i == selected) 3f else 1.5f) * d
            paint.color = border
            canvas.drawRoundRect(rect, 6f * d, 6f * d, paint)
            paint.style = Paint.Style.FILL
            if (!stack.empty) {
                ItemIcons.draw(canvas, stack.id, rect.centerX(), rect.centerY() - 2f * d, rect.width() * 0.8f)
                if (stack.count > 1) {
                    text.textSize = 13f * d
                    text.color = Color.argb(200, 0, 0, 0)
                    canvas.drawText("${stack.count}", rect.right - 4f * d + 1.5f * d, rect.bottom - 5f * d + 1.5f * d, text)
                    text.color = Color.WHITE
                    canvas.drawText("${stack.count}", rect.right - 4f * d, rect.bottom - 5f * d, text)
                }
                if (i == inventory.weaponSlot && Items.isWeapon(stack.id)) {
                    label.textSize = 8f * d
                    label.color = Palette.lime
                    canvas.drawText("NA MÃO", rect.left + 4f * d, rect.top + 11f * d, label)
                }
            }
            if (i < Inventory.HOTBAR) {
                label.textSize = 9f * d
                label.color = Color.argb(150, 255, 255, 255)
                canvas.drawText("${i + 1}", rect.left + 4f * d, rect.bottom - 5f * d, label)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            for (i in 0 until Inventory.SIZE) {
                slotRect(i, rect)
                if (rect.contains(event.x, event.y)) {
                    onTap(i)
                    break
                }
            }
        }
        return true
    }
}

/**
 * Mochila + Arsenal (armas) + Loja (consumíveis). Em partida, mexer aqui pausa o jogo e as mudanças
 * voltam para o motor ao fechar.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class InventoryScreen(
    private val app: MainActivity,
    private val inventory: Inventory,
    private val inMatch: Boolean,
    initialTab: Int,
    private val onClose: (useSlot: Int) -> Unit,
) : FrameLayout(app) {
    private val content = FrameLayout(app)
    private val tabs = ArrayList<TextView>()
    private val diamonds = Ui.text(app, "", 15f, Palette.cyan, Fonts.title)
    private var tab = 0
    private var selected = -1
    private val explosives get() = app.progress.upgrade("explosives")

    // Fundo próprio e opaco: a tela mostra só os itens (sem o personagem).
    private val bgPaint = Paint().apply { color = Color.rgb(9, 14, 26) }
    private val gridPaint = Paint().apply { color = Color.argb(14, 168, 255, 62) }

    init {
        isClickable = true
        Ui.blockTouches(this)
        setWillNotDraw(false)
        val column = Ui.column(app)

        // Barra superior: voltar, título, abas e diamantes.
        val header = Ui.row(app)
        header.setPadding(app.dpi(16), app.dpi(10), app.dpi(16), 0)
        header.addView(Ui.button(app, if (inMatch) "▶ JOGO" else "‹ VOLTAR", Palette.stone, Color.WHITE, 13f) { close() }, Ui.lp(Ui.WRAP, app.dpi(44)))
        val titleCol = Ui.column(app).apply { setPadding(app.dpi(12), 0, app.dpi(10), 0) }
        titleCol.addView(Ui.text(app, if (inMatch) "JOGO PAUSADO" else "ARMAS E ITENS", 10f, Palette.lime, Fonts.title))
        titleCol.addView(Ui.title(app, "INVENTÁRIO", 21f))
        header.addView(titleCol, Ui.lp(Ui.WRAP, Ui.WRAP))
        listOf("🎒 MOCHILA", "🔫 ARSENAL", "🛒 LOJA").forEachIndexed { i, labelText ->
            val t = Ui.button(app, labelText, Palette.stone, Color.WHITE, 12f) { selectTab(i) }
            tabs.add(t)
            header.addView(t, Ui.lp(0, app.dpi(42), 1f).apply { marginEnd = app.dpi(6) })
        }
        header.addView(diamonds.apply {
            background = PanelDrawable(app, Color.parseColor("#CC0B1426"), Color.argb(80, 60, 230, 255), 14f)
            setPadding(app.dpi(12), app.dpi(6), app.dpi(12), app.dpi(6))
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = app.dpi(4) })
        column.addView(header)

        // Corpo: só o conteúdo da aba, ocupando a largura toda.
        column.addView(content, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(16), app.dpi(10), app.dpi(16), app.dpi(10)) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        selectTab(initialTab)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        // Textura discreta de blocos.
        val step = app.dp(32f)
        var x = 0f
        while (x < w) { canvas.drawRect(x, 0f, x + 1f, h, gridPaint); x += step }
        var y = 0f
        while (y < h) { canvas.drawRect(0f, y, w, y + 1f, gridPaint); y += step }
    }

    /** Fecha a tela (volta ao jogo ou ao menu). */
    fun close() {
        inventory.normalize()
        onClose(-1)
    }

    private fun refreshDiamonds() {
        diamonds.text = "◆ ${app.progress.diamonds}"
    }

    private fun selectTab(i: Int) {
        tab = i
        tabs.forEachIndexed { k, t ->
            (t.background as BlockDrawable).apply { fill = if (k == i) Palette.lime else Palette.stone; invalidateSelf() }
            t.setTextColor(if (k == i) Palette.dark else Color.WHITE)
        }
        refreshDiamonds()
        content.removeAllViews()
        content.addView(
            when (i) {
                0 -> backpackView()
                1 -> arsenalView()
                else -> shopView()
            },
            Ui.frame(Ui.MATCH, Ui.MATCH),
        )
    }

    /** Salva a mochila (em partida a cópia de trabalho também volta ao motor ao fechar). */
    private fun commit() {
        inventory.normalize()
        if (inventory !== app.progress.inventory) app.progress.inventory.copyFrom(inventory)
        app.save()
    }

    // ------------------------------------------------------------------ mochila

    private fun backpackView(): View {
        val row = Ui.row(app, Gravity.TOP)
        val details = Ui.panel(app)
        lateinit var grid: InventoryGridView
        fun showDetails() {
            details.removeAllViews()
            val stack = inventory.slots.getOrNull(selected)
            if (stack == null || stack.empty) {
                details.addView(Ui.title(app, "Sua mochila", 17f, Palette.lime))
                details.addView(Ui.text(app, "Toque num item para ver detalhes. Toque num item e depois em outro espaço para mover ou trocar — como no Minecraft.\n\nSó a BARRA RÁPIDA (6 espaços de baixo) aparece durante a partida: deixe lá suas armas e itens favoritos.\n\nGanhe itens matando zumbis, abrindo baús e nos SUPRIMENTOS (anúncio).", 12f, Palette.muted).apply { setLineSpacing(0f, 1.15f); setPadding(0, app.dpi(6), 0, 0) })
                return
            }
            val def = Items.get(stack.id) ?: return
            val top = Ui.row(app)
            top.addView(ItemIconView(app, def.id), Ui.lp(app.dpi(54), app.dpi(54)))
            val texts = Ui.column(app).apply { setPadding(app.dpi(10), 0, 0, 0) }
            texts.addView(Ui.title(app, def.name, 16f, def.color))
            texts.addView(Ui.text(app, if (def.kind == ItemKind.WEAPON) "ARMA" else "×${stack.count} (máx. ${Items.maxStack(def.id, explosives)})", 11f, Palette.muted, Fonts.title))
            top.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
            details.addView(top)
            details.addView(Ui.text(app, def.desc, 12f, Palette.text).apply { setPadding(0, app.dpi(6), 0, app.dpi(4)) })
            if (def.kind == ItemKind.WEAPON) {
                details.addView(Ui.text(app, "Dano ${def.damage} • Cadência ${def.rate} • Pente ${def.magazine}", 11f, Palette.gold, Fonts.bold))
                val inHand = selected == inventory.weaponSlot
                val equip = Ui.button(app, if (inHand) "NA MÃO ✓" else "EQUIPAR", Palette.lime, Palette.dark, 13f) {
                    inventory.weaponSlot = selected
                    commit()
                    grid.invalidate()
                    showDetails()
                }
                Ui.setEnabledLook(equip, !inHand)
                details.addView(equip, Ui.lp(Ui.MATCH, app.dpi(44)).apply { topMargin = app.dpi(8) })
            } else {
                if (def.kind == ItemKind.THROWABLE) {
                    val chosen = selected == inventory.throwSlot
                    val pick = Ui.button(app, if (chosen) "ESCOLHIDO ✓" else "USAR NO BOTÃO DE ARREMESSO", Color.rgb(255, 150, 60), Palette.dark, 11f) {
                        inventory.throwSlot = selected
                        commit()
                        grid.invalidate()
                        showDetails()
                    }
                    Ui.setEnabledLook(pick, !chosen)
                    details.addView(pick, Ui.lp(Ui.MATCH, app.dpi(42)).apply { topMargin = app.dpi(8) })
                } else if (inMatch) {
                    details.addView(Ui.button(app, "USAR AGORA", Palette.lime, Palette.dark, 13f) {
                        inventory.normalize()
                        onClose(selected)
                    }, Ui.lp(Ui.MATCH, app.dpi(44)).apply { topMargin = app.dpi(8) })
                }
                details.addView(Ui.button(app, "DESCARTAR", Palette.red, Color.WHITE, 12f) {
                    Popup.show(app, "Descartar", "Jogar fora ${def.name} ×${stack.count}?", "DESCARTAR", "CANCELAR") { ok ->
                        if (ok && inventory.discard(selected)) {
                            selected = -1
                            grid.selected = -1
                            commit()
                            showDetails()
                        }
                    }
                }, Ui.lp(Ui.MATCH, app.dpi(40)).apply { topMargin = app.dpi(6) })
            }
        }
        grid = InventoryGridView(app, inventory) { i ->
            when {
                selected == i -> selected = -1
                selected >= 0 && !inventory[selected].empty -> {
                    inventory.moveOrSwap(selected, i, explosives)
                    app.audio.playUi("click", 0.4f)
                    commit()
                    selected = -1
                }
                else -> selected = i
            }
            grid.selected = selected
            showDetails()
        }
        val gridWindow = Ui.panel(app, Color.argb(240, 20, 28, 46), Color.argb(90, 168, 255, 62))
        gridWindow.addView(grid, Ui.lp(Ui.MATCH, Ui.MATCH))
        row.addView(gridWindow, Ui.lp(0, Ui.MATCH, 1.3f).apply { marginEnd = app.dpi(10) })
        row.addView(ScrollView(app).apply { addView(details) }, Ui.lp(0, Ui.MATCH, 1f))
        showDetails()
        return row
    }

    // ------------------------------------------------------------------ arsenal (armas)

    private fun arsenalView(): View {
        val list = Ui.column(app)
        list.addView(Ui.text(app, "Armas compradas ficam para sempre na mochila. Novas armas liberam conforme você avança nas fases (às vezes aparecem em baús!).", 12f, Palette.muted).apply { setPadding(0, 0, 0, app.dpi(6)) })
        for (def in Items.weapons) {
            val card = Ui.panel(app, Palette.panelSoft)
            val r = Ui.row(app)
            r.addView(ItemIconView(app, def.id), Ui.lp(app.dpi(64), app.dpi(48)))
            val texts = Ui.column(app).apply { setPadding(app.dpi(10), 0, 0, 0) }
            texts.addView(Ui.title(app, def.name, 15f, def.color))
            texts.addView(Ui.text(app, def.desc, 11f, Palette.muted))
            texts.addView(Ui.text(app, "Dano ${def.damage} • Cadência ${def.rate} • Pente ${def.magazine}", 11f, Palette.gold, Fonts.bold).apply { setPadding(0, app.dpi(2), 0, 0) })
            r.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
            val owned = inventory.has(def.id)
            val unlocked = Rules.weaponUnlocked(app.progress, def.id)
            val button = when {
                owned -> Ui.button(app, "NA MOCHILA ✓", Palette.limeDark, Palette.dark, 12f) {}.also { Ui.setEnabledLook(it, false) }
                !unlocked -> Ui.button(app, "FASE ${def.unlockStage}", Palette.stone, Color.WHITE, 12f) {}.also { Ui.setEnabledLook(it, false) }
                else -> Ui.button(app, "COMPRAR ◆ ${def.price}", Palette.gold, Palette.dark, 12f) { buyWeapon(def.id) }
                    .also { Ui.setEnabledLook(it, app.progress.diamonds >= def.price) }
            }
            r.addView(button, Ui.lp(app.dpi(150), app.dpi(46)).apply { marginStart = app.dpi(10) })
            card.addView(r)
            list.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = app.dpi(6) })
        }
        return ScrollView(app).apply { addView(list) }
    }

    private fun buyWeapon(id: Int) {
        if (!inventory.canAdd(id)) {
            Popup.show(app, "Mochila cheia", "Descarte algum item para abrir espaço para a nova arma.", "OK")
            return
        }
        // Compra na cópia de trabalho (em partida ela é a mochila do motor).
        val def = Items.get(id) ?: return
        if (app.progress.diamonds < def.price) return
        app.progress.diamonds -= def.price
        inventory.add(id, 1)
        commit()
        app.audio.playUi("unlock", 0.9f)
        selectTab(1)
    }

    // ------------------------------------------------------------------ loja (consumíveis)

    private fun shopView(): View {
        val column = Ui.column(app)
        val remaining = Rules.supplyAdsRemaining(app.progress, app.today())
        val ad = Ui.button(app, if (remaining > 0) "▶ CAIXA DE SUPRIMENTOS GRÁTIS (anúncio) • $remaining hoje" else "▶ CAIXAS GRÁTIS ACABARAM — VOLTE AMANHÃ", Palette.cyan, Palette.dark, 13f) {
            app.watchAd {
                val got = Rules.claimSupplyCrate(app.progress, app.today())
                // Em partida a cópia de trabalho também recebe os itens.
                if (inMatch) got.forEach { (id, n) -> inventory.add(id, n, explosives) } else inventory.copyFrom(app.progress.inventory)
                commit()
                app.audio.playUi("reward", 1f)
                val text = if (got.isEmpty()) "Sua mochila está cheia! Abra espaço para receber itens." else got.joinToString("\n") { (id, n) -> "+$n ${Items.get(id)?.name}" }
                Popup.show(app, "Suprimentos!", text, "OBA!")
                selectTab(2)
            }
        }
        Ui.setEnabledLook(ad, remaining > 0)
        column.addView(ad, Ui.lp(Ui.MATCH, app.dpi(50)).apply { bottomMargin = app.dpi(8) })
        val gridRows = Items.consumables.chunked(2)
        for (pair in gridRows) {
            val r = Ui.row(app)
            pair.forEachIndexed { k, def ->
                val card = Ui.panel(app, Palette.panelSoft)
                val top = Ui.row(app)
                top.addView(ItemIconView(app, def.id), Ui.lp(app.dpi(44), app.dpi(44)))
                val texts = Ui.column(app).apply { setPadding(app.dpi(8), 0, 0, 0) }
                texts.addView(Ui.title(app, def.name, 14f, def.color))
                texts.addView(Ui.text(app, "Na mochila: ${inventory.count(def.id)}", 10f, Palette.muted, Fonts.bold))
                top.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
                card.addView(top)
                card.addView(Ui.text(app, def.desc, 11f, Palette.muted).apply { setPadding(0, app.dpi(4), 0, app.dpi(6)) })
                val buttons = Ui.row(app)
                for (amount in listOf(1, 5)) {
                    val price = def.price * amount
                    val b = Ui.button(app, "×$amount ◆$price", if (amount == 1) Palette.gold else Palette.lime, Palette.dark, 12f) { buyConsumable(def.id, amount) }
                    Ui.setEnabledLook(b, app.progress.diamonds >= price)
                    buttons.addView(b, Ui.lp(0, app.dpi(40), 1f).apply { marginEnd = if (amount == 1) app.dpi(6) else 0 })
                }
                card.addView(buttons)
                r.addView(card, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = if (k == 0) app.dpi(8) else 0 })
            }
            if (pair.size == 1) r.addView(View(app), Ui.lp(0, 1, 1f))
            column.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = app.dpi(8) })
        }
        return ScrollView(app).apply { addView(column) }
    }

    private fun buyConsumable(id: Int, amount: Int) {
        val def = Items.get(id) ?: return
        val price = def.price * amount
        if (app.progress.diamonds < price) return
        val test = Inventory().apply { copyFrom(inventory) }
        if (test.add(id, amount, explosives) > 0) {
            Popup.show(app, "Mochila cheia", "Não cabe tudo. Descarte itens ou compre menos.", "OK")
            return
        }
        app.progress.diamonds -= price
        inventory.add(id, amount, explosives)
        commit()
        app.audio.playUi("reward", 0.8f)
        selectTab(2)
    }
}
