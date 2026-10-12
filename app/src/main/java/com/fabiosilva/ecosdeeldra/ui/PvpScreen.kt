package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Look
import com.fabiosilva.ecosdeeldra.data.LookPainter
import com.fabiosilva.ecosdeeldra.net.LanLobby

/** PvP em Wi-Fi local: procurar/criar sala, sala de espera e regras da partida. */
@SuppressLint("ViewConstructor")
class PvpScreen(private val app: MainActivity, private val lobby: LanLobby) : FrameLayout(app) {
    private val body = FrameLayout(app)
    private var rooms: List<LanLobby.Room> = emptyList()
    private var roomsList: LinearLayout? = null

    init {
        setBackgroundColor(Color.argb(170, 6, 10, 20))
        Ui.blockTouches(this)
        val column = Ui.column(app)
        column.addView(Header.build(app, "PvP LOCAL", "MESMO WI-FI • 2 A 8 JOGADORES • SEM MIRA ASSISTIDA") { onBack() })
        column.addView(body, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(20), app.dpi(10), app.dpi(20), app.dpi(14)) })
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        val last = app.lastLobbyState
        if (last != null) showRoom(last.players, last.rules, last.localId, last.isHost) else showBrowse()
    }

    private fun onBack() {
        if (lobby.isHost || app.lastLobbyState != null) {
            lobby.leaveRoom()
            app.lastLobbyState = null
            showBrowse()
        } else {
            app.closePvp()
        }
    }

    fun playerName(): String {
        val s = app.progress.settings
        if (s.playerName.isBlank()) {
            s.playerName = app.progress.currentHero.name
            app.save()
        }
        return s.playerName
    }

    // ------------------------------------------------------------------ procurar salas

    fun showBrowse() {
        body.removeAllViews()
        val row = Ui.row(app, Gravity.TOP)
        val left = Ui.panel(app)
        left.addView(Ui.text(app, "SEU NOME", 10f, Palette.lime, Fonts.title))
        val name = EditText(app).apply {
            setText(playerName())
            setTextColor(Color.WHITE)
            setHintTextColor(Palette.muted)
            hint = "Seu nome"
            textSize = 16f
            typeface = Fonts.bold
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(14))
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = PanelDrawable(app, Color.argb(120, 0, 0, 0), Palette.line, 8f)
            setPadding(app.dpi(10), app.dpi(8), app.dpi(10), app.dpi(8))
        }
        name.setOnFocusChangeListener { _, focused ->
            if (!focused) saveName(name.text.toString())
        }
        left.addView(name, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(4) })
        left.addView(Ui.text(app, "Personagem: ${app.progress.currentHero.name}  •  Seu IP: ${LanLobby.localIp() ?: "sem Wi-Fi"}", 11f, Palette.muted).apply { setPadding(0, app.dpi(8), 0, app.dpi(10)) })
        left.addView(Ui.button(app, "CRIAR SALA", Palette.lime, Palette.dark, 16f) {
            saveName(name.text.toString())
            lobby.host(playerName(), app.progress.lookCode, LanLobby.Rules(10, 5))
        }, Ui.lp(Ui.MATCH, app.dpi(50)))
        // Acha a sala sozinho, sem ninguém precisar passar o IP (conectar por IP continua abaixo).
        val auto = Ui.button(app, if (lobby.autoJoining) "✕ PARAR BUSCA AUTOMÁTICA" else "🔍 ENTRAR AUTOMATICAMENTE", Palette.cyan, Palette.dark, 14f) {
            saveName(name.text.toString())
            if (lobby.autoJoining) lobby.cancelAutoJoin() else lobby.autoJoin(playerName(), app.progress.lookCode)
            showBrowse()
        }
        left.addView(auto, Ui.lp(Ui.MATCH, app.dpi(48)).apply { topMargin = app.dpi(8) })
        if (lobby.autoJoining) Ui.pulse(auto)
        left.addView(Ui.button(app, "CONECTAR POR IP", Palette.stone, Color.WHITE, 13f) {
            saveName(name.text.toString())
            askIp()
        }, Ui.lp(Ui.MATCH, app.dpi(42)).apply { topMargin = app.dpi(8) })
        left.addView(Ui.text(app, "A busca automática encontra a sala sozinha no mesmo Wi-Fi (ou no hotspot de um dos celulares) — ninguém precisa digitar IP. Se a rede bloquear, use CONECTAR POR IP. Na partida não há mira assistida.", 11f, Palette.muted).apply {
            setPadding(0, app.dpi(10), 0, 0)
            setLineSpacing(0f, 1.15f)
        })
        row.addView(left, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = app.dpi(12) })

        val right = Ui.panel(app)
        right.addView(Ui.text(app, "SALAS ENCONTRADAS AUTOMATICAMENTE", 10f, Palette.lime, Fonts.title))
        val list = Ui.column(app)
        roomsList = list
        right.addView(ScrollView(app).apply { addView(list) }, Ui.lp(Ui.MATCH, Ui.MATCH))
        row.addView(right, Ui.lp(0, Ui.MATCH, 1.3f))
        body.addView(row, Ui.frame(Ui.MATCH, Ui.MATCH))
        renderRooms()
    }

    private fun saveName(raw: String) {
        val clean = LanLobby.sanitize(raw)
        if (clean != app.progress.settings.playerName) {
            app.progress.settings.playerName = clean
            app.save()
        }
    }

    fun showRooms(list: List<LanLobby.Room>) {
        rooms = list
        renderRooms()
    }

    private fun renderRooms() {
        val list = roomsList ?: return
        list.removeAllViews()
        if (rooms.isEmpty()) {
            val waiting = if (lobby.autoJoining) "🔍 Procurando uma sala para entrar...\nAssim que um amigo tocar em CRIAR SALA você entra sozinho." else "Procurando salas no Wi-Fi...\nPeça para um amigo tocar em CRIAR SALA."
            list.addView(Ui.text(app, waiting, 13f, if (lobby.autoJoining) Palette.cyan else Palette.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, app.dpi(30), 0, 0)
                setLineSpacing(0f, 1.2f)
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
            return
        }
        for (room in rooms) {
            val card = Ui.panel(app, Palette.panelSoft)
            val r = Ui.row(app)
            val texts = Ui.column(app)
            texts.addView(Ui.title(app, "Sala de ${room.name}", 15f))
            texts.addView(Ui.text(app, "${room.players}/${room.max} jogadores • ${room.address}" + if (room.inMatch) " • EM PARTIDA" else "", 11f, if (room.inMatch) Palette.gold else Palette.muted))
            r.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
            val join = Ui.button(app, "ENTRAR", Palette.lime, Palette.dark, 13f) { lobby.join(room.address, playerName(), app.progress.lookCode) }
            Ui.setEnabledLook(join, !room.inMatch && room.players < room.max)
            r.addView(join, Ui.lp(app.dpi(110), app.dpi(44)))
            card.addView(r)
            list.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(6) })
        }
    }

    /** A busca automática achou uma sala e está entrando. */
    fun showAutoJoining(room: LanLobby.Room) {
        val list = roomsList ?: return
        list.removeAllViews()
        list.addView(Ui.text(app, "Sala de ${room.name} encontrada!\nEntrando...", 15f, Palette.lime, Fonts.title).apply {
            gravity = Gravity.CENTER
            setPadding(0, app.dpi(30), 0, 0)
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun askIp() {
        val layer = FrameLayout(app).apply {
            setBackgroundColor(Color.argb(190, 4, 7, 14))
            isClickable = true
        }
        val panel = Ui.panel(app)
        panel.addView(Ui.title(app, "Conectar por IP", 18f, Palette.lime))
        panel.addView(Ui.text(app, "Digite o IP mostrado na tela do anfitrião.", 12f, Palette.muted).apply { setPadding(0, app.dpi(4), 0, app.dpi(8)) })
        val input = EditText(app).apply {
            hint = "192.168.0.10"
            setTextColor(Color.WHITE)
            setHintTextColor(Palette.muted)
            textSize = 18f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_PHONE
            background = PanelDrawable(app, Color.argb(120, 0, 0, 0), Palette.line, 8f)
            setPadding(app.dpi(10), app.dpi(8), app.dpi(10), app.dpi(8))
        }
        panel.addView(input, Ui.lp(Ui.MATCH, Ui.WRAP))
        val buttons = Ui.row(app)
        buttons.addView(Ui.button(app, "CANCELAR", Palette.stone, Color.WHITE, 13f) { removeView(layer) }, Ui.lp(0, app.dpi(46), 1f).apply { marginEnd = app.dpi(8) })
        buttons.addView(Ui.button(app, "CONECTAR", Palette.lime, Palette.dark, 13f) {
            val ip = input.text.toString().trim()
            removeView(layer)
            if (ip.isNotEmpty()) lobby.join(ip, playerName(), app.progress.lookCode)
        }, Ui.lp(0, app.dpi(46), 1f))
        panel.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = app.dpi(10) })
        layer.addView(panel, Ui.frame(app.dpi(380), Ui.WRAP, Gravity.CENTER))
        addView(layer, Ui.frame(Ui.MATCH, Ui.MATCH))
        input.requestFocus()
    }

    // ------------------------------------------------------------------ sala de espera

    fun showRoom(players: List<LanLobby.LobbyPlayer>, rules: LanLobby.Rules, localId: Int, isHost: Boolean) {
        roomsList = null
        body.removeAllViews()
        val row = Ui.row(app, Gravity.TOP)
        val left = Ui.panel(app)
        val hostName = players.firstOrNull { it.id == 0 }?.name ?: "anfitrião"
        left.addView(Ui.text(app, "SALA DE ${hostName.uppercase()} • ${players.size}/${LanLobby.MAX}", 10f, Palette.lime, Fonts.title))
        val list = Ui.column(app)
        for (p in players.sortedBy { it.id }) {
            val r = Ui.row(app).apply { setPadding(0, app.dpi(6), 0, 0) }
            val bitmap = LookPainter.face(Look.decode(p.skin))
            r.addView(ImageView(app).apply { setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false }) }, Ui.lp(app.dpi(34), app.dpi(34)))
            val tag = buildString {
                if (p.id == 0) append("  • anfitrião")
                if (p.id == localId) append("  • você")
            }
            r.addView(Ui.text(app, p.name + tag, 14f, if (p.id == localId) Palette.lime else Color.WHITE, Fonts.bold).apply { setPadding(app.dpi(10), 0, 0, 0) })
            list.addView(r)
        }
        left.addView(ScrollView(app).apply { addView(list) }, Ui.lp(Ui.MATCH, Ui.MATCH))
        row.addView(left, Ui.lp(0, Ui.MATCH, 1f).apply { marginEnd = app.dpi(12) })

        val right = Ui.panel(app)
        right.addView(Ui.text(app, "VENCE QUEM CHEGAR A", 10f, Palette.lime, Fonts.title))
        val kills = Ui.row(app).apply { setPadding(0, app.dpi(4), 0, app.dpi(10)) }
        for (k in listOf(5, 10, 15, 20)) {
            val selected = rules.killLimit == k
            val b = Ui.button(app, "$k", if (selected) Palette.lime else Palette.stone, if (selected) Palette.dark else Color.WHITE, 13f) {
                if (isHost) lobby.setRules(rules.copy(killLimit = k))
            }
            kills.addView(b, Ui.lp(0, app.dpi(40), 1f).apply { marginEnd = app.dpi(6) })
        }
        right.addView(kills)
        right.addView(Ui.text(app, "TEMPO DA PARTIDA", 10f, Palette.lime, Fonts.title))
        val times = Ui.row(app).apply { setPadding(0, app.dpi(4), 0, app.dpi(10)) }
        for (m in listOf(3, 5, 8)) {
            val selected = rules.timeMinutes == m
            val b = Ui.button(app, "$m min", if (selected) Palette.lime else Palette.stone, if (selected) Palette.dark else Color.WHITE, 13f) {
                if (isHost) lobby.setRules(rules.copy(timeMinutes = m))
            }
            times.addView(b, Ui.lp(0, app.dpi(40), 1f).apply { marginEnd = app.dpi(6) })
        }
        right.addView(times)
        if (isHost) {
            right.addView(Ui.text(app, "Seu IP: ${LanLobby.localIp() ?: "?"} (para conectar manualmente)", 11f, Palette.muted))
            val start = Ui.button(app, "INICIAR PARTIDA ▶", Palette.lime, Palette.dark, 17f) { lobby.startMatch() }
            Ui.setEnabledLook(start, players.size >= 2)
            right.addView(start, Ui.lp(Ui.MATCH, app.dpi(56)).apply { topMargin = app.dpi(10) })
            if (players.size < 2) right.addView(Ui.text(app, "Aguardando outro jogador entrar...", 12f, Palette.gold).apply { setPadding(0, app.dpi(6), 0, 0) })
        } else {
            right.addView(Ui.text(app, "Aguardando o anfitrião iniciar a partida...", 14f, Palette.gold, Fonts.bold).apply { setPadding(0, app.dpi(10), 0, 0) })
        }
        row.addView(right, Ui.lp(0, Ui.WRAP, 1.2f))
        body.addView(row, Ui.frame(Ui.MATCH, Ui.MATCH))
    }
}

/** Placar final da partida PvP. */
@SuppressLint("ViewConstructor")
class PvpResultOverlay(
    app: MainActivity,
    ranking: List<Triple<Int, Int, Int>>, // id, abates, mortes
    names: Map<Int, String>,
    winner: Int,
    localId: Int,
    reward: Int,
    isHost: Boolean,
) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(200, 4, 7, 14))
        isClickable = true
        val panel = Ui.panel(app)
        val won = winner == localId
        panel.addView(Ui.title(app, if (won) "VOCÊ VENCEU!" else "FIM DE PARTIDA", 28f, if (won) Palette.gold else Palette.lime))
        val winnerName = names[winner] ?: "—"
        panel.addView(Ui.text(app, if (won) "Você foi o melhor da sala!" else "Vencedor: $winnerName", 13f, Palette.muted).apply { setPadding(0, app.dpi(2), 0, app.dpi(10)) })
        ranking.forEachIndexed { pos, (id, k, d) ->
            val r = Ui.row(app).apply { setPadding(0, app.dpi(3), 0, app.dpi(3)) }
            val color = if (id == localId) Palette.lime else Color.WHITE
            r.addView(Ui.title(app, "${pos + 1}º", 15f, if (pos == 0) Palette.gold else Palette.muted), Ui.lp(app.dpi(40), Ui.WRAP))
            r.addView(Ui.text(app, names[id] ?: "Jogador ${id + 1}", 14f, color, Fonts.bold), Ui.lp(0, Ui.WRAP, 1f))
            r.addView(Ui.text(app, "☠ $k   ✝ $d", 14f, color, Fonts.bold))
            panel.addView(r)
        }
        panel.addView(Ui.text(app, "Recompensa: ◆ $reward", 14f, Palette.cyan, Fonts.title).apply { setPadding(0, app.dpi(10), 0, app.dpi(10)) })
        val buttons = Ui.row(app)
        buttons.addView(Ui.button(app, "MENU", Palette.stone, Color.WHITE, 13f) { app.closePvp() }, Ui.lp(0, app.dpi(48), 0.8f).apply { marginEnd = app.dpi(8) })
        buttons.addView(Ui.button(app, "VOLTAR À SALA", Palette.lime, Palette.dark, 14f) { app.backToPvpRoom() }, Ui.lp(0, app.dpi(48), 1.2f))
        panel.addView(buttons)
        if (isHost) {
            panel.addView(Ui.text(app, "Na sala você pode iniciar uma nova partida com um mapa novo.", 11f, Palette.muted).apply { setPadding(0, app.dpi(8), 0, 0) })
        }
        addView(panel, Ui.frame(app.dpi(440), Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
    }
}

/** No PvP o jogo não pausa: só oferece continuar ou sair. */
@SuppressLint("ViewConstructor")
class PvpPauseOverlay(app: MainActivity) : FrameLayout(app) {
    init {
        setBackgroundColor(Color.argb(140, 4, 7, 14))
        isClickable = true
        val panel = Ui.panel(app)
        panel.gravity = Gravity.CENTER_HORIZONTAL
        panel.addView(Ui.title(app, "MENU", 24f, Palette.lime))
        panel.addView(Ui.text(app, "A partida continua rodando para os outros jogadores.", 12f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, app.dpi(4), 0, app.dpi(12))
        })
        panel.addView(Ui.button(app, "CONTINUAR ▶", Palette.lime, Palette.dark, 15f) { app.resumePvp() }, Ui.lp(Ui.MATCH, app.dpi(50)).apply { bottomMargin = app.dpi(8) })
        panel.addView(Ui.button(app, "SAIR DA PARTIDA", Palette.red, Color.WHITE, 14f) { app.leavePvpMatch() }, Ui.lp(Ui.MATCH, app.dpi(48)))
        addView(panel, Ui.frame(app.dpi(320), Ui.WRAP, Gravity.CENTER))
        Ui.popIn(panel)
    }
}

