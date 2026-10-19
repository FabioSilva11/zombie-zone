package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.Inventory
import com.fabiosilva.ecosdeeldra.data.Items
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.SurvivalResult
import com.fabiosilva.ecosdeeldra.engine.INV_INT_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class InventoryTest {
    @Test
    fun starterKitPutsWeaponsAndItemsOnTheHotbar() {
        val inv = Inventory.starter()
        assertEquals(Items.RIFLE, inv[0].id)
        assertEquals(Items.PISTOL, inv[1].id)
        assertEquals(3, inv.count(Items.GRENADE))
        assertEquals(0, inv.weaponSlot)
        assertTrue((0 until Inventory.HOTBAR).all { !inv[it].empty || it == 5 })
    }

    @Test
    fun itemsStackUpToTheLimitAndOverflowIsReturned() {
        val inv = Inventory()
        assertEquals(0, inv.add(Items.MEDKIT, 7))
        assertEquals(3, inv[0].count)
        assertEquals(3, inv[1].count)
        assertEquals(1, inv[2].count)
        // Granadas crescem com a melhoria de explosivos (+1 a cada 3 níveis).
        assertEquals(8, Items.maxStack(Items.GRENADE, 0))
        assertEquals(10, Items.maxStack(Items.GRENADE, 6))
        // Mochila cheia devolve o excesso.
        repeat(Inventory.SIZE) { inv.add(Items.BANDAGE, 10) }
        assertTrue(inv.add(Items.ANTIDOTE, 1) == 1)
        assertFalse(inv.canAdd(Items.ANTIDOTE))
    }

    @Test
    fun weaponsNeverDuplicateAndCannotBeDiscarded() {
        val inv = Inventory.starter()
        assertEquals(0, inv.add(Items.RIFLE, 1))
        assertEquals(1, inv.count(Items.RIFLE))
        assertFalse(inv.discard(0))
        assertTrue(inv.discard(3)) // bandagens
        assertTrue(inv[3].empty)
    }

    @Test
    fun moveSwapsSlotsAndKeepsTheWeaponInHand() {
        val inv = Inventory.starter()
        inv.weaponSlot = 1 // pistola
        inv.moveOrSwap(1, 10)
        assertEquals(Items.PISTOL, inv[10].id)
        assertEquals(10, inv.weaponSlot)
        // Juntar pilhas iguais.
        inv.add(Items.BANDAGE, 1)
        val first = inv.slots.indexOfFirst { it.id == Items.BANDAGE }
        inv.slots[15].id = Items.BANDAGE
        inv.slots[15].count = 2
        inv.moveOrSwap(15, first)
        assertEquals(5, inv[first].count)
        assertTrue(inv[15].empty)
    }

    @Test
    fun encodeDecodeAndEngineArrayRoundTrip() {
        val inv = Inventory.starter()
        inv.add(Items.SNIPER, 1)
        inv.throwSlot = 2
        val copy = Inventory.decode(inv.encode())
        assertEquals(inv.encode(), copy.encode())
        val arr = inv.toIntArray()
        assertEquals(INV_INT_COUNT, arr.size)
        assertEquals(Items.RIFLE, arr[0])
        assertEquals(2, arr[INV_INT_COUNT - 1])
        // Inventário sem armas recebe o fuzil.
        val empty = Inventory.decode("0:0|0|-1")
        assertEquals(1, empty.count(Items.RIFLE))
    }

    @Test
    fun weaponsUnlockByStageAndCostDiamonds() {
        val p = Progress()
        p.diamonds = 5000
        assertFalse(Rules.buyWeapon(p, Items.SNIPER)) // libera na fase 6
        winLeftmostPath(p, 5)
        val before = p.diamonds
        assertTrue(Rules.buyWeapon(p, Items.SNIPER))
        assertEquals(before - Items.get(Items.SNIPER)!!.price, p.diamonds)
        assertFalse(Rules.buyWeapon(p, Items.SNIPER))
        assertTrue(Rules.buyItem(p, Items.MEDKIT, 2))
        assertEquals(3, p.inventory.count(Items.MEDKIT))
    }

    @Test
    fun upgradeCapGrowsWithEachZone() {
        val p = Progress()
        p.diamonds = 1_000_000
        assertEquals(5, Rules.upgradeCap(p))
        repeat(5) { assertTrue(Rules.buyUpgrade(p, "armor")) }
        assertFalse(Rules.buyUpgrade(p, "armor"))
        winLeftmostPath(p, 5)
        assertEquals(8, Rules.upgradeCap(p))
        assertTrue(Rules.buyUpgrade(p, "armor"))
        assertEquals(6, p.upgrade("armor"))
        assertTrue(Rules.upgradeCost(6)!! > Rules.upgradeCost(5)!!)
        assertFalse(Rules.buyUpgrade(p, "nope"))
    }

    @Test
    fun survivalRankingKeepsTheTopTenByTime() {
        val p = Progress()
        for (i in 1..12) {
            Rules.applySurvivalResult(p, "J$i", "2026-10-02", SurvivalResult(i * 30f, i, i * 4, 0, i * 100, 0, 0f))
        }
        val ranking = Rules.survivalRanking(p)
        assertEquals(10, ranking.size)
        assertEquals("J12", ranking.first().name)
        assertEquals(360, p.stats.bestSurvivalSeconds)
        assertEquals(12, p.stats.bestWave)
        val summary = Rules.applySurvivalResult(p, "Novo", "2026-10-03", SurvivalResult(999f, 20, 80, 10, 5000, 6, 100f))
        assertEquals(1, summary.rank)
        assertTrue(summary.newRecord)
        assertTrue(summary.reward > 0)
    }

    @Test
    fun supplyCrateIsCappedPerDay() {
        val p = Progress()
        repeat(Rules.SUPPLY_AD_CAP) { assertTrue(Rules.claimSupplyCrate(p, "2026-10-02", Random(it)).isNotEmpty()) }
        assertTrue(Rules.claimSupplyCrate(p, "2026-10-02").isEmpty())
        assertTrue(Rules.claimSupplyCrate(p, "2026-10-03").isNotEmpty())
    }
}
