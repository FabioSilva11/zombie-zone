package com.fabiosilva.ecosdeeldra.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Sala de espera do PvP em Wi-Fi local (UDP, porta 47777).
 * O anfitrião anuncia a sala por broadcast; os outros aparelhos a encontram e entram.
 * Ao iniciar, todos recebem a mesma semente do mundo e a lista de jogadores; a partida
 * em si roda no motor C++ (porta 47778).
 */
class LanLobby(context: Context, private val listener: Listener) {
    interface Listener {
        fun onRooms(rooms: List<Room>)
        fun onLobby(players: List<LobbyPlayer>, rules: Rules, localId: Int, isHost: Boolean)
        fun onStart(info: StartInfo)
        fun onClosed(reason: String)
    }

    data class Room(val address: String, val name: String, val players: Int, val max: Int, val inMatch: Boolean, val seenAt: Long)
    data class LobbyPlayer(val id: Int, val skin: Int, val name: String)
    data class Rules(val killLimit: Int, val timeMinutes: Int)
    data class StartInfo(
        val isHost: Boolean,
        val hostAddress: String,
        val localId: Int,
        val seed: Int,
        val rules: Rules,
        val players: List<LobbyPlayer>,
    )

    private enum class Role { SCANNING, HOST, CLIENT }

    private class Member(val id: Int, var skin: Int, var name: String, var address: InetAddress, var port: Int, val nonce: String, var lastSeen: Long)

    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val sender = Executors.newSingleThreadExecutor()
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var role = Role.SCANNING
    private val rooms = ConcurrentHashMap<String, Room>()

    // Anfitrião
    private val members = ConcurrentHashMap<Int, Member>()
    @Volatile private var hostName = "Anfitrião"
    @Volatile private var hostSkin = 0
    @Volatile var rules = Rules(10, 5)
        private set
    @Volatile var inMatch = false
        private set

    // Cliente
    @Volatile private var hostAddress: InetAddress? = null
    @Volatile private var localId = -1
    @Volatile private var nonce = ""
    @Volatile private var joinName = ""
    @Volatile private var joinSkin = 0
    @Volatile private var joinStartedAt = 0L
    @Volatile private var lastHostMessage = 0L
    @Volatile private var started = false

    val isHost: Boolean get() = role == Role.HOST

    fun start(): Boolean {
        if (running) return true
        return try {
            multicastLock = wifi?.createMulticastLock("zombie-zone-lan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 250
                bind(InetSocketAddress(PORT))
            }
            running = true
            Thread({ loop() }, "lan-lobby").apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível abrir a porta $PORT", e)
            close()
            false
        }
    }

    fun close() {
        if (role == Role.HOST) members.values.forEach { m -> repeat(3) { send("CLOSE", m.address, m.port) } }
        if (role == Role.CLIENT && localId >= 0) hostAddress?.let { h -> repeat(3) { send("LEAVE|$localId", h, PORT) } }
        running = false
        sender.execute {
            runCatching { socket?.close() }
            socket = null
        }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        role = Role.SCANNING
    }

    // ------------------------------------------------------------------ anfitrião

    fun host(name: String, skin: Int, rules: Rules) {
        role = Role.HOST
        hostName = sanitize(name)
        hostSkin = skin
        this.rules = rules
        members.clear()
        inMatch = false
        notifyLobby()
    }

    fun setRules(rules: Rules) {
        this.rules = rules
        broadcastRoom()
        notifyLobby()
    }

    /** Anfitrião: sorteia a semente e manda todos para a partida. */
    fun startMatch() {
        if (role != Role.HOST) return
        inMatch = true
        val seed = Random.nextInt(1, 16_000_000)
        val players = lobbyPlayers()
        val payload = "START|$seed|${rules.killLimit}|${rules.timeMinutes}|${encodePlayers(players)}"
        Thread {
            repeat(6) {
                members.values.forEach { m -> send(payload, m.address, m.port) }
                Thread.sleep(120)
            }
        }.start()
        main.post { listener.onStart(StartInfo(true, "127.0.0.1", 0, seed, rules, players)) }
    }

    /** Anfitrião: volta todos para a sala depois da partida. */
    fun endMatch() {
        if (role != Role.HOST) return
        inMatch = false
        broadcastRoom()
        notifyLobby()
    }

    // ------------------------------------------------------------------ cliente

    fun join(address: String, name: String, skin: Int) {
        sender.execute {
            try {
                val addr = InetAddress.getByName(address)
                hostAddress = addr
                role = Role.CLIENT
                localId = -1
                started = false
                joinName = sanitize(name)
                joinSkin = skin
                nonce = Random.nextInt(100000, 999999).toString()
                joinStartedAt = SystemClock.elapsedRealtime()
                lastHostMessage = joinStartedAt
                sendNow("JOIN|$nonce|$skin|$joinName", addr, PORT)
            } catch (e: Exception) {
                main.post { listener.onClosed("Endereço inválido: $address") }
            }
        }
    }

    fun leaveRoom() {
        if (role == Role.CLIENT && localId >= 0) hostAddress?.let { h -> repeat(3) { send("LEAVE|$localId", h, PORT) } }
        if (role == Role.HOST) members.values.forEach { m -> repeat(3) { send("CLOSE", m.address, m.port) } }
        members.clear()
        role = Role.SCANNING
        localId = -1
        hostAddress = null
        started = false
        inMatch = false
    }

    // ------------------------------------------------------------------ laço de rede

    private fun loop() {
        val buf = ByteArray(2048)
        var lastTick = 0L
        while (running) {
            val s = socket ?: break
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                handle(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address, packet.port)
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Erro na sala: ${e.message}")
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastTick >= 1000) {
                lastTick = now
                tick(now)
            }
        }
    }

    private fun tick(now: Long) {
        when (role) {
            Role.SCANNING -> {
                val before = rooms.size
                rooms.entries.removeIf { now - it.value.seenAt > 4000 }
                if (rooms.size != before) notifyRooms()
            }
            Role.HOST -> {
                val beacon = "BEACON|${hostName}|${members.size + 1}|$MAX|${if (inMatch) 1 else 0}"
                broadcastTargets().forEach { send(beacon, it, PORT) }
                val dropped = members.values.filter { now - it.lastSeen > 7000 }
                dropped.forEach { members.remove(it.id) }
                broadcastRoom()
                if (dropped.isNotEmpty()) notifyLobby()
            }
            Role.CLIENT -> {
                val h = hostAddress ?: return
                if (localId < 0) {
                    if (now - joinStartedAt > 6000) {
                        role = Role.SCANNING
                        main.post { listener.onClosed("A sala não respondeu. Confira se estão no mesmo Wi-Fi.") }
                    } else {
                        send("JOIN|$nonce|$joinSkin|$joinName", h, PORT)
                    }
                } else {
                    send("PING|$localId", h, PORT)
                    if (now - lastHostMessage > 7000 && !started) {
                        role = Role.SCANNING
                        main.post { listener.onClosed("A conexão com a sala caiu.") }
                    }
                }
            }
        }
    }

    private fun handle(message: String, from: InetAddress, port: Int) {
        val p = message.split('|')
        if (p.size < 2 || p[0] != PREFIX) return
        val now = SystemClock.elapsedRealtime()
        when (p[1]) {
            "BEACON" -> if (role == Role.SCANNING && p.size >= 6 && !isLocalAddress(from)) {
                val key = from.hostAddress ?: return
                val isNew = !rooms.containsKey(key)
                rooms[key] = Room(key, p[2], p[3].toIntOrNull() ?: 1, p[4].toIntOrNull() ?: MAX, p[5] == "1", now)
                if (isNew) notifyRooms()
            }
            "JOIN" -> if (role == Role.HOST && p.size >= 5) handleJoin(p[2], p[3].toIntOrNull() ?: 0, sanitize(p[4]), from, port, now)
            "PING" -> if (role == Role.HOST) {
                val m = members[p.getOrNull(2)?.toIntOrNull() ?: -1]
                if (m != null && m.address == from) m.lastSeen = now
            }
            "LEAVE" -> if (role == Role.HOST) {
                val id = p.getOrNull(2)?.toIntOrNull() ?: -1
                if (members[id]?.address == from && members.remove(id) != null) {
                    broadcastRoom()
                    notifyLobby()
                }
            }
            "ACCEPT" -> if (role == Role.CLIENT && p.size >= 4 && p[2] == nonce) {
                localId = p[3].toIntOrNull() ?: -1
                lastHostMessage = now
            }
            "REJECT" -> if (role == Role.CLIENT && p.getOrNull(2) == nonce) {
                role = Role.SCANNING
                main.post { listener.onClosed(p.getOrNull(3) ?: "Entrada recusada.") }
            }
            "ROOM" -> if (role == Role.CLIENT && from == hostAddress && p.size >= 6) {
                lastHostMessage = now
                val r = Rules(p[2].toIntOrNull() ?: 10, p[3].toIntOrNull() ?: 5)
                val matchRunning = p[4] == "1"
                if (!matchRunning) started = false
                val players = decodePlayers(p[5])
                if (localId >= 0 && !started) main.post { listener.onLobby(players, r, localId, false) }
            }
            "START" -> if (role == Role.CLIENT && from == hostAddress && p.size >= 6 && !started && localId >= 0) {
                started = true
                lastHostMessage = now
                val info = StartInfo(
                    false, from.hostAddress ?: "", localId, p[2].toIntOrNull() ?: 1,
                    Rules(p[3].toIntOrNull() ?: 10, p[4].toIntOrNull() ?: 5), decodePlayers(p[5]),
                )
                main.post { listener.onStart(info) }
            }
            "CLOSE" -> if (role == Role.CLIENT && from == hostAddress) {
                role = Role.SCANNING
                main.post { listener.onClosed("O anfitrião fechou a sala.") }
            }
        }
    }

    private fun handleJoin(joinNonce: String, skin: Int, name: String, from: InetAddress, port: Int, now: Long) {
        members.values.firstOrNull { it.nonce == joinNonce }?.let {
            it.lastSeen = now
            send("ACCEPT|$joinNonce|${it.id}", from, port)
            return
        }
        if (inMatch) {
            send("REJECT|$joinNonce|A partida já começou. Espere a próxima.", from, port)
            return
        }
        val id = (1 until MAX).firstOrNull { it !in members.keys }
        if (id == null) {
            send("REJECT|$joinNonce|Sala cheia.", from, port)
            return
        }
        members[id] = Member(id, skin.coerceIn(0, 17), name, from, port, joinNonce, now)
        send("ACCEPT|$joinNonce|$id", from, port)
        broadcastRoom()
        notifyLobby()
    }

    private fun lobbyPlayers(): List<LobbyPlayer> =
        listOf(LobbyPlayer(0, hostSkin, hostName)) + members.values.sortedBy { it.id }.map { LobbyPlayer(it.id, it.skin, it.name) }

    private fun broadcastRoom() {
        if (role != Role.HOST) return
        val payload = "ROOM|${rules.killLimit}|${rules.timeMinutes}|${if (inMatch) 1 else 0}|${encodePlayers(lobbyPlayers())}"
        members.values.forEach { send(payload, it.address, it.port) }
    }

    private fun notifyLobby() {
        if (role != Role.HOST) return
        val players = lobbyPlayers()
        val r = rules
        main.post { listener.onLobby(players, r, 0, true) }
    }

    private fun notifyRooms() {
        val list = rooms.values.sortedBy { it.name }
        main.post { listener.onRooms(list) }
    }

    private fun send(payload: String, address: InetAddress, port: Int) {
        sender.execute { sendNow(payload, address, port) }
    }

    private fun sendNow(payload: String, address: InetAddress, port: Int) {
        val s = socket ?: return
        runCatching {
            val bytes = "$PREFIX|$payload".toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(bytes, bytes.size, address, port))
        }
    }

    private fun isLocalAddress(address: InetAddress): Boolean = runCatching {
        NetworkInterface.getByInetAddress(address) != null
    }.getOrDefault(false)

    companion object {
        private const val TAG = "LanLobby"
        const val PORT = 47777
        const val PREFIX = "ZZ1"
        const val MAX = 8

        fun sanitize(name: String): String =
            name.replace(Regex("[|;,\\n\\r]"), " ").trim().take(14).ifEmpty { "Jogador" }

        fun encodePlayers(players: List<LobbyPlayer>): String =
            players.joinToString(";") { "${it.id},${it.skin},${sanitize(it.name)}" }

        fun decodePlayers(raw: String): List<LobbyPlayer> = raw.split(';').mapNotNull { entry ->
            val f = entry.split(',')
            if (f.size < 3) return@mapNotNull null
            val id = f[0].toIntOrNull() ?: return@mapNotNull null
            if (id !in 0 until MAX) return@mapNotNull null
            LobbyPlayer(id, (f[1].toIntOrNull() ?: 0).coerceIn(0, 17), f[2])
        }

        /** IPv4 desta rede local (para mostrar ao jogador e conectar manualmente). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        fun broadcastTargets(): List<InetAddress> {
            val list = mutableListOf<InetAddress>()
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
                    if (!ni.isUp || ni.isLoopback) return@forEach
                    ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { list.add(it) } }
                }
            }
            runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
            return list.distinct()
        }
    }
}
