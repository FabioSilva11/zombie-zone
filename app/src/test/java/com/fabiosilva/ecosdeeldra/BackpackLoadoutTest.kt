package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Inventory
import com.fabiosilva.ecosdeeldra.data.Items
import com.fabiosilva.ecosdeeldra.data.WeaponSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Equipamento estilo Free Fire: 2 armas principais + pistola na barra rápida, o resto guardado na mochila. */
class BackpackLoadoutTest {
    @Test
    fun starterLoadoutHasRiflePrimaryAndPistol() {
        val inv = Inventory.starter()
        assertEquals(listOf(0), inv.primarySlots())
        assertEquals(1, inv.pistolSlot())
        assertTrue(inv.spareWeaponSlots().isEmpty())
        assertFalse(inv.fitLoadout())
    }

    @Test
    fun fitLoadoutKeepsTwoPrimariesAndPrefersTheOneInHand() {
        val inv = Inventory()
        inv.add(Items.RIFLE, 1)    // 0
        inv.add(Items.SHOTGUN, 1)  // 1
        inv.add(Items.SMG, 1)      // 2
        inv.add(Items.PISTOL, 1)   // 3
        inv.weaponSlot = 2
        assertTrue(inv.fitLoadout())
        val primaries = inv.primarySlots().map { inv[it].id }
        assertEquals(2, primaries.size)
        assertTrue(Items.SMG in primaries)
        assertEquals(Items.SMG, inv[inv.weaponSlot].id)
        assertEquals(listOf(Items.SHOTGUN), inv.spareWeaponSlots().map { inv[it].id })
        assertTrue(inv.spareWeaponSlots().all { it >= Inventory.HOTBAR })
        assertTrue(inv.pistolSlot() in 0 until Inventory.HOTBAR)
    }

    @Test
    fun fitLoadoutBringsTheHandWeaponToTheHotbar() {
        val inv = Inventory.starter()
        inv.moveOrSwap(0, 10)  // fuzil vai para a mochila (a mão acompanha)
        assertEquals(10, inv.weaponSlot)
        assertTrue(inv.fitLoadout())
        assertTrue(inv.weaponSlot < Inventory.HOTBAR)
        assertEquals(Items.RIFLE, inv[inv.weaponSlot].id)
    }

    @Test
    fun equipSpareIntoAnEquippedSlotSwapsAndKeepsTheHand() {
        val inv = Inventory.starter()
        inv.add(Items.SNIPER, 1)  // a barra do kit inicial está cheia: vai direto para a mochila
        val added = inv.firstSlotOf(Items.SNIPER)
        if (added < Inventory.HOTBAR) assertTrue(inv.stowWeapon(added))
        val from = inv.firstSlotOf(Items.SNIPER)
        assertTrue(from >= Inventory.HOTBAR)
        val rifle = inv.firstSlotOf(Items.RIFLE)
        assertEquals(rifle, inv.weaponSlot)
        assertTrue(inv.equipWeapon(from, rifle))
        assertEquals(Items.SNIPER, inv[rifle].id)
        assertEquals(rifle, inv.weaponSlot)  // a arma nova fica na mão
        assertEquals(Items.RIFLE, inv[from].id)
    }

    @Test
    fun equipWithoutTargetUsesAFreeOrConsumableHotbarSlot() {
        val inv = Inventory.starter()
        inv.add(Items.SMG, 1)
        val smg = inv.firstSlotOf(Items.SMG)
        if (smg < Inventory.HOTBAR) assertTrue(inv.stowWeapon(smg))
        // Lota a barra com consumíveis: a arma troca de lugar com um deles.
        for (i in 0 until Inventory.HOTBAR) if (inv[i].empty) { inv[i].id = Items.ANTIDOTE; inv[i].count = 1 }
        val from = inv.firstSlotOf(Items.SMG)
        val bandages = inv.count(Items.BANDAGE) + inv.count(Items.GRENADE) + inv.count(Items.SMOKE) + inv.count(Items.ANTIDOTE) + inv.count(Items.MEDKIT)
        assertTrue(inv.equipWeapon(from))
        assertTrue(inv.firstSlotOf(Items.SMG) < Inventory.HOTBAR)
        val after = inv.count(Items.BANDAGE) + inv.count(Items.GRENADE) + inv.count(Items.SMOKE) + inv.count(Items.ANTIDOTE) + inv.count(Items.MEDKIT)
        assertEquals(bandages, after)  // nenhum consumível se perdeu
    }

    @Test
    fun handWeaponCannotBeStowed() {
        val inv = Inventory.starter()
        assertFalse(inv.stowWeapon(inv.weaponSlot))
        assertTrue(inv.stowWeapon(inv.pistolSlot()))
        assertEquals(-1, inv.pistolSlot())
        assertTrue(inv.holdWeapon(inv.primarySlots().first()))
        assertFalse(inv.holdWeapon(20))
    }

    @Test
    fun removeAmountTakesFromTheLastStacksAndKeepsIndicesValid() {
        val inv = Inventory()
        inv.add(Items.RIFLE, 1)
        inv.add(Items.GRENADE, 12)  // 8 + 4
        inv.throwSlot = inv.firstSlotOf(Items.GRENADE)
        assertEquals(5, inv.removeAmount(Items.GRENADE, 5))
        assertEquals(7, inv.count(Items.GRENADE))
        assertTrue(Items.isThrowable(inv[inv.throwSlot].id))
        assertEquals(7, inv.removeAmount(Items.GRENADE, 99))
        assertEquals(0, inv.count(Items.GRENADE))
        assertEquals(-1, inv.throwSlot)
        assertEquals(0, inv.removeAmount(Items.RIFLE, 1))
        assertEquals(1, inv.count(Items.RIFLE))
    }

    @Test
    fun everyWeaponAndTheKnifeHaveSpecs() {
        for (w in Items.weapons) assertNotNull(w.name, WeaponSpecs.get(w.id))
        assertNotNull(WeaponSpecs.get(WeaponSpecs.KNIFE))
    }
}
