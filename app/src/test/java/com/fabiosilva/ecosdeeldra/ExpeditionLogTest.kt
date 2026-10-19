package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.ExpeditionLog
import com.fabiosilva.ecosdeeldra.data.ExpeditionOutcome
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import com.fabiosilva.ecosdeeldra.data.ExpeditionRecord
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.save.DecodeOutcome
import com.fabiosilva.ecosdeeldra.data.save.SaveCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpeditionLogTest {
    private fun rec(
        id: ContractId = ContractId.RED_NIGHT, win: Boolean = true, kind: ExpeditionKind = ExpeditionKind.CAMPAIGN,
        stage: Int = 7, code: String = "ZZ-80W0-Y4G0-100RS", day: String = "2026-10-18",
    ) = ExpeditionRecord(code, kind, stage, id, if (win) ExpeditionOutcome.VICTORY else ExpeditionOutcome.DEFEAT, 212, 19, 6, 38, 118, day)

    @Test
    fun encodeDecodeRoundTrips() {
        val a = ExpeditionLog()
        a.add(rec()); a.add(rec(ContractId.SILENT_RECOVERY, win = false, stage = 2)); a.add(rec(ContractId.ENDURANCE, kind = ExpeditionKind.SURVIVAL, stage = 6))
        a.remember(ExpeditionPlan(ExpeditionKind.CAMPAIGN, 3, 5, ContractId.RED_NIGHT))
        a.remember(ExpeditionPlan(ExpeditionKind.SURVIVAL, 1, 5, ContractId.RED_NIGHT))
        a.grantModule("nucleo_instavel"); a.grantModule("nucleo_instavel")
        val text = a.encode()
        assertTrue(text, Regex("[A-Za-z0-9 ~,;:|=._-]*").matches(text))
        val b = ExpeditionLog().also { it.load(text) }
        assertEquals(text, b.encode())
        assertEquals(a.records.toList(), b.records.toList())
        assertEquals(ContractId.RED_NIGHT, b.lastCampaign)
        assertEquals(ContractId.RED_NIGHT, b.lastSurvival)
        assertEquals(2, b.modules["nucleo_instavel"])
        assertEquals(1, b.winsOf(ContractId.RED_NIGHT)); assertEquals(1, b.playsOf(ContractId.SILENT_RECOVERY))
        assertEquals(0, b.winsOf(ContractId.SILENT_RECOVERY))
    }

    @Test
    fun garbageAndUnknownFieldsAreIgnored() {
        val log = ExpeditionLog()
        for (junk in listOf(
            "", "   ", "lixo", "v9|", "v9|lc=99|ls=x|pl=a,b|wi=|mo=:::|r=;;;~~~", "v1|r=ZZ~V~0~7~99~1~1~1~1~1~dia",
            "v1|r=ZZ~V~0~7~3~1~1~1~1", "|||", "v1|=", "v1|lc=", "v1|pl=1,2,3,x,5", "\u0000￿",
        )) {
            log.load(junk)
            assertTrue(log.records.size <= ExpeditionLog.MAX_RECORDS)
        }
        log.load("v1|lc=1|ls=5|pl=0,3,0,1|wi=0,1|mo=a:2,b:x,:3|r=ZZ~V~0~2~1~10~5~1~3~9~2026-10-18;ZZ~V~0~7~99~1~1~1~1~1~dia;ZZ~V~0~7~3~1~1~1")
        assertEquals(1, log.records.size)
        assertEquals(ContractId.SILENT_RECOVERY, log.lastCampaign)
        assertEquals(3, log.playsOf(ContractId.SILENT_RECOVERY))
        assertEquals(1, log.winsOf(ContractId.SILENT_RECOVERY))
        assertEquals(mapOf("a" to 2), log.modules.toMap())
        assertEquals("future 'ls=5' valid", ContractId.ENDURANCE, log.lastSurvival)
    }

    @Test
    fun loadResetsPreviousState() {
        val log = ExpeditionLog()
        log.add(rec()); log.grantModule("x")
        log.load("")
        assertTrue(log.records.isEmpty()); assertTrue(log.modules.isEmpty())
        assertEquals(0, log.playsOf(ContractId.RED_NIGHT))
    }

    @Test
    fun keepsOnlyTheLatestFortyRecords() {
        val log = ExpeditionLog()
        for (i in 1..55) log.add(rec(stage = i))
        assertEquals(40, log.records.size)
        assertEquals(55, log.records.first().stage)
        assertEquals(16, log.records.last().stage)
        assertEquals(55, log.playsOf(ContractId.RED_NIGHT))     // contadores não dependem do corte
        val back = ExpeditionLog().also { it.load(log.encode()) }
        assertEquals(40, back.records.size)
    }

    @Test
    fun countsPlaysAndWinsPerContract() {
        val log = ExpeditionLog()
        log.add(rec(ContractId.NONE)); log.add(rec(ContractId.NONE, win = false)); log.add(rec(ContractId.RED_NIGHT))
        assertEquals(2, log.playsOf(ContractId.NONE)); assertEquals(1, log.winsOf(ContractId.NONE))
        assertEquals(1, log.playsOf(ContractId.RED_NIGHT))
        assertEquals(ContractId.RED_NIGHT, log.lastOf(ContractId.RED_NIGHT)!!.contract)
        assertNull(log.lastOf(ContractId.LOST_CRATE))
    }

    @Test
    fun rememberStoresLastContractPerKind() {
        val log = ExpeditionLog()
        log.remember(ExpeditionPlan(ExpeditionKind.CAMPAIGN, 3, 1, ContractId.SILENT_RECOVERY))
        assertEquals(ContractId.SILENT_RECOVERY, log.lastCampaign)
        assertEquals(ContractId.ENDURANCE, log.lastSurvival)
        log.remember(ExpeditionPlan(ExpeditionKind.SURVIVAL, 1, 1, ContractId.RED_NIGHT))
        assertEquals(ContractId.SILENT_RECOVERY, log.lastCampaign)
        assertEquals(ContractId.RED_NIGHT, log.lastSurvival)
    }

    @Test
    fun delimitersInsideFieldsCannotBreakTheEncoding() {
        val log = ExpeditionLog()
        log.add(rec(code = "A|B;C~D", day = "x y,z"))
        val back = ExpeditionLog().also { it.load(log.encode()) }
        assertEquals(1, back.records.size)
        assertFalse(back.records.first().day.contains(' '))
    }

    // ---- persistência pelo SaveCodec ----

    @Test
    fun savedProgressKeepsTheExpeditionLog() {
        val p = Progress()
        p.expeditions.add(rec()); p.expeditions.remember(ExpeditionPlan(ExpeditionKind.CAMPAIGN, 3, 9, ContractId.RED_NIGHT))
        val json = SaveCodec.encode(p, "t")
        assertTrue(JSONObject(json).has("expeditions"))
        val back = (SaveCodec.decode(json) as DecodeOutcome.Ok).progress
        assertEquals(1, back.expeditions.records.size)
        assertEquals(ContractId.RED_NIGHT, back.expeditions.lastCampaign)
        assertEquals(p.expeditions.encode(), back.expeditions.encode())
        assertTrue(back.saveExtras.isEmpty())      // é campo conhecido, não "extra de versão futura"
    }

    @Test
    fun oldSaveWithoutTheFieldLoadsWithAnEmptyLog() {
        val o = JSONObject(SaveCodec.encode(Progress(), "t")).also { it.remove("expeditions") }
        val out = SaveCodec.decode(o.toString())
        assertTrue(out is DecodeOutcome.Ok)
        val p = (out as DecodeOutcome.Ok).progress
        assertTrue(p.expeditions.records.isEmpty())
        assertEquals(ContractId.NONE, p.expeditions.lastCampaign)
        assertEquals(ContractId.ENDURANCE, p.expeditions.lastSurvival)
    }

    @Test
    fun garbageInTheFieldNeverInvalidatesTheSave() {
        for (bad in listOf<Any>("v1|r=lixo~~~", 12, "\u0000", JSONObject("{\"a\":1}"))) {
            val o = JSONObject(SaveCodec.encode(Progress().apply { diamonds = 55 }, "t")).also { it.put("expeditions", bad) }
            val out = SaveCodec.decode(o.toString())
            assertTrue("$bad", out is DecodeOutcome.Ok)
            assertEquals(55, (out as DecodeOutcome.Ok).progress.diamonds)
            assertNotNull(out.progress.expeditions)
        }
    }
}
