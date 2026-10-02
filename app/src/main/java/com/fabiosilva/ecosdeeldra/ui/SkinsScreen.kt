package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.SkinDef
import com.fabiosilva.ecosdeeldra.data.Skins
import com.fabiosilva.ecosdeeldra.data.UnlockKind

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SkinsScreen(private val app: MainActivity) : FrameLayout(app) {
    private var current = app.progress.selectedSkin
    private val cells = ArrayList<FrameLayout>()
    private val name = Ui.title(app, "", 22f)
    private val role = Ui.text(app, "", 11f, Palette.lime, Fonts.title)
    private val requirement = Ui.text(app, "", 12f, Palette.muted)
    private val bar = ProgressBarView(app)
    private val barText = Ui.text(app, "", 11f, Palette.text, Fonts.bold)
    private val action: TextView
    private var header: LinearLayout

    init {
        val column = Ui.column(app)
        header = Header.build(app, "HERÓIS", "ESCOLHA SUA SKIN • ARRASTE PARA GIRAR") { leave() }
        column.addView(header)
        val body = Ui.row(app, Gravity.TOP)

        // Área de prévia (o motor 3D desenha o herói atrás); arrastar gira o modelo.
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
        body.addView(previewArea, Ui.lp(0, Ui.MATCH, 0.9f))

        val right = Ui.column(app)
        val grid = GridLayout(app).apply { columnCount = 6 }
        for (skin in Skins.all) {
            val cell = skinCell(skin)
            cells.add(cell)
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = app.dpi(70)
                height = app.dpi(78)
                setMargins(app.dpi(3), app.dpi(3), app.dpi(3), app.dpi(3))
            })
        }
        val scroll = ScrollView(app).apply { isVerticalScrollBarEnabled = false; addView(grid) }
        right.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        val detail = Ui.panel(app)
        val detailRow = Ui.row(app)
        val texts = Ui.column(app)
        texts.addView(role)
        texts.addView(name)
        texts.addView(requirement.apply { setPadding(0, app.dpi(2), 0, app.dpi(4)) })
        val barRow = Ui.row(app)
        barRow.addView(bar, Ui.lp(0, app.dpi(9), 1f))
        barRow.addView(barText.apply { setPadding(app.dpi(8), 0, 0, 0) })
        texts.addView(barRow)
        detailRow.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
        action = Ui.button(app, "", Palette.lime, Palette.dark, 14f) { onAction() }
        detailRow.addView(action, Ui.lp(app.dpi(178), app.dpi(54)).apply { marginStart = app.dpi(10) })
        detail.addView(detailRow)
        right.addView(detail, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(6) })
        body.addView(right, Ui.lp(0, Ui.MATCH, 1.1f))
        column.addView(body, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(20), app.dpi(8), app.dpi(20), app.dpi(14)) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        select(current)
    }

    private fun skinCell(skin: SkinDef): FrameLayout {
        val cell = FrameLayout(app)
        cell.background = BlockDrawable(app, Palette.panelSoft, 6f, 3f)
        val bitmap = BitmapFactory.decodeResource(resources, resources.getIdentifier("skin_${skin.letter}", "drawable", app.packageName))
        val image = ImageView(app).apply {
            setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        cell.addView(image, Ui.frame(app.dpi(46), app.dpi(46), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = app.dpi(4) })
        cell.addView(Ui.text(app, skin.name, 10f, Color.WHITE, Fonts.title), Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = app.dpi(7) })
        val badge = Ui.text(app, "", 9f, Palette.dark, Fonts.title).apply {
            setPadding(app.dpi(4), app.dpi(1), app.dpi(4), app.dpi(1))
        }
        cell.addView(badge, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, app.dpi(2), app.dpi(2), 0) })
        cell.tag = skin.index
        cell.setOnClickListener {
            app.audio.playUi("click", 0.6f)
            select(skin.index)
        }
        return cell
    }

    private fun refreshCells() {
        val p = app.progress
        for (cell in cells) {
            val index = cell.tag as Int
            val owned = index in p.ownedSkins
            val bg = cell.background as BlockDrawable
            bg.fill = when {
                index == current -> Palette.lime
                index == p.selectedSkin -> Palette.limeDark
                owned -> Palette.panelSoft
                else -> Color.parseColor("#151B2B")
            }
            bg.invalidateSelf()
            cell.getChildAt(0).alpha = if (owned) 1f else 0.4f
            val badge = cell.getChildAt(2) as TextView
            val skin = Skins.get(index)
            when {
                index == p.selectedSkin -> { badge.text = "✓"; badge.background = PanelDrawable(app, Palette.lime, Color.TRANSPARENT, 6f) }
                owned -> { badge.text = ""; badge.background = null }
                skin.unlock == UnlockKind.ADS -> { badge.text = "▶"; badge.background = PanelDrawable(app, Palette.cyan, Color.TRANSPARENT, 6f) }
                skin.unlock == UnlockKind.LOGIN_DAYS -> { badge.text = "D${skin.goal}"; badge.background = PanelDrawable(app, Palette.gold, Color.TRANSPARENT, 6f) }
                else -> { badge.text = "🔒"; badge.background = PanelDrawable(app, Palette.stone, Color.TRANSPARENT, 6f) }
            }
        }
    }

    private fun select(index: Int) {
        current = index
        app.previewSkin(index)
        val p = app.progress
        val skin = Skins.get(index)
        val owned = index in p.ownedSkins
        name.text = skin.name.uppercase()
        role.text = skin.role.uppercase()
        val (value, goal) = Skins.progressOf(skin, p)
        requirement.text = if (owned) "Skin liberada — toque em EQUIPAR para usar." else Skins.requirementText(skin)
        bar.fraction = if (owned) 1f else value.toFloat() / goal.coerceAtLeast(1)
        bar.color = if (skin.unlock == UnlockKind.ADS) Palette.cyan else Palette.lime
        barText.text = if (owned) "100%" else "$value/$goal"
        val bg = action.background as BlockDrawable
        when {
            owned && p.selectedSkin == index -> { action.text = "EQUIPADO ✓"; bg.fill = Palette.limeDark; Ui.setEnabledLook(action, false) }
            owned -> { action.text = "EQUIPAR"; bg.fill = Palette.lime; Ui.setEnabledLook(action, true) }
            skin.unlock == UnlockKind.ADS -> { action.text = "▶ ANÚNCIO ($value/$goal)"; bg.fill = Palette.cyan; Ui.setEnabledLook(action, true) }
            skin.price > 0 -> { action.text = "COMPRAR ◆ ${skin.price}"; bg.fill = Palette.gold; Ui.setEnabledLook(action, p.diamonds >= skin.price) }
            else -> { action.text = "CONTINUE JOGANDO"; bg.fill = Palette.stone; Ui.setEnabledLook(action, false) }
        }
        bg.invalidateSelf()
        refreshCells()
    }

    private fun onAction() {
        val p = app.progress
        val skin = Skins.get(current)
        val owned = current in p.ownedSkins
        when {
            owned -> {
                p.selectedSkin = current
                app.save()
                app.audio.playUi("reward", 0.8f)
            }
            skin.unlock == UnlockKind.ADS -> app.watchAd { /* contagem e liberação feitas pelo app */ }
            skin.price > 0 && Rules.buySkin(p, current) -> {
                p.selectedSkin = current
                app.save()
                app.audio.playUi("unlock", 1f)
            }
        }
        refreshHeader()
        select(current)
    }

    private fun refreshHeader() {
        val parent = header.parent as LinearLayout
        val index = parent.indexOfChild(header)
        val fresh = Header.build(app, "HERÓIS", "ESCOLHA SUA SKIN • ARRASTE PARA GIRAR") { leave() }
        parent.removeViewAt(index)
        parent.addView(fresh, index)
        header = fresh
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) select(current)
    }

    private fun leave() {
        app.previewSkin(app.progress.selectedSkin)
        app.showHome()
    }
}
