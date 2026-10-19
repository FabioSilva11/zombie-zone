package com.fabiosilva.ecosdeeldra.data.save

import com.fabiosilva.ecosdeeldra.data.Cosmetics
import com.fabiosilva.ecosdeeldra.data.DailyMission
import com.fabiosilva.ecosdeeldra.data.Hero
import com.fabiosilva.ecosdeeldra.data.Inventory
import com.fabiosilva.ecosdeeldra.data.Look
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.StageRecord
import com.fabiosilva.ecosdeeldra.data.SurvivalRecord
import com.fabiosilva.ecosdeeldra.data.Upgrades
import com.fabiosilva.ecosdeeldra.tutorial.TutorialPolicy
import org.json.JSONArray
import org.json.JSONObject

class SaveFormatException(message: String) : Exception(message)

sealed interface DecodeOutcome {
    data class Ok(val progress: Progress, val schemaFound: Int, val warnings: List<String>) : DecodeOutcome {
        val isNewerThanApp: Boolean get() = schemaFound > SaveCodec.SCHEMA_VERSION
    }
    data class Invalid(val reason: String) : DecodeOutcome
}

/** Conversão Progress <-> JSON. Lógica pura: não conhece arquivos nem Android. */
object SaveCodec {
    const val SCHEMA_VERSION = 5

    /** Chaves de topo que o app conhece. O resto é preservado em Progress.saveExtras. */
    internal val KNOWN_KEYS: Set<String> = setOf(
        "version", "writtenBy", "diamonds", "ownedSkins", "selectedHero", "heroes", "stages",
        "vitality", "power", "reload", "upgrades", "inventory", "survival", "supplyAdsDay", "supplyAdsToday",
        "stats", "lastLoginDay", "calendarClaims", "lastCalendarClaim", "dailyDay", "daily", "dailyBonusClaimed",
        "adsDay", "adsToday", "chestReadyAt", "tutorialSeen", "tutorial", "playTapsSinceAd", "missionsSinceInterstitial",
        "lastAdAt", "settings", "expeditions",
    )

    /** Aposentadas: ignoradas na leitura e nunca mais gravadas (nem como extras). */
    internal val REMOVED_KEYS: Set<String> = setOf("adRevenueMicros", "adRevenueCurrency")
    internal val REMOVED_SETTINGS_KEYS: Set<String> = setOf("adRevenueDifficulty")

    private fun Float.finiteOr(default: Float = 0f): Float = if (isFinite()) this else default

    fun encode(p: Progress, writtenBy: String = ""): String {
        val o = JSONObject()
        o.put("version", SCHEMA_VERSION)
        if (writtenBy.isNotEmpty()) o.put("writtenBy", writtenBy)
        o.put("diamonds", p.diamonds)
        o.put("ownedSkins", JSONArray(p.ownedCosmetics.toList()))           // nome antigo mantido
        o.put("selectedHero", p.selectedHero)
        o.put("heroes", JSONArray().apply { p.heroes.forEach { put(JSONObject().put("name", it.name).put("look", it.look)) } })
        o.put("stages", JSONObject().apply {
            for ((n, r) in p.stages.toSortedMap()) {
                put(n.toString(), JSONObject().apply {
                    put("completed", r.completed)
                    put("stars", r.stars)
                    put("bestTime", r.bestTime.finiteOr().toDouble())
                    put("crystalMask", r.crystalMask)
                    put("fails", r.fails)
                    put("skipped", r.skipped)
                })
            }
        })
        o.put("vitality", p.vitality)
        o.put("power", p.power)
        o.put("reload", p.reload)
        o.put("upgrades", JSONObject().apply { p.upgrades.toSortedMap().forEach { (k, v) -> put(k, v) } })
        o.put("inventory", p.inventory.encode())
        o.put("survival", JSONArray().apply {
            p.survivalRecords.forEach {
                put(JSONObject().put("name", it.name).put("seconds", it.seconds).put("wave", it.wave).put("kills", it.kills).put("score", it.score).put("date", it.date))
            }
        })
        o.put("supplyAdsDay", p.supplyAdsDay)
        o.put("supplyAdsToday", p.supplyAdsToday)
        o.put("stats", JSONObject().apply {
            put("kills", p.stats.kills); put("headshots", p.stats.headshots); put("wins", p.stats.wins)
            put("crystals", p.stats.crystals); put("playSeconds", p.stats.playSeconds); put("adsWatched", p.stats.adsWatched)
            put("loginDays", p.stats.loginDays); put("bestCombo", p.stats.bestCombo); put("pvpKills", p.stats.pvpKills)
            put("pvpWins", p.stats.pvpWins); put("pvpMatches", p.stats.pvpMatches); put("chests", p.stats.chests)
            put("nests", p.stats.nests); put("distance", p.stats.distance); put("losses", p.stats.losses)
            put("survivalRuns", p.stats.survivalRuns); put("bestWave", p.stats.bestWave)
            put("bestSurvivalSeconds", p.stats.bestSurvivalSeconds)
        })
        o.put("lastLoginDay", p.lastLoginDay)
        o.put("calendarClaims", p.calendarClaims)
        o.put("lastCalendarClaim", p.lastCalendarClaim)
        o.put("dailyDay", p.dailyDay)
        o.put("daily", JSONArray().apply {
            p.daily.forEach { put(JSONObject().put("id", it.id).put("progress", it.progress).put("claimed", it.claimed)) }
        })
        o.put("dailyBonusClaimed", p.dailyBonusClaimed)
        o.put("adsDay", p.adsDay)
        o.put("adsToday", p.adsToday)
        o.put("chestReadyAt", p.chestReadyAt)
        o.put("tutorialSeen", TutorialPolicy.isDone(p.tutorialVersion, p.tutorialMask))
        o.put("tutorial", JSONObject().put("version", p.tutorialVersion).put("mask", p.tutorialMask))
        o.put("playTapsSinceAd", p.playTapsSinceAd)
        o.put("missionsSinceInterstitial", p.missionsSinceInterstitial)
        o.put("lastAdAt", p.lastAdAt)
        o.put("expeditions", p.expeditions.encode())
        o.put("settings", JSONObject().apply {
            val s = p.settings
            put("sensitivity", s.sensitivity.finiteOr(1f).toDouble())
            put("musicVolume", s.musicVolume.finiteOr(0.6f).toDouble())
            put("sfxVolume", s.sfxVolume.finiteOr(1f).toDouble())
            put("aimAssist", s.aimAssist)
            put("autoFire", s.autoFire)
            put("invertY", s.invertY)
            put("vibration", s.vibration)
            put("quality", s.quality)
            put("reminders", s.reminders)
            put("showFps", s.showFps)
            put("leftHanded", s.leftHanded)
            put("playerName", s.playerName)
            put("cinematic", s.cinematic)
            put("hudLayout", s.hudLayout)
            put("hudOpacity", s.hudOpacity.finiteOr(0.85f).toDouble())
        })
        // Campos de versões futuras: devolvidos como vieram.
        p.saveExtras.forEach { (k, raw) ->
            if (k !in KNOWN_KEYS && k !in REMOVED_KEYS && !o.has(k)) {
                try { o.put(k, JSONArray(raw).get(0)) } catch (e: Exception) { /* extra ilegível: descarta */ }
            }
        }
        return o.toString()
    }

    fun decode(raw: String): DecodeOutcome {
        val o = try {
            JSONObject(raw.removePrefix("﻿").trim())
        } catch (e: Exception) {
            return DecodeOutcome.Invalid("JSON inválido: ${e.message.orEmpty().take(100)}")
        }
        val found = SaveMigrations.detectSchema(o)
        if (found < 0) return DecodeOutcome.Invalid("formato não reconhecido (sem 'version' nem campos do jogo)")
        if (found <= SCHEMA_VERSION) SaveMigrations.migrate(o, found)   // esquema futuro: leitura tolerante, sem migrar
        val warnings = mutableListOf<String>()
        return try {
            DecodeOutcome.Ok(decodeBody(o, warnings), found, warnings)
        } catch (e: SaveFormatException) {
            DecodeOutcome.Invalid(e.message ?: "seção crítica inválida")
        } catch (e: Exception) {
            DecodeOutcome.Invalid("erro inesperado: ${e.javaClass.simpleName}")
        }
    }

    fun summarize(p: Progress) = SaveSummary(p.diamonds, p.completedStages(), p.totalStars(), p.currentHero.name, p.stats.playSeconds / 60)

    // ------------------------------------------------------------------ leitura

    private fun JSONObject.strictInt(key: String, default: Int): Int {
        if (isNull(key)) return default
        val v = get(key)
        if (v !is Number) throw SaveFormatException("'$key' deveria ser número")
        return v.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun JSONObject.strictObject(key: String): JSONObject? {
        if (isNull(key)) return null
        return get(key) as? JSONObject ?: throw SaveFormatException("'$key' deveria ser objeto")
    }

    private fun JSONObject.strictArray(key: String): JSONArray? {
        if (isNull(key)) return null
        return get(key) as? JSONArray ?: throw SaveFormatException("'$key' deveria ser lista")
    }

    private fun JSONObject.strictString(key: String): String {
        if (isNull(key)) return ""
        return get(key) as? String ?: throw SaveFormatException("'$key' deveria ser texto")
    }

    private fun finiteFloat(d: Double): Float = if (d.isNaN() || d.isInfinite()) 0f else d.toFloat().coerceAtLeast(0f)

    private fun decodeBody(o: JSONObject, warnings: MutableList<String>): Progress {
        val p = Progress()

        // ---- críticas: formato errado => save inválido (cai para os backups; nunca zera em silêncio) ----
        p.diamonds = o.strictInt("diamonds", 0).coerceAtLeast(0)
        o.strictArray("ownedSkins")?.let { arr ->
            for (i in 0 until arr.length()) arr.optInt(i, -1).takeIf { it in Cosmetics.all.indices }?.let { p.ownedCosmetics.add(it) }
        }
        p.ownedCosmetics.add(0)
        o.strictArray("heroes")?.let { arr ->
            val list = (0 until arr.length()).mapNotNull { i ->
                val h = arr.optJSONObject(i) ?: return@mapNotNull null
                Hero(Rules.sanitizeName(h.optString("name", "Herói")), h.optInt("look", Look().encode()))
            }
            if (arr.length() > 0 && list.isEmpty()) throw SaveFormatException("lista de heróis ilegível")
            if (list.isNotEmpty()) {
                p.heroes.clear()
                p.heroes.addAll(list.take(Rules.MAX_HEROES))
            }
        }
        p.selectedHero = o.strictInt("selectedHero", 0).coerceIn(0, p.heroes.size - 1)
        o.strictObject("stages")?.let { stages ->
            for (key in stages.keys()) {
                val n = key.toIntOrNull() ?: continue
                if (n < 1) continue
                val r = stages.strictObject(key) ?: throw SaveFormatException("fase $n ilegível")
                p.stages[n] = StageRecord(
                    completed = r.optBoolean("completed"),
                    stars = r.strictInt("stars", 0).coerceIn(0, 3),
                    bestTime = finiteFloat(r.optDouble("bestTime", 0.0)),
                    crystalMask = r.strictInt("crystalMask", 0) and 0b111,
                    fails = r.strictInt("fails", 0).coerceAtLeast(0),
                    skipped = r.optBoolean("skipped"),
                )
            }
        }
        p.vitality = o.strictInt("vitality", 0).coerceIn(0, 60)
        p.power = o.strictInt("power", 0).coerceIn(0, 60)
        p.reload = o.strictInt("reload", 0).coerceIn(0, 60)
        o.strictObject("upgrades")?.let { u ->
            for (key in u.keys()) if (Upgrades.get(key) != null) p.upgrades[key] = u.strictInt(key, 0).coerceIn(0, 60)
        }
        if (o.has("inventory")) p.inventory.copyFrom(Inventory.decode(o.strictString("inventory")))

        // ---- leves: entradas ruins são descartadas e viram aviso; contêiner com tipo errado ainda é crítico ----
        o.strictArray("survival")?.let { arr ->
            var dropped = 0
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i)
                if (r == null) { dropped++; continue }
                p.survivalRecords.add(SurvivalRecord(r.optString("name", "Jogador"), r.optInt("seconds"), r.optInt("wave"), r.optInt("kills"), r.optInt("score"), r.optString("date", "")))
            }
            if (dropped > 0) warnings.add("ranking de sobrevivência")
        }
        p.supplyAdsDay = o.optString("supplyAdsDay", "")
        p.supplyAdsToday = o.optInt("supplyAdsToday").coerceAtLeast(0)
        o.strictObject("stats")?.let { s ->
            fun n(key: String) = s.optInt(key).coerceAtLeast(0)
            p.stats.kills = n("kills"); p.stats.headshots = n("headshots"); p.stats.wins = n("wins")
            p.stats.crystals = n("crystals"); p.stats.playSeconds = s.optLong("playSeconds").coerceAtLeast(0L)
            p.stats.adsWatched = n("adsWatched"); p.stats.loginDays = n("loginDays"); p.stats.bestCombo = n("bestCombo")
            p.stats.pvpKills = n("pvpKills"); p.stats.pvpWins = n("pvpWins"); p.stats.pvpMatches = n("pvpMatches")
            p.stats.chests = n("chests"); p.stats.nests = n("nests"); p.stats.distance = s.optLong("distance").coerceAtLeast(0L)
            p.stats.losses = n("losses"); p.stats.survivalRuns = n("survivalRuns"); p.stats.bestWave = n("bestWave")
            p.stats.bestSurvivalSeconds = n("bestSurvivalSeconds")
        }
        p.lastLoginDay = o.optString("lastLoginDay", "")
        p.calendarClaims = o.optInt("calendarClaims").coerceAtLeast(0)
        p.lastCalendarClaim = o.optString("lastCalendarClaim", "")
        p.dailyDay = o.optString("dailyDay", "")
        o.strictArray("daily")?.let { arr ->
            var dropped = 0
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i)
                if (d == null) { dropped++; continue }
                val id = d.optString("id")
                if (Rules.DAILY_POOL.any { it.id == id }) p.daily.add(DailyMission(id, d.optInt("progress").coerceAtLeast(0), d.optBoolean("claimed")))
            }
            if (dropped > 0) { p.daily.clear(); p.dailyDay = ""; warnings.add("missões diárias") }           // refreshDaily recria
        }
        p.dailyBonusClaimed = o.optBoolean("dailyBonusClaimed")
        p.adsDay = o.optString("adsDay", "")
        p.adsToday = o.optInt("adsToday").coerceAtLeast(0)
        // adRevenueMicros / adRevenueCurrency foram aposentados: qualquer valor presente é ignorado.
        p.chestReadyAt = o.optLong("chestReadyAt")
        // Tutorial: o legado `tutorialSeen` sozinho NÃO prova aprendizado; só o objeto "tutorial" ou uma fase vencida.
        val tut = o.optJSONObject("tutorial")
        val savedTutorial = TutorialPolicy.migrate(tut != null, tut?.optInt("version") ?: 0, tut?.optInt("mask") ?: 0, p.completedStages())
        p.tutorialVersion = savedTutorial.version.coerceAtLeast(0)
        p.tutorialMask = savedTutorial.mask.coerceAtLeast(0)
        p.tutorialSeen = TutorialPolicy.isDone(p.tutorialVersion, p.tutorialMask)
        p.playTapsSinceAd = o.optInt("playTapsSinceAd").coerceIn(0, Rules.CAMPAIGN_PLAY_AD_EVERY - 1)
        p.missionsSinceInterstitial = o.optInt("missionsSinceInterstitial").coerceAtLeast(0)
        p.lastAdAt = o.optLong("lastAdAt")
        // Aditivo e tolerante: campo ausente ou ilegível = histórico vazio (nunca invalida o save).
        p.expeditions.load(if (o.opt("expeditions") is String) o.optString("expeditions", "") else "")
        o.strictObject("settings")?.let { s ->
            val t = p.settings
            t.sensitivity = s.optDouble("sensitivity", 1.0).toFloat().coerceIn(0.3f, 2.5f)
            t.musicVolume = s.optDouble("musicVolume", 0.6).toFloat().coerceIn(0f, 1f)
            t.sfxVolume = s.optDouble("sfxVolume", 1.0).toFloat().coerceIn(0f, 1f)
            t.aimAssist = s.optBoolean("aimAssist", true)
            t.autoFire = s.optBoolean("autoFire", true)
            t.invertY = s.optBoolean("invertY", false)
            t.vibration = s.optBoolean("vibration", true)
            t.quality = s.optInt("quality", 1).coerceIn(0, 2)
            t.reminders = s.optBoolean("reminders", true)
            t.showFps = s.optBoolean("showFps", false)
            t.leftHanded = s.optBoolean("leftHanded", false)
            t.playerName = s.optString("playerName", "")
            t.cinematic = s.optBoolean("cinematic", false)
            t.hudLayout = s.optString("hudLayout", "")
            t.hudOpacity = s.optDouble("hudOpacity", 0.85).toFloat().coerceIn(0.3f, 1f)
        }

        // Campos desconhecidos (versões futuras): guardados verbatim, sem tipo (lista de 1 elemento preserva string x número).
        for (key in o.keys()) {
            if (key !in KNOWN_KEYS && key !in REMOVED_KEYS) p.saveExtras[key] = JSONArray().put(o.get(key)).toString()
        }
        return p
    }
}
