package com.fabiosilva.ecosdeeldra.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Sala de espera do PvP em Wi-Fi local (UDP, porta 47777).
 * Detecção automática das salas (ninguém precisa digitar IP), por vários caminhos ao mesmo tempo,
 * porque cada roteador/hotspot bloqueia um tipo diferente de pacote:
 *  - o anfitrião anuncia a sala por broadcast e multicast;
 *  - quem procura pergunta ("DISCOVER") por broadcast, multicast, ao gateway (hotspot) e a cada IP
 *    da sub-rede — o anfitrião responde direto a quem perguntou;
 *  - e o Android NSD (mDNS/DNS-SD) registra e encontra o serviço "_zombiezone._udp".
 * "Entrar automaticamente" entra na primeira sala livre encontrada. Conectar por IP continua existindo.
 * Ao iniciar, todos recebem a mesma semente do mundo e a lista de jogadores; a partida
 * em si roda no motor C++ (porta 47778).
 */
class LanLobby(context: Context, private val listener: Listener) {
    interface Listener {
        fun onRooms(rooms: List<Room>)
        fun onLobby(players: List<LobbyPlayer>, rules: Rules, localId: Int, isHost: Boolean)
        fun onStart(info: StartInfo)
        fun onClosed(reason: String)
        fun onAutoJoin(room: Room) = Unit
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
        /** Só no anfitrião: IPv4 (big-endian) de cada jogador por id; o motor confere o remetente dos pacotes. */
        val memberIps: Map<Int, Int> = emptyMap(),
    )

    private enum class Role { SCANNING, HOST, CLIENT }

    private class Member(val id: Int, var skin: Int, var name: String, var address: InetAddress, var port: Int, val nonce: String, var lastSeen: Long)

    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val nsd: NsdManager? = runCatching { context.applicationContext.getSystemService(NsdManager::class.java) }.getOrNull()
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var nsdDiscovery: NsdManager.DiscoveryListener? = null
    private val nsdQueue = ArrayDeque<NsdServiceInfo>()
    @Volatile private var nsdResolving = false
    private val nsdHosts = ConcurrentHashMap<String, Long>()  // IPs achados pelo NSD (perguntamos direto a eles)
    @Volatile private var lastSweep = 0L
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

    // Entrada automática: entra na primeira sala livre que aparecer.
    @Volatile var autoJoining = false
        private set
    @Volatile private var autoName = ""
    @Volatile private var autoSkin = 0

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
            socket = openSocket()
            running = true
            Thread({ loop() }, "lan-lobby").apply { isDaemon = true }.start()
            startNsdDiscovery()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível abrir a porta $PORT", e)
            close()
            false
        }
    }

    /** Socket que também escuta o grupo multicast (alguns roteadores bloqueiam broadcast, não multicast). */
    private fun openSocket(): DatagramSocket {
        val multicast = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 250
                bind(InetSocketAddress(PORT))
                val group = InetSocketAddress(InetAddress.getByName(GROUP), PORT)
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                    .forEach { ni -> runCatching { joinGroup(group, ni) } }
            }
        }.getOrNull()
        return multicast ?: DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 250
            bind(InetSocketAddress(PORT))
        }
    }

    /** Procura uma sala e entra sozinho na primeira livre (sem digitar IP). */
    fun autoJoin(name: String, skin: Int) {
        autoName = sanitize(name)
        autoSkin = skin
        autoJoining = true
        lastSweep = 0L
        sender.execute { discover(SystemClock.elapsedRealtime(), sweep = true) }
        tryAutoJoin()
    }

    fun cancelAutoJoin() {
        autoJoining = false
    }

    private fun tryAutoJoin() {
        if (!autoJoining || role != Role.SCANNING) return
        val room = rooms.values.filter { !it.inMatch && it.players < it.max }.maxByOrNull { it.seenAt } ?: return
        autoJoining = false
        main.post { listener.onAutoJoin(room) }
        join(room.address, autoName, autoSkin)
    }

    fun close() {
        stopNsd()
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
        autoJoining = false
        hostName = sanitize(name)
        hostSkin = skin
        this.rules = rules
        members.clear()
        inMatch = false
        registerNsd()
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
        val ips = members.values.mapNotNull { m ->
            val b = (m.address as? java.net.Inet4Address)?.address ?: return@mapNotNull null
            m.id to ((b[0].toInt() and 255 shl 24) or (b[1].toInt() and 255 shl 16) or (b[2].toInt() and 255 shl 8) or (b[3].toInt() and 255))
        }.toMap()
        val payload = "START|$seed|${rules.killLimit}|${rules.timeMinutes}|${encodePlayers(players)}"
        Thread {
            repeat(6) {
                members.values.forEach { m -> send(payload, m.address, m.port) }
                Thread.sleep(120)
            }
        }.start()
        main.post { listener.onStart(StartInfo(true, "127.0.0.1", 0, seed, rules, players, ips)) }
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
        unregisterNsd()
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
                handle(packet.data, packet.length, packet.address, packet.port)
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
                rooms.entries.removeIf { now - it.value.seenAt > 5000 }
                if (rooms.size != before) notifyRooms()
                // Pergunta ativamente quem está hospedando (varre a sub-rede a cada 4 s).
                val sweep = now - lastSweep > 4000
                if (sweep) lastSweep = now
                discover(now, sweep)
            }
            Role.HOST -> {
                val beacon = beacon()
                broadcastTargets().forEach { send(beacon, it, PORT) }
                runCatching { send(beacon, InetAddress.getByName(GROUP), PORT) }
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

    private fun handle(bytes: ByteArray, length: Int, from: InetAddress, port: Int) {
        // Tamanho, prefixo, campos e faixas são validados em LanWire.parse; o que não passar é ignorado.
        val msg = LanWire.parse(bytes, length) ?: return
        val now = SystemClock.elapsedRealtime()
        when (msg) {
            is LanWire.Msg.Beacon -> if (role == Role.SCANNING && !isLocalAddress(from)) {
                val key = from.hostAddress ?: return
                val old = rooms[key]
                if (old == null && rooms.size >= MAX_ROOMS) return  // lista de salas não cresce sem limite
                val room = Room(key, msg.name, msg.players, msg.max, msg.inMatch, now)
                rooms[key] = room
                if (old == null || old.players != room.players || old.inMatch != room.inMatch || old.name != room.name) notifyRooms()
                tryAutoJoin()
            }
            is LanWire.Msg.Discover -> if (role == Role.HOST && !isLocalAddress(from)) send(beacon(), from, port)
            is LanWire.Msg.Join -> if (role == Role.HOST) handleJoin(msg.nonce, msg.skin, msg.name, from, port, now)
            is LanWire.Msg.Ping -> if (role == Role.HOST) {
                val m = members[msg.id]
                if (m != null && m.address == from) m.lastSeen = now
            }
            is LanWire.Msg.Leave -> if (role == Role.HOST) {
                if (members[msg.id]?.address == from && members.remove(msg.id) != null) {
                    broadcastRoom()
                    notifyLobby()
                }
            }
            is LanWire.Msg.Accept -> if (role == Role.CLIENT && msg.nonce == nonce && from == hostAddress) {
                localId = msg.id
                lastHostMessage = now
            }
            is LanWire.Msg.Reject -> if (role == Role.CLIENT && msg.nonce == nonce && from == hostAddress) {
                role = Role.SCANNING
                main.post { listener.onClosed(msg.reason) }
            }
            is LanWire.Msg.Room -> if (role == Role.CLIENT && from == hostAddress) {
                lastHostMessage = now
                val r = Rules(msg.rules.killLimit, msg.rules.timeMinutes)
                if (!msg.inMatch) started = false
                val players = msg.players.map { LobbyPlayer(it.id, it.skin, it.name) }
                if (localId >= 0 && !started) main.post { listener.onLobby(players, r, localId, false) }
            }
            is LanWire.Msg.Start -> if (role == Role.CLIENT && from == hostAddress && !started && localId >= 0) {
                started = true
                lastHostMessage = now
                val info = StartInfo(
                    false, from.hostAddress ?: "", localId, msg.seed,
                    Rules(msg.rules.killLimit, msg.rules.timeMinutes), msg.players.map { LobbyPlayer(it.id, it.skin, it.name) },
                )
                main.post { listener.onStart(info) }
            }
            LanWire.Msg.Close -> if (role == Role.CLIENT && from == hostAddress) {
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
        if (!LanWire.canAdmit(members.values.map { it.address.hostAddress ?: "" }, from.hostAddress ?: "")) {
            send("REJECT|$joinNonce|Muitas entradas deste aparelho.", from, port)
            return
        }
        val id = (1 until MAX).firstOrNull { it !in members.keys }
        if (id == null) {
            send("REJECT|$joinNonce|Sala cheia.", from, port)
            return
        }
        members[id] = Member(id, skin, name, from, port, joinNonce, now)
        send("ACCEPT|$joinNonce|$id", from, port)
        broadcastRoom()
        notifyLobby()
    }

    private fun beacon(): String = "BEACON|${hostName}|${members.size + 1}|$MAX|${if (inMatch) 1 else 0}"

    /** Pergunta "tem sala aí?" por todos os caminhos possíveis. */
    private fun discover(now: Long, sweep: Boolean) {
        val targets = LinkedHashSet<InetAddress>()
        targets.addAll(broadcastTargets())
        runCatching { targets.add(InetAddress.getByName(GROUP)) }
        gatewayAddress()?.let { targets.add(it) }
        nsdHosts.entries.removeIf { now - it.value > 60_000 }
        nsdHosts.keys.forEach { ip -> runCatching { targets.add(InetAddress.getByName(ip)) } }
        if (sweep) targets.addAll(subnetHosts())
        val payload = "DISCOVER|$now"
        targets.forEach { send(payload, it, PORT) }
    }

    /** Gateway do Wi-Fi: quando o anfitrião é o hotspot, ele é o próprio gateway. */
    @Suppress("DEPRECATION")
    private fun gatewayAddress(): InetAddress? = runCatching {
        val g = wifi?.dhcpInfo?.gateway ?: 0
        if (g == 0) null
        else InetAddress.getByAddress(byteArrayOf((g and 0xFF).toByte(), (g shr 8 and 0xFF).toByte(), (g shr 16 and 0xFF).toByte(), (g shr 24 and 0xFF).toByte()))
    }.getOrNull()

    /** Todos os IPs da sub-rede /24 de cada interface local (para quando broadcast e multicast são bloqueados). */
    private fun subnetHosts(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { ni ->
                ni.interfaceAddresses.forEach { ia ->
                    val addr = ia.address as? Inet4Address ?: return@forEach
                    if (!addr.isSiteLocalAddress) return@forEach
                    val b = addr.address
                    for (last in 1..254) {
                        if (last == (b[3].toInt() and 0xFF)) continue
                        out.add(InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], last.toByte())))
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ NSD (mDNS / DNS-SD)

    private fun registerNsd() {
        val manager = nsd ?: return
        unregisterNsd()
        val info = NsdServiceInfo().apply {
            serviceName = "ZZ-" + hostName.replace(Regex("[^A-Za-z0-9 _-]"), "").ifEmpty { "Sala" }
            serviceType = NSD_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdRegistration = listener
        }.onFailure { Log.w(TAG, "NSD indisponível: ${it.message}") }
    }

    private fun unregisterNsd() {
        val l = nsdRegistration ?: return
        nsdRegistration = null
        runCatching { nsd?.unregisterService(l) }
    }

    private fun startNsdDiscovery() {
        val manager = nsd ?: return
        if (nsdDiscovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(nsdQueue) { nsdQueue.addLast(serviceInfo) }
                resolveNext()
            }
        }
        runCatching {
            manager.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdDiscovery = listener
        }.onFailure { Log.w(TAG, "Descoberta NSD falhou: ${it.message}") }
    }

    /** O NSD só resolve um serviço por vez: fila simples. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val manager = nsd ?: return
        val next = synchronized(nsdQueue) {
            if (nsdResolving || nsdQueue.isEmpty()) return
            nsdResolving = true
            nsdQueue.removeFirst()
        }
        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    nsdResolving = false
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    nsdResolving = false
                    val host = serviceInfo.host
                    if (host is Inet4Address && !isLocalAddress(host)) {
                        host.hostAddress?.let { nsdHosts[it] = SystemClock.elapsedRealtime() }
                        send("DISCOVER|nsd", host, PORT)
                    }
                    resolveNext()
                }
            })
        }.onFailure {
            nsdResolving = false
        }
    }

    private fun stopNsd() {
        unregisterNsd()
        nsdDiscovery?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        nsdDiscovery = null
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
        const val PREFIX = LanWire.PREFIX
        const val GROUP = "239.255.77.77"
        const val NSD_TYPE = "_zombiezone._udp."
        const val MAX = LanWire.MAX_PLAYERS

        const val MAX_ROOMS = 32  // salas guardadas na lista de busca

        fun sanitize(name: String): String = LanWire.sanitizeName(name)

        fun encodePlayers(players: List<LobbyPlayer>): String =
            LanWire.encodePlayers(players.map { LanWire.Player(it.id, it.skin, it.name) })

        fun decodePlayers(raw: String): List<LobbyPlayer> =
            LanWire.decodePlayers(raw).map { LobbyPlayer(it.id, it.skin, it.name) }  // skin = aparência (Look codificado)

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
