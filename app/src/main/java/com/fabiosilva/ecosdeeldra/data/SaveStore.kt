package com.fabiosilva.ecosdeeldra.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Salva o [Progress] em SharedPreferences como JSON (versão 3 = jogo nativo). */
class SaveStore(context: Context) {
    private val prefs = context.getSharedPreferences("zombie_zone_native", Context.MODE_PRIVATE)

    fun load(): Progress = runCatching {
        val raw = prefs.getString(KEY, null) ?: return Progress()
        decode(JSONObject(raw))
    }.getOrElse { Progress() }

    fun save(p: Progress) {
        prefs.edit().putString(KEY, encode(p).toString()).apply()
    }

    private fun encode(p: Progress): JSONObject = JSONObject().apply {
        put("version", 4)
        put("diamonds", p.diamonds)
        put("ownedSkins", JSONArray(p.ownedCosmetics.toList()))  // nome antigo mantido: visuais liberados
        put("selectedHero", p.selectedHero)
        put("heroes", JSONArray().apply { p.heroes.forEach { put(JSONObject().put("name", it.name).put("look", it.look)) } })
        put("stages", JSONObject().apply {
            p.stages.forEach { (n, r) ->
                put(n.toString(), JSONObject().apply {
                    put("completed", r.completed)
                    put("stars", r.stars)
                    put("bestTime", r.bestTime.toDouble())
                    put("crystalMask", r.crystalMask)
                    put("fails", r.fails)
                    put("skipped", r.skipped)
                })
            }
        })
        put("vitality", p.vitality)
        put("power", p.power)
        put("reload", p.reload)
        put("upgrades", JSONObject().apply { p.upgrades.forEach { (k, v) -> put(k, v) } })
        put("inventory", p.inventory.encode())
        put("survival", JSONArray().apply {
            p.survivalRecords.forEach {
                put(JSONObject().put("name", it.name).put("seconds", it.seconds).put("wave", it.wave).put("kills", it.kills).put("score", it.score).put("date", it.date))
            }
        })
        put("supplyAdsDay", p.supplyAdsDay)
        put("supplyAdsToday", p.supplyAdsToday)
        put("stats", JSONObject().apply {
            put("kills", p.stats.kills)
            put("headshots", p.stats.headshots)
            put("wins", p.stats.wins)
            put("crystals", p.stats.crystals)
            put("playSeconds", p.stats.playSeconds)
            put("adsWatched", p.stats.adsWatched)
            put("loginDays", p.stats.loginDays)
            put("bestCombo", p.stats.bestCombo)
            put("pvpKills", p.stats.pvpKills)
            put("pvpWins", p.stats.pvpWins)
            put("pvpMatches", p.stats.pvpMatches)
            put("chests", p.stats.chests)
            put("nests", p.stats.nests)
            put("distance", p.stats.distance)
            put("losses", p.stats.losses)
            put("survivalRuns", p.stats.survivalRuns)
            put("bestWave", p.stats.bestWave)
            put("bestSurvivalSeconds", p.stats.bestSurvivalSeconds)
        })
        put("lastLoginDay", p.lastLoginDay)
        put("calendarClaims", p.calendarClaims)
        put("lastCalendarClaim", p.lastCalendarClaim)
        put("dailyDay", p.dailyDay)
        put("daily", JSONArray().apply {
            p.daily.forEach { put(JSONObject().put("id", it.id).put("progress", it.progress).put("claimed", it.claimed)) }
        })
        put("dailyBonusClaimed", p.dailyBonusClaimed)
        put("adsDay", p.adsDay)
        put("adsToday", p.adsToday)
        put("adRevenueMicros", p.adRevenueMicros)
        put("adRevenueCurrency", p.adRevenueCurrency)
        put("chestReadyAt", p.chestReadyAt)
        put("tutorialSeen", p.tutorialSeen)
        put("missionsSinceInterstitial", p.missionsSinceInterstitial)
        put("lastAdAt", p.lastAdAt)
        put("settings", JSONObject().apply {
            val s = p.settings
            put("sensitivity", s.sensitivity.toDouble())
            put("musicVolume", s.musicVolume.toDouble())
            put("sfxVolume", s.sfxVolume.toDouble())
            put("aimAssist", s.aimAssist)
            put("autoFire", s.autoFire)
            put("invertY", s.invertY)
            put("vibration", s.vibration)
            put("quality", s.quality)
            put("reminders", s.reminders)
            put("adRevenueDifficulty", s.adRevenueDifficulty)
            put("showFps", s.showFps)
            put("leftHanded", s.leftHanded)
            put("playerName", s.playerName)
            put("cinematic", s.cinematic)
            put("hudLayout", s.hudLayout)
            put("hudOpacity", s.hudOpacity.toDouble())
        })
    }

    private fun decode(o: JSONObject): Progress {
        val p = Progress()
        p.diamonds = o.optInt("diamonds", 0).coerceAtLeast(0)
        o.optJSONArray("ownedSkins")?.let { arr ->
            for (i in 0 until arr.length()) arr.optInt(i, -1).takeIf { it in Cosmetics.all.indices }?.let { p.ownedCosmetics.add(it) }
        }
        p.ownedCosmetics.add(0)
        o.optJSONArray("heroes")?.let { arr ->
            val list = (0 until arr.length()).mapNotNull { i ->
                val h = arr.optJSONObject(i) ?: return@mapNotNull null
                Hero(Rules.sanitizeName(h.optString("name", "Herói")), h.optInt("look", Look().encode()))
            }
            if (list.isNotEmpty()) {
                p.heroes.clear()
                p.heroes.addAll(list.take(Rules.MAX_HEROES))
            }
        }
        p.selectedHero = o.optInt("selectedHero", 0).coerceIn(0, p.heroes.size - 1)
        o.optJSONObject("stages")?.let { stages ->
            stages.keys().forEach { key ->
                val n = key.toIntOrNull() ?: return@forEach
                if (n < 1) return@forEach
                val r = stages.getJSONObject(key)
                p.stages[n] = StageRecord(
                    completed = r.optBoolean("completed"),
                    stars = r.optInt("stars").coerceIn(0, 3),
                    bestTime = r.optDouble("bestTime", 0.0).toFloat(),
                    crystalMask = r.optInt("crystalMask") and 0b111,
                    fails = r.optInt("fails").coerceAtLeast(0),
                    skipped = r.optBoolean("skipped"),
                )
            }
        }
        p.vitality = o.optInt("vitality").coerceIn(0, 60)
        p.power = o.optInt("power").coerceIn(0, 60)
        p.reload = o.optInt("reload").coerceIn(0, 60)
        o.optJSONObject("upgrades")?.let { u ->
            u.keys().forEach { key -> if (Upgrades.get(key) != null) p.upgrades[key] = u.optInt(key).coerceIn(0, 60) }
        }
        if (o.has("inventory")) p.inventory.copyFrom(Inventory.decode(o.optString("inventory", "")))
        o.optJSONArray("survival")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                p.survivalRecords.add(SurvivalRecord(r.optString("name", "Jogador"), r.optInt("seconds"), r.optInt("wave"), r.optInt("kills"), r.optInt("score"), r.optString("date", "")))
            }
        }
        p.supplyAdsDay = o.optString("supplyAdsDay", "")
        p.supplyAdsToday = o.optInt("supplyAdsToday")
        o.optJSONObject("stats")?.let { s ->
            p.stats.kills = s.optInt("kills")
            p.stats.headshots = s.optInt("headshots")
            p.stats.wins = s.optInt("wins")
            p.stats.crystals = s.optInt("crystals")
            p.stats.playSeconds = s.optLong("playSeconds")
            p.stats.adsWatched = s.optInt("adsWatched")
            p.stats.loginDays = s.optInt("loginDays")
            p.stats.bestCombo = s.optInt("bestCombo")
            p.stats.pvpKills = s.optInt("pvpKills")
            p.stats.pvpWins = s.optInt("pvpWins")
            p.stats.pvpMatches = s.optInt("pvpMatches")
            p.stats.chests = s.optInt("chests")
            p.stats.nests = s.optInt("nests")
            p.stats.distance = s.optLong("distance")
            p.stats.losses = s.optInt("losses")
            p.stats.survivalRuns = s.optInt("survivalRuns")
            p.stats.bestWave = s.optInt("bestWave")
            p.stats.bestSurvivalSeconds = s.optInt("bestSurvivalSeconds")
        }
        p.lastLoginDay = o.optString("lastLoginDay", "")
        p.calendarClaims = o.optInt("calendarClaims")
        p.lastCalendarClaim = o.optString("lastCalendarClaim", "")
        p.dailyDay = o.optString("dailyDay", "")
        o.optJSONArray("daily")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val id = d.optString("id")
                if (Rules.DAILY_POOL.any { it.id == id }) p.daily.add(DailyMission(id, d.optInt("progress"), d.optBoolean("claimed")))
            }
        }
        p.dailyBonusClaimed = o.optBoolean("dailyBonusClaimed")
        p.adsDay = o.optString("adsDay", "")
        p.adsToday = o.optInt("adsToday")
        p.adRevenueMicros = o.optLong("adRevenueMicros", 0L).coerceAtLeast(0L)
        p.adRevenueCurrency = o.optString("adRevenueCurrency", "")
        p.chestReadyAt = o.optLong("chestReadyAt")
        p.tutorialSeen = o.optBoolean("tutorialSeen")
        p.missionsSinceInterstitial = o.optInt("missionsSinceInterstitial")
        p.lastAdAt = o.optLong("lastAdAt")
        o.optJSONObject("settings")?.let { s ->
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
            t.adRevenueDifficulty = s.optBoolean("adRevenueDifficulty", false)
            t.showFps = s.optBoolean("showFps", false)
            t.leftHanded = s.optBoolean("leftHanded", false)
            t.playerName = s.optString("playerName", "")
            t.cinematic = s.optBoolean("cinematic", false)
            t.hudLayout = s.optString("hudLayout", "")
            t.hudOpacity = s.optDouble("hudOpacity", 0.85).toFloat().coerceIn(0.3f, 1f)
        }
        return p
    }

    private companion object {
        const val KEY = "progress"
    }
}
