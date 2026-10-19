package com.fabiosilva.ecosdeeldra.data

enum class ExpeditionOutcome { VICTORY, DEFEAT }

data class ExpeditionRecord(
    val code: String,                 // "-" se a fase passou de 1023
    val kind: ExpeditionKind, val stage: Int, val contract: ContractId,
    val outcome: ExpeditionOutcome,
    val seconds: Int, val kills: Int, val alerts: Int,
    val contractDiamonds: Int, val totalDiamonds: Int,
    val day: String,                  // yyyy-MM-dd
)

/**
 * Histórico e contadores das expedições. Persistido como UMA string compacta (campo "expeditions" do save):
 * `v1|lc=1|ls=5|pl=0,3,...|wi=0,1,...|mo=nucleo_instavel:1|r=reg;reg`, registro = `código~V|D~tipo~fase~contrato~seg~abates~alertas~◆contrato~◆total~dia`.
 * [load] é tolerante: lixo vira padrão e nunca lança (não pode derrubar o save).
 */
class ExpeditionLog {
    var lastCampaign = ContractId.NONE
    var lastSurvival = ContractId.ENDURANCE
    val records = ArrayDeque<ExpeditionRecord>()       // mais recente primeiro, no máximo MAX_RECORDS
    val modules = linkedMapOf<String, Int>()           // 4C: "nucleo_instavel" -> quantidade
    private val plays = IntArray(SLOTS)
    private val wins = IntArray(SLOTS)

    fun playsOf(id: ContractId): Int = plays.getOrElse(id.code) { 0 }
    fun winsOf(id: ContractId): Int = wins.getOrElse(id.code) { 0 }
    fun lastOf(id: ContractId): ExpeditionRecord? = records.firstOrNull { it.contract == id }

    /** Guarda o último contrato usado em cada modo (a preparação o preseleciona). */
    fun remember(plan: ExpeditionPlan) {
        if (plan.kind == ExpeditionKind.CAMPAIGN) lastCampaign = plan.contract else lastSurvival = plan.contract
    }

    fun add(r: ExpeditionRecord) {
        val i = r.contract.code
        if (i in plays.indices) {
            plays[i]++
            if (r.outcome == ExpeditionOutcome.VICTORY) wins[i]++
        }
        records.addFirst(r)
        while (records.size > MAX_RECORDS) records.removeLast()
    }

    fun grantModule(id: String) {
        if (id.isBlank()) return
        val key = clean(id)
        modules[key] = (modules[key] ?: 0) + 1
    }

    fun encode(): String = buildString {
        append("v").append(VERSION)
        append("|lc=").append(lastCampaign.code)
        append("|ls=").append(lastSurvival.code)
        append("|pl=").append(plays.joinToString(","))
        append("|wi=").append(wins.joinToString(","))
        append("|mo=").append(modules.entries.joinToString(",") { "${it.key}:${it.value}" })
        append("|r=").append(records.joinToString(";") { encodeRecord(it) })
    }

    fun load(text: String) {
        lastCampaign = ContractId.NONE
        lastSurvival = ContractId.ENDURANCE
        records.clear()
        modules.clear()
        plays.fill(0)
        wins.fill(0)
        try {
            val parts = text.trim().split('|')
            if (parts.isEmpty() || !parts[0].startsWith("v")) return
            if (parts[0].drop(1).toIntOrNull() == null) return
            for (part in parts.drop(1)) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq)
                val value = part.substring(eq + 1)
                when (key) {
                    "lc" -> ContractId.fromCode(value.toIntOrNull() ?: -1)
                        ?.takeIf { ExpeditionKind.CAMPAIGN in Contracts.get(it).kinds }?.let { lastCampaign = it }
                    "ls" -> ContractId.fromCode(value.toIntOrNull() ?: -1)
                        ?.takeIf { ExpeditionKind.SURVIVAL in Contracts.get(it).kinds }?.let { lastSurvival = it }
                    "pl" -> readCounts(value, plays)
                    "wi" -> readCounts(value, wins)
                    "mo" -> for (item in value.split(',')) {
                        val kv = item.split(':')
                        if (kv.size != 2 || kv[0].isBlank()) continue
                        val n = kv[1].toIntOrNull() ?: continue
                        if (n > 0) modules[kv[0]] = n
                    }
                    "r" -> for (item in value.split(';')) {
                        if (records.size >= MAX_RECORDS) break
                        decodeRecord(item)?.let { records.addLast(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // Nunca deixa um campo ilegível derrubar o carregamento do save.
        }
    }

    private fun readCounts(value: String, into: IntArray) {
        val items = value.split(',')
        for (i in into.indices) into[i] = (items.getOrNull(i)?.toIntOrNull() ?: 0).coerceAtLeast(0)
    }

    private fun clean(s: String) = s.replace(Regex("[|;~,:=\\s]"), "-")

    private fun encodeRecord(r: ExpeditionRecord): String = listOf(
        clean(r.code), if (r.outcome == ExpeditionOutcome.VICTORY) "V" else "D", r.kind.ordinal, r.stage, r.contract.code,
        r.seconds, r.kills, r.alerts, r.contractDiamonds, r.totalDiamonds, clean(r.day),
    ).joinToString("~")

    private fun decodeRecord(text: String): ExpeditionRecord? {
        val f = text.split('~')
        if (f.size < 11) return null
        val kind = ExpeditionKind.entries.getOrNull(f[2].toIntOrNull() ?: return null) ?: return null
        val contract = ContractId.fromCode(f[4].toIntOrNull() ?: return null) ?: return null
        val outcome = when (f[1]) { "V" -> ExpeditionOutcome.VICTORY; "D" -> ExpeditionOutcome.DEFEAT; else -> return null }
        fun n(i: Int) = (f[i].toIntOrNull() ?: 0).coerceAtLeast(0)
        return ExpeditionRecord(f[0], kind, n(3), contract, outcome, n(5), n(6), n(7), n(8), n(9), f[10])
    }

    companion object {
        const val MAX_RECORDS = 40
        private const val VERSION = 1
        private const val SLOTS = 8     // ids 0..7 (reserva para contratos futuros)
    }
}
