package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.Skins

/** Cabeçalho padrão: botão voltar, título e recursos (estrelas/diamantes). */
object Header {
    fun build(app: MainActivity, title: String, subtitle: String, onBack: () -> Unit): LinearLayout {
        val row = Ui.row(app)
        row.setPadding(app.dpi(20), app.dpi(12), app.dpi(20), 0)
        row.addView(Ui.button(app, "‹ VOLTAR", Palette.stone, Color.WHITE, 13f, onBack), Ui.lp(Ui.WRAP, app.dpi(44)))
        val titles = Ui.column(app).apply { setPadding(app.dpi(14), 0, 0, 0) }
        titles.addView(Ui.text(app, subtitle, 10f, Palette.lime, Fonts.title))
        titles.addView(Ui.title(app, title, 22f))
        row.addView(titles, Ui.lp(0, Ui.WRAP, 1f))
        row.addView(resources(app))
        return row
    }

    fun resources(app: MainActivity): LinearLayout {
        val row = Ui.row(app)
        row.addView(Ui.chip(app, "★", app.progress.totalStars().toString(), Palette.gold))
        row.addView(View(app), Ui.lp(app.dpi(8), 1))
        row.addView(Ui.chip(app, "◆", app.progress.diamonds.toString(), Palette.cyan))
        return row
    }
}

@SuppressLint("ViewConstructor")
class HomeScreen(private val app: MainActivity) : FrameLayout(app) {
    private val chestLabel: TextView
    private val chestButton: TextView
    private val adButton: TextView
    private val missionsBadge = BadgeView(app)
    private val resources = FrameLayout(app)
    private val stagesValue = Ui.title(app, "", 15f)
    private val starsValue = Ui.title(app, "", 15f)
    private val heroesValue = Ui.title(app, "", 15f)
    private val ticker = object : Runnable {
        override fun run() {
            updateChest()
            postDelayed(this, 1000)
        }
    }

    init {
        val p = app.progress
        // Marca
        val brand = Ui.row(app)
        val logo = Ui.title(app, "Z", 26f, Palette.dark).apply {
            gravity = Gravity.CENTER
            background = BlockDrawable(app, Palette.lime, 6f, 3f)
        }
        brand.addView(logo, Ui.lp(app.dpi(48), app.dpi(48)))
        val brandText = Ui.column(app).apply { setPadding(app.dpi(10), 0, 0, 0) }
        brandText.addView(Ui.title(app, "ZOMBIE ZONE", 24f))
        brandText.addView(Ui.text(app, "ILHAS DE 5 KM • FASES INFINITAS", 10f, Palette.lime, Fonts.title))
        brand.addView(brandText)
        addView(brand, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply { setMargins(app.dpi(22), app.dpi(14), 0, 0) })

        // Recursos e ajustes
        val top = Ui.row(app)
        top.addView(resources)
        top.addView(View(app), Ui.lp(app.dpi(8), 1))
        top.addView(Ui.button(app, "⚙", Palette.stone, Color.WHITE, 18f) { app.showSettings() }, Ui.lp(app.dpi(48), app.dpi(44)))
        addView(top, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, app.dpi(16), app.dpi(22), 0) })

        // Cartão da missão atual
        val current = Campaign.stage(p.highestUnlockedStage())
        val card = Ui.panel(app)
        card.addView(Ui.text(app, "ZONA ${current.zone.index + 1} • ${current.zone.name.uppercase()}", 11f, current.zone.color, Fonts.title))
        card.addView(Ui.title(app, "Fase ${current.number} · ${current.title}", 20f).apply { setPadding(0, app.dpi(4), 0, app.dpi(2)) })
        card.addView(Ui.text(app, "Ilha de ${current.islandKm} • 3 ninhos de infecção • baús escondidos", 11f, Palette.muted).apply { setPadding(0, 0, 0, app.dpi(8)) })
        val stats = Ui.row(app)
        fun stat(label: String, value: TextView) = Ui.column(app).apply {
            background = PanelDrawable(app, Color.argb(120, 0, 0, 0), Color.argb(40, 255, 255, 255), 8f)
            setPadding(app.dpi(10), app.dpi(6), app.dpi(10), app.dpi(6))
            addView(Ui.text(app, label, 9f, Palette.muted, Fonts.title))
            addView(value)
        }
        stats.addView(stat("VENCIDAS", stagesValue), Ui.lp(0, Ui.WRAP, 1f))
        stats.addView(View(app), Ui.lp(app.dpi(6), 1))
        stats.addView(stat("ESTRELAS", starsValue), Ui.lp(0, Ui.WRAP, 1f))
        stats.addView(View(app), Ui.lp(app.dpi(6), 1))
        stats.addView(stat("HERÓIS", heroesValue), Ui.lp(0, Ui.WRAP, 1f))
        card.addView(stats)
        val play = Ui.button(app, "JOGAR  ▶", Palette.lime, Palette.dark, 20f) { app.startStage(current.number) }
        card.addView(play, Ui.lp(Ui.MATCH, app.dpi(52)).apply { topMargin = app.dpi(8) })
        Ui.pulse(play)
        val secondary = Ui.row(app)
        secondary.addView(Ui.button(app, "MAPA DAS ZONAS", Palette.stone, Color.WHITE, 13f) { app.showStages() }, Ui.lp(0, app.dpi(44), 1f).apply { marginEnd = app.dpi(6) })
        secondary.addView(Ui.button(app, "⚔ PvP WI-FI", Palette.red, Color.WHITE, 13f) { app.showPvp() }, Ui.lp(0, app.dpi(44), 1f))
        card.addView(secondary, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(6) })
        // Ancorado logo acima da barra de botões (14 de margem + 54 da barra + 12 de folga): nunca fica por trás dela.
        addView(card, Ui.frame(app.dpi(330), Ui.WRAP, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, app.dpi(22), app.dpi(80)) })

        // Herói ativo (o modelo 3D aparece à esquerda, renderizado pelo motor)
        val skin = Skins.get(p.selectedSkin)
        val heroTag = Ui.chip(app, "♦", "${skin.name.uppercase()} • ${skin.role}", Palette.lime)
        addView(heroTag, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(app.dpi(22), 0, 0, app.dpi(86)) })

        // Navegação inferior
        val nav = Ui.row(app)
        fun navButton(label: String, fill: Int, textColor: Int, onClick: () -> Unit) =
            Ui.button(app, label, fill, textColor, 12f, onClick).apply { setPadding(app.dpi(10), app.dpi(6), app.dpi(10), app.dpi(10)) }
        nav.addView(navButton("⚔ HERÓIS", Palette.stone, Color.WHITE) { app.showSkins() }, Ui.lp(0, app.dpi(54), 1f))
        nav.addView(View(app), Ui.lp(app.dpi(8), 1))
        val missions = FrameLayout(app)
        missions.addView(navButton("✔ MISSÕES", Palette.stone, Color.WHITE) { app.showMissions() }, Ui.frame(Ui.MATCH, Ui.MATCH))
        missions.addView(missionsBadge, Ui.frame(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, -app.dpi(4), -app.dpi(2), 0) })
        nav.addView(missions, Ui.lp(0, app.dpi(54), 1f))
        nav.addView(View(app), Ui.lp(app.dpi(8), 1))
        nav.addView(navButton("▲ MELHORIAS", Palette.stone, Color.WHITE) { app.showUpgrades() }, Ui.lp(0, app.dpi(54), 1f))
        nav.addView(View(app), Ui.lp(app.dpi(8), 1))
        chestButton = navButton("▣ BAÚ", Palette.stone, Color.WHITE) { onChest() }
        chestLabel = chestButton
        nav.addView(chestButton, Ui.lp(0, app.dpi(54), 1.15f))
        nav.addView(View(app), Ui.lp(app.dpi(8), 1))
        adButton = navButton("▶ +${Rules.AD_DIAMONDS} ◆", Palette.cyan, Palette.dark) { onAd() }
        nav.addView(adButton, Ui.lp(0, app.dpi(54), 1.15f))
        addView(nav, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { setMargins(app.dpi(22), 0, app.dpi(22), app.dpi(14)) })

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
        resources.removeAllViews()
        resources.addView(Header.resources(app))
        val p = app.progress
        stagesValue.text = p.completedStages().toString()
        starsValue.text = p.totalStars().toString()
        heroesValue.text = "${p.ownedSkins.size}/${Skins.all.size}"
        missionsBadge.setCount(Rules.claimableDailyCount(app.progress) + if (Rules.canClaimCalendar(app.progress, app.today())) 1 else 0)
        val remaining = Rules.adsRemaining(app.progress, app.today())
        adButton.text = if (remaining > 0) "▶ +${Rules.AD_DIAMONDS} ◆  ($remaining)" else "▶ VOLTE AMANHÃ"
        Ui.setEnabledLook(adButton, remaining > 0)
        updateChest()
    }

    private fun updateChest() {
        val now = System.currentTimeMillis()
        val ready = Rules.chestReady(app.progress, now)
        val bg = chestButton.background as BlockDrawable
        if (ready) {
            chestLabel.text = "▣ BAÚ GRÁTIS!"
            bg.fill = Palette.gold
            chestLabel.setTextColor(Palette.dark)
        } else {
            val left = (app.progress.chestReadyAt - now) / 1000
            chestLabel.text = "▣ %d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60)
            bg.fill = Palette.stone
            chestLabel.setTextColor(Color.WHITE)
        }
        bg.invalidateSelf()
    }

    private fun onChest() {
        val now = System.currentTimeMillis()
        if (Rules.chestReady(app.progress, now)) {
            val got = Rules.openChest(app.progress, now)
            app.audio.playUi("reward", 1f)
            app.save()
            Popup.show(app, "Baú de suprimentos", "Você encontrou ◆ $got diamantes!\nUm novo baú fica pronto em 4 horas.", "OBA!")
            app.requestNotificationsOnce()
            refresh()
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
