package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.Cosmetics
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.Look
import com.fabiosilva.ecosdeeldra.data.LookPainter
import com.fabiosilva.ecosdeeldra.data.Rules

/** Cabeçalho padrão das telas: moedas à esquerda; título e botão de fechar à direita. */
object Header {
    fun build(app: MainActivity, title: String, subtitle: String, onBack: () -> Unit): LinearLayout {
        val row = Ui.row(app)
        row.setPadding(app.dpi(16), app.dpi(10), app.dpi(16), 0)
        row.addView(resources(app))
        row.addView(View(app), Ui.lp(0, 1, 1f))
        val titles = Ui.column(app).apply {
            gravity = Gravity.END
            setPadding(0, 0, app.dpi(12), 0)
        }
        titles.addView(Ui.text(app, subtitle, 9f, Palette.accent, Fonts.title).apply { gravity = Gravity.END })
        titles.addView(Ui.title(app, title, 24f).apply { gravity = Gravity.END })
        row.addView(titles)
        row.addView(Ui.iconButton(app, Icon.CLOSE, Palette.stone, Color.WHITE, onBack), Ui.lp(app.dpi(44), app.dpi(44)))
        return row
    }

    fun resources(app: MainActivity): LinearLayout {
        val row = Ui.row(app)
        val starTarget = app.progress.highestUnlockedStage() * 3
        row.addView(Ui.currencyChip(app, Icon.STAR, "${app.progress.totalStars()}/$starTarget", Palette.gold))
        row.addView(View(app), Ui.lp(app.dpi(8), 1))
        row.addView(Ui.currencyChip(app, Icon.DIAMOND, app.progress.diamonds.toString(), Palette.cyan))
        return row
    }
}

/** Lobby: o herói 3D (desenhado pelo motor) fica no centro; a interface o emoldura nas bordas. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class HomeScreen(private val app: MainActivity) : FrameLayout(app) {
    private class Entry(
        val root: FrameLayout,
        val title: TextView,
        val sub: TextView,
        val icon: IconView,
        val bg: BlockDrawable,
        val badge: BadgeView,
    )

    private val menuFill = Color.parseColor("#D9160F38")
    private val currencies = FrameLayout(app)
    private val stagesValue = Ui.title(app, "", 10.5f)
    private val starsValue = Ui.title(app, "", 10.5f)
    private val heroesValue = Ui.title(app, "", 10.5f)
    private val missions: Entry
    private val daily: Entry
    private val chest: Entry
    private val video: Entry
    private val ticker = object : Runnable {
        override fun run() {
            updateChest()
            postDelayed(this, 1000)
        }
    }

    private fun scrim(top: Boolean, heightDp: Int) {
        val colors = if (top) intArrayOf(Color.argb(175, 8, 4, 24), Color.TRANSPARENT) else intArrayOf(Color.TRANSPARENT, Color.argb(200, 8, 4, 24))
        val v = View(app).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors) }
        addView(v, Ui.frame(Ui.MATCH, app.dpi(heightDp), if (top) Gravity.TOP else Gravity.BOTTOM))
    }

    private fun lobbyResources(): LinearLayout {
        fun chip(icon: Icon, value: String, color: Int): LinearLayout = Ui.row(app).apply {
            background = PanelDrawable(app, Color.parseColor("#D90B0720"), Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)), 6f)
            setPadding(app.dpi(6), app.dpi(3), app.dpi(6), app.dpi(3))
            addView(IconView(app, icon, color), Ui.lp(app.dpi(13), app.dpi(13)))
            addView(Ui.text(app, value, 11f, Palette.text, Fonts.title).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = app.dpi(4) })
        }
        val p = app.progress
        return Ui.row(app).apply {
            addView(chip(Icon.STAR, "${p.totalStars()}/${p.highestUnlockedStage() * 3}", Palette.gold), Ui.lp(0, app.dpi(23), 1.25f))
            addView(View(app), Ui.lp(app.dpi(6), 1))
            addView(chip(Icon.DIAMOND, p.diamonds.toString(), Palette.cyan), Ui.lp(0, app.dpi(23), 1f))
        }
    }

    private fun entry(icon: Icon, label: String, onClick: () -> Unit): Entry {
        val bg = BlockDrawable(app, menuFill, 6f, 3f)
        val root = FrameLayout(app).apply {
            background = bg
            isClickable = true
            isFocusable = true
        }
        val row = Ui.row(app).apply { setPadding(app.dpi(7), 0, app.dpi(18), app.dpi(1)) }
        val iv = IconView(app, icon, Palette.accent)
        row.addView(iv, Ui.lp(app.dpi(16), app.dpi(16)))
        val col = Ui.column(app).apply { setPadding(app.dpi(6), 0, 0, 0) }
        val t = Ui.title(app, label, 11f).apply {
            typeface = Fonts.bold
            letterSpacing = 0.01f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val s = Ui.text(app, "", 7.5f, Palette.muted, Fonts.bold).apply {
            visibility = View.GONE
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(t)
        col.addView(s)
        row.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        root.addView(row, Ui.frame(Ui.MATCH, Ui.MATCH, Gravity.CENTER_VERTICAL))
        val badge = BadgeView(app).apply {
            textSize = 9f
            includeFontPadding = false
            setPadding(app.dpi(4), 0, app.dpi(4), app.dpi(1))
        }
        root.addView(badge, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, app.dpi(1), app.dpi(2), 0) })
        root.setOnClickListener {
            Ui.clickSound?.invoke()
            onClick()
        }
        return Entry(root, t, s, iv, bg, badge)
    }

    private fun setSub(e: Entry, text: String?) {
        e.sub.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        e.sub.text = text ?: ""
    }

    init {
        val p = app.progress
        scrim(top = true, heightDp = 110)
        scrim(top = false, heightDp = 150)

        // Cartão do jogador (canto superior esquerdo)
        val hero = p.currentHero
        val level = p.stages.values.count { it.completed } + 1
        val card = Ui.row(app).apply {
            background = PanelDrawable(app, Color.parseColor("#E6251260"), Color.parseColor("#99B07CFF"), 8f)
            setPadding(app.dpi(4), app.dpi(2), app.dpi(8), app.dpi(2))
        }
        val avatar = FrameLayout(app).apply { background = PanelDrawable(app, Color.parseColor("#B30B0720"), Color.argb(120, 255, 196, 0), 5f) }
        val bitmap = LookPainter.face(Look.decode(hero.look))
        avatar.addView(
            ImageView(app).apply {
                setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            Ui.frame(Ui.MATCH, Ui.MATCH).apply { setMargins(app.dpi(2), app.dpi(2), app.dpi(2), app.dpi(2)) },
        )
        avatar.addView(
            Ui.text(app, "$level", 7f, Palette.dark, Fonts.title).apply {
                gravity = Gravity.CENTER
                background = PanelDrawable(app, Palette.accent, Color.TRANSPARENT, 4f)
                setPadding(app.dpi(3), 0, app.dpi(3), 0)
            },
            Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START),
        )
        card.addView(avatar, Ui.lp(app.dpi(26), app.dpi(26)))
        val names = Ui.column(app).apply { setPadding(app.dpi(7), 0, 0, 0) }
        names.addView(Ui.title(app, hero.name.uppercase(), 11.5f).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        names.addView(Ui.text(app, "NÍVEL $level • PERSONAGEM", 7.5f, Palette.accent, Fonts.title).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        card.addView(names, Ui.lp(0, Ui.WRAP, 1f))
        // cartão + moedas na mesma linha, no canto superior esquerdo
        val topLeft = Ui.row(app)
        topLeft.addView(card, Ui.lp(app.dpi(142), Ui.WRAP))
        topLeft.addView(currencies, Ui.lp(app.dpi(134), Ui.WRAP).apply { marginStart = app.dpi(8) })
        addView(topLeft, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START))

        // Marca + ajustes (canto superior direito)
        val topRight = Ui.row(app)
        val wordmark = SpannableString("ZOMBIE ZONE").apply { setSpan(ForegroundColorSpan(Palette.accent), 7, length, 0) }
        val brand = Ui.title(app, "", 16f).apply {
            text = wordmark
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        topRight.addView(brand, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = app.dpi(8) })
        val settingsButton = Ui.iconButton(app, Icon.GEAR, Palette.stone, Color.WHITE) { app.showSettings() }
        settingsButton.getChildAt(0).layoutParams = Ui.frame(app.dpi(16), app.dpi(16), Gravity.CENTER)
        topRight.addView(settingsButton, Ui.lp(app.dpi(26), app.dpi(26)))
        addView(topRight, Ui.frame(app.dpi(152), app.dpi(30), Gravity.TOP or Gravity.END))

        // Estatísticas (lateral direita)
        val stats = Ui.panel(app, Color.parseColor("#B30B0720")).apply { setPadding(app.dpi(6), app.dpi(4), app.dpi(6), app.dpi(4)) }
        fun stat(label: String, value: TextView) {
            val r = Ui.row(app)
            r.addView(Ui.text(app, label, 8f, Palette.muted, Fonts.bold).apply { isSingleLine = true }, Ui.lp(0, Ui.WRAP, 0.45f))
            value.apply {
                gravity = Gravity.END
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            r.addView(value, Ui.lp(0, Ui.WRAP, 0.55f))
            stats.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = app.dpi(3) })
        }
        stat("FASES", stagesValue)
        stat("ESTRELAS", starsValue)
        stat("HERÓIS", heroesValue)
        addView(stats, Ui.frame(app.dpi(100), Ui.WRAP, Gravity.TOP or Gravity.END))

        // Menu lateral esquerdo
        missions = entry(Icon.MISSIONS, "MISSÕES") { app.showMissions() }
        val pvp = entry(Icon.SWORDS, "PVP WI-FI") { app.showPvp() }
        chest = entry(Icon.CHEST, "BAÚ") { onChest() }
        video = entry(Icon.PLAY, "VÍDEO") { onAd() }
        daily = entry(Icon.GIFT, "PRÊMIO DIÁRIO") { app.showDailyReward(force = true) }
        val menu = Ui.column(app)
        listOf(missions, pvp, chest, video, daily).forEachIndexed { index, e ->
            menu.addView(e.root, Ui.lp(Ui.MATCH, app.dpi(29)).apply { bottomMargin = if (index < 4) app.dpi(5) else 0 })
        }
        addView(menu, Ui.frame(app.dpi(120), Ui.WRAP, Gravity.TOP or Gravity.START))

        // Barra inferior: COFRE • ARMAS • FASES
        val bar = Ui.row(app).apply {
            background = PanelDrawable(app, Color.parseColor("#D90B0720"), Color.parseColor("#668B5BFF"), 8f)
            setPadding(app.dpi(4), app.dpi(2), app.dpi(4), app.dpi(2))
        }
        fun barItem(icon: Icon, label: String, onClick: () -> Unit) {
            val item = Ui.row(app, Gravity.CENTER).apply {
                isClickable = true
                isFocusable = true
                setPadding(app.dpi(4), 0, app.dpi(4), 0)
                setOnClickListener {
                    Ui.clickSound?.invoke()
                    onClick()
                }
                setOnTouchListener { v, e ->
                    v.alpha = if (e.actionMasked == MotionEvent.ACTION_DOWN) 0.6f else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) 1f else v.alpha
                    false
                }
            }
            item.addView(IconView(app, icon, Palette.text), Ui.lp(app.dpi(18), app.dpi(18)))
            item.addView(Ui.title(app, label, 11f).apply {
                typeface = Fonts.bold
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(app.dpi(5), 0, 0, 0)
            }, Ui.lp(Ui.WRAP, Ui.WRAP))
            if (bar.childCount > 0) bar.addView(View(app).apply { setBackgroundColor(Color.argb(70, 255, 255, 255)) }, Ui.lp(app.dpi(1), app.dpi(18)))
            bar.addView(item, Ui.lp(0, Ui.MATCH, 1f))
        }
        barItem(Icon.VAULT, "COFRE") { app.showSkins() }
        barItem(Icon.RIFLE, "ARMAS") { app.showUpgrades() }
        barItem(Icon.MAP_FLAG, "FASES") { app.showStages() }
        addView(bar, Ui.frame(app.dpi(330), app.dpi(31), Gravity.BOTTOM or Gravity.START))

        // Modo/fase atual + botão JOGAR (canto inferior direito)
        val current = Campaign.stage(p.frontierStage())
        val dock = Ui.column(app).apply { gravity = Gravity.END }
        val mode = Ui.row(app).apply {
            background = PanelDrawable(app, Color.parseColor("#E61A1140"), Color.parseColor("#99B07CFF"), 8f)
            setPadding(app.dpi(6), app.dpi(4), app.dpi(6), app.dpi(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Ui.clickSound?.invoke()
                app.showStages()
            }
        }
        mode.addView(
            Ui.title(app, "${current.depth}", 14f, Palette.dark).apply {
                gravity = Gravity.CENTER
                background = BlockDrawable(app, current.zone.color, 6f, 3f)
            },
            Ui.lp(app.dpi(28), app.dpi(28)),
        )
        val modeText = Ui.column(app).apply { setPadding(app.dpi(6), 0, app.dpi(4), 0) }
        modeText.addView(Ui.title(app, "FASE ${current.depth} • ${current.title.uppercase()}", 10.5f, Palette.cyan).apply {
            typeface = Fonts.bold
            letterSpacing = 0.01f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        modeText.addView(Ui.text(app, "ZONA ${current.zone.index + 1} • ${current.zone.name.uppercase()} • MAPA ALEATÓRIO", 7.5f, current.zone.color, Fonts.bold).apply {
            letterSpacing = 0.01f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        mode.addView(modeText, Ui.lp(0, Ui.WRAP, 1f))
        mode.addView(IconView(app, Icon.SWORDS, Palette.text), Ui.lp(app.dpi(16), app.dpi(16)))
        dock.addView(mode, Ui.lp(Ui.MATCH, app.dpi(40)).apply { bottomMargin = app.dpi(4) })
        val play = Ui.button(app, "JOGAR", Palette.accent, Palette.dark, 24f) { app.showExpeditionPrep(ExpeditionKind.CAMPAIGN, current.number) }.apply {
            includeFontPadding = false
            setPadding(app.dpi(8), 0, app.dpi(8), app.dpi(3))
        }
        dock.addView(play, Ui.lp(app.dpi(160), app.dpi(41)))
        addView(dock, Ui.frame(app.dpi(202), Ui.WRAP, Gravity.BOTTOM or Gravity.END))

        // Usa os limites reais do lobby para manter as regiões separadas em paisagem compacta.
        fun layoutLobby(w: Int, h: Int) {
            val widthDp = w / resources.displayMetrics.density
            val inset = app.dpi((16f + (widthDp - 480f).coerceIn(0f, 160f) * 0.15f).toInt())
            val usableWidth = (w - 2 * inset).coerceAtLeast(0)
            val rightWidth = app.dpi(if (widthDp < 600f) 136 else 152)
            val currencyWidth = minOf(app.dpi(if (widthDp < 600f) 128 else 134), (usableWidth - rightWidth - app.dpi(104)).coerceAtLeast(app.dpi(64)))
            val cardWidth = minOf(app.dpi(142), (usableWidth - rightWidth - currencyWidth - app.dpi(20)).coerceAtLeast(app.dpi(84)))
            card.layoutParams = Ui.lp(cardWidth, Ui.WRAP)
            currencies.layoutParams = Ui.lp(currencyWidth, Ui.WRAP).apply { marginStart = app.dpi(8) }
            topLeft.layoutParams = Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(inset, app.dpi(8), 0, 0) }
            brand.textSize = if (widthDp < 600f) 14f else 16f
            topRight.layoutParams = Ui.frame(rightWidth, app.dpi(30), Gravity.TOP or Gravity.END).apply { setMargins(0, app.dpi(8), inset, 0) }
            stats.layoutParams = Ui.frame(app.dpi(100), Ui.WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, app.dpi(48), inset, 0) }
            val menuTop = minOf((h * 0.29f).toInt(), (h - app.dpi(218)).coerceAtLeast(app.dpi(48)))
            menu.layoutParams = Ui.frame(app.dpi(120), Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(inset, menuTop, 0, 0) }
            val dockWidth = minOf(app.dpi(202), (usableWidth - app.dpi(214)).coerceAtLeast(app.dpi(160)))
            val barWidth = minOf(app.dpi(330), (usableWidth - dockWidth - app.dpi(12)).coerceAtLeast(0))
            bar.layoutParams = Ui.frame(barWidth, app.dpi(31), Gravity.BOTTOM or Gravity.START).apply { setMargins(inset, 0, 0, app.dpi(10)) }
            dock.layoutParams = Ui.frame(dockWidth, Ui.WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, inset, app.dpi(6)) }
        }
        layoutLobby(app.dpi(resources.configuration.screenWidthDp), app.dpi(resources.configuration.screenHeightDp))
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) layoutLobby(right - left, bottom - top)
        }

        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    fun refresh() {
        currencies.removeAllViews()
        currencies.addView(lobbyResources(), Ui.frame(Ui.MATCH, Ui.WRAP))
        val p = app.progress
        stagesValue.text = "${p.completedStages()}/${p.highestUnlockedStage()}"
        starsValue.text = "${p.totalStars()}/${p.highestUnlockedStage() * 3}"
        heroesValue.text = "${p.heroes.size}/${Cosmetics.all.size}"
        missions.badge.setCount(Rules.claimableDailyCount(p))
        daily.badge.setCount(if (Rules.canClaimCalendar(p, app.today())) 1 else 0)
        val remaining = Rules.adsRemaining(p, app.today())
        setSub(video, if (remaining > 0) "+${Rules.AD_DIAMONDS} ◆  ($remaining)" else "VOLTE AMANHÃ")
        Ui.setEnabledLook(video.root, remaining > 0)
        updateChest()
    }

    private fun updateChest() {
        val now = System.currentTimeMillis()
        val ready = Rules.chestReady(app.progress, now)
        chest.bg.fill = if (ready) Palette.accent else menuFill
        chest.title.text = if (ready) "BAÚ GRÁTIS!" else "BAÚ"
        chest.title.setTextColor(if (ready) Palette.dark else Palette.text)
        chest.icon.set(Icon.CHEST, if (ready) Palette.dark else Palette.accent)
        if (ready) {
            setSub(chest, null)
        } else {
            val left = (app.progress.chestReadyAt - now) / 1000
            setSub(chest, "%d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60))
        }
        chest.bg.invalidateSelf()
    }

    private fun onChest() {
        val now = System.currentTimeMillis()
        if (Rules.chestReady(app.progress, now)) {
            val got = Rules.openChest(app.progress, now)
            app.audio.playUi("reward", 1f)
            app.save()
            refresh()
            app.showReward("◆ $got DIAMANTES", "Baú de suprimentos • um novo baú fica pronto em 4 horas.") { app.requestNotificationsOnce() }
        } else {
            Popup.show(app, "Baú trancado", "O próximo baú abre sozinho em algumas horas. Quer abrir agora assistindo a um anúncio?", "ABRIR COM ANÚNCIO", "ESPERAR") { ok ->
                if (ok) app.watchAd {
                    app.progress.chestReadyAt = 0L
                    onChest()
                }
            }
        }
    }

    private fun onAd() {
        if (Rules.adsRemaining(app.progress, app.today()) <= 0) return
        app.watchAd {
            val got = Rules.claimAdDiamonds(app.progress, app.today())
            if (got > 0) app.audio.playUi("reward", 1f)
            refresh()
        }
    }
}
