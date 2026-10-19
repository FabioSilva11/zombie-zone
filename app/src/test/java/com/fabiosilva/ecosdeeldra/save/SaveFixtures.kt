package com.fabiosilva.ecosdeeldra.save

import com.fabiosilva.ecosdeeldra.data.Inventory
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.save.SaveCodec
import org.json.JSONArray
import org.json.JSONObject

object SaveFixtures {
    val STARTER_INVENTORY: String = Inventory.starter().encode()

    /** Save real no formato v4 (como o app antigo gravava), incluindo os campos de receita que serão ignorados. */
    val V4_JSON: String = """
    {"version":4,"diamonds":1234,"ownedSkins":[0,2,3],"selectedHero":1,
     "heroes":[{"name":"Ana","look":0},{"name":"Bia","look":12345}],
     "stages":{"1":{"completed":true,"stars":3,"bestTime":120.5,"crystalMask":7,"fails":1,"skipped":false},
               "2":{"completed":true,"stars":1,"bestTime":300.0,"crystalMask":3,"fails":0,"skipped":true}},
     "vitality":3,"power":2,"reload":1,"upgrades":{"vitality":3,"power":2,"reload":1,"magazine":4,"inexistente":9},
     "inventory":"$STARTER_INVENTORY",
     "survival":[{"name":"Ana","seconds":300,"wave":5,"kills":80,"score":900,"date":"2026-10-01"}],
     "supplyAdsDay":"2026-10-17","supplyAdsToday":2,
     "stats":{"kills":500,"headshots":40,"wins":9,"crystals":20,"playSeconds":7200,"adsWatched":6,"loginDays":4,"bestCombo":7,
              "pvpKills":1,"pvpWins":0,"pvpMatches":1,"chests":3,"nests":9,"distance":15000,"losses":4,"survivalRuns":2,
              "bestWave":5,"bestSurvivalSeconds":300},
     "lastLoginDay":"2026-10-17","calendarClaims":3,"lastCalendarClaim":"2026-10-17","dailyDay":"2026-10-17",
     "daily":[{"id":"kill","progress":10,"claimed":false},{"id":"win","progress":2,"claimed":true},{"id":"nest","progress":0,"claimed":false}],
     "dailyBonusClaimed":false,"adsDay":"2026-10-17","adsToday":1,"adRevenueMicros":9000000,"adRevenueCurrency":"USD",
     "chestReadyAt":1760000000000,"tutorialSeen":true,"playTapsSinceAd":3,"missionsSinceInterstitial":1,"lastAdAt":1760000000000,
     "settings":{"sensitivity":1.4,"musicVolume":0.5,"sfxVolume":1.0,"aimAssist":true,"autoFire":false,"invertY":false,
                 "vibration":true,"quality":2,"reminders":true,"adRevenueDifficulty":true,"showFps":true,"leftHanded":true,
                 "playerName":"Ana","cinematic":false,"hudLayout":"","hudOpacity":0.7}}
    """.trimIndent()

    /** Ordena chaves recursivamente: compara JSON sem depender da ordem do HashMap do org.json real. */
    fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { k -> "\"$k\":${canon(v.get(k))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        else -> v.toString()
    }

    fun json(diamonds: Int): String = SaveCodec.encode(Progress().apply { this.diamonds = diamonds }, "teste")

    /** Variante do V4 com uma chave de topo trocada/acrescentada. */
    fun v4With(edit: (JSONObject) -> Unit): String = JSONObject(V4_JSON).also(edit).toString()
}
