package com.fabiosilva.ecosdeeldra.data.save

import org.json.JSONObject

/** Passos de migração do JSON bruto entre versões de esquema. Cada passo é puro e idempotente. */
internal object SaveMigrations {
    /** Campos que existiam antes do campo "version" ser lido (versões <= 3 não tinham marcador confiável). */
    private val LEGACY_MARKERS = listOf("diamonds", "stages", "heroes", "stats", "ownedSkins", "inventory")

    /** @return versão do esquema, 0 para legado sem "version", -1 se o JSON não parece um save do jogo. */
    fun detectSchema(o: JSONObject): Int {
        if (o.has("version")) {
            val v = o.opt("version")
            return if (v is Number && v.toInt() >= 0) v.toInt() else -1
        }
        return if (LEGACY_MARKERS.any { o.has(it) }) 0 else -1
    }

    /** Chave = versão de origem; o passo leva o JSON de `from` para `from + 1`. Ausente = identidade. */
    private val steps: Map<Int, (JSONObject) -> Unit> = mapOf(
        4 to { o: JSONObject -> v4ToV5(o) },
    )

    fun migrate(o: JSONObject, from: Int): Int {
        var v = from
        while (v < SaveCodec.SCHEMA_VERSION) {
            steps[v]?.invoke(o)
            v++
        }
        o.put("version", v)
        return v
    }

    /** v4 -> v5: aposenta o estado de receita de anúncios (a dificuldade nunca depende de anúncios). */
    private fun v4ToV5(o: JSONObject) {
        SaveCodec.REMOVED_KEYS.forEach { o.remove(it) }
        o.optJSONObject("settings")?.let { s -> SaveCodec.REMOVED_SETTINGS_KEYS.forEach { s.remove(it) } }
    }
}
