package com.fabiosilva.ecosdeeldra.data

import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.INV_INT_COUNT

enum class ItemKind { WEAPON, HEAL, BUFF, THROWABLE }

/** Item do inventário (ids iguais a ItemId em app/src/main/cpp/game/items.h). */
data class ItemDef(
    val id: Int,
    val name: String,
    val desc: String,
    val kind: ItemKind,
    val color: Int,
    val price: Int,
    val unlockStage: Int = 1,
    val damage: String = "",
    val rate: String = "",
    val magazine: Int = 0,
)

object Items {
    const val RIFLE = 1
    const val PISTOL = 2
    const val SHOTGUN = 3
    const val SMG = 4
    const val SNIPER = 5
    const val LMG = 6
    const val FLAMER = 7
    const val LAUNCHER = 8
    const val MEDKIT = 20
    const val BANDAGE = 21
    const val ANTIDOTE = 22
    const val ADRENALINE = 23
    const val FIRE_AMMO = 24
    const val ARMOR = 25
    const val GRENADE = 26
    const val MOLOTOV = 27
    const val SMOKE = 28

    val all = listOf(
        ItemDef(RIFLE, "Fuzil de assalto", "Equilibrado: rajada rápida e boa precisão.", ItemKind.WEAPON, 0xFF8FD46B.toInt(), 0, 1, "18", "10/s", 30),
        ItemDef(PISTOL, "Pistola", "Leve e precisa; você anda mais rápido com ela.", ItemKind.WEAPON, 0xFFB8C4D8.toInt(), 120, 1, "30", "5/s", 12),
        ItemDef(SHOTGUN, "Escopeta", "9 chumbos de perto: arrasa hordas, fraca de longe.", ItemKind.WEAPON, 0xFFE0A35E.toInt(), 320, 2, "9×13", "1,2/s", 6),
        ItemDef(SMG, "Submetralhadora", "Cadência altíssima para limpar corredores.", ItemKind.WEAPON, 0xFF7FD8FF.toInt(), 450, 4, "12", "15/s", 36),
        ItemDef(SNIPER, "Rifle de precisão", "Luneta com zoom; atravessa até 4 zumbis.", ItemKind.WEAPON, 0xFF9DB4FF.toInt(), 700, 6, "120", "0,9/s", 5),
        ItemDef(LMG, "Metralhadora", "Pente de 90 balas que atravessam; pesada.", ItemKind.WEAPON, 0xFFC9D16A.toInt(), 900, 9, "17", "12/s", 90),
        ItemDef(FLAMER, "Lança-chamas", "Jato de fogo de curto alcance que incendeia tudo.", ItemKind.WEAPON, 0xFFFF7A3D.toInt(), 1100, 12, "9+fogo", "14/s", 80),
        ItemDef(LAUNCHER, "Lança-granadas", "Disparos explosivos em linha reta.", ItemKind.WEAPON, 0xFFB6FF5C.toInt(), 1500, 15, "160", "1/s", 3),
        ItemDef(MEDKIT, "Kit médico", "Recupera 40% da vida e estanca sangramento. Espera de 8 s entre curas.", ItemKind.HEAL, 0xFFFF6B6B.toInt(), 90),
        ItemDef(BANDAGE, "Bandagem", "Recupera 12% da vida e estanca sangramento. Espera de 8 s entre curas.", ItemKind.HEAL, 0xFFF2DDB8.toInt(), 30),
        ItemDef(ANTIDOTE, "Antídoto", "Cura veneno, lentidão e fogo (não recupera vida).", ItemKind.HEAL, 0xFF8CFF6B.toInt(), 30),
        ItemDef(ADRENALINE, "Adrenalina", "20 s: +30% de velocidade, recarga e tiro mais rápidos.", ItemKind.BUFF, 0xFF4CE8FF.toInt(), 35),
        ItemDef(FIRE_AMMO, "Munição incendiária", "45 s de tiros com +50% de dano.", ItemKind.BUFF, 0xFFFFA53D.toInt(), 30),
        ItemDef(ARMOR, "Colete", "Absorve 70% do dano até se desgastar.", ItemKind.BUFF, 0xFF9DB0D8.toInt(), 60),
        ItemDef(GRENADE, "Granada", "Segure o botão para ver onde cai; solte para lançar.", ItemKind.THROWABLE, 0xFFA8C46A.toInt(), 25),
        ItemDef(MOLOTOV, "Molotov", "Fogo no chão que queima os zumbis (não você).", ItemKind.THROWABLE, 0xFFFF8A3D.toInt(), 25),
        ItemDef(SMOKE, "Granada de fumaça", "Cria uma cortina de fumaça que bloqueia a visão e os tiros.", ItemKind.THROWABLE, 0xFFB8C2C9.toInt(), 35),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: Int): ItemDef? = byId[id]
    fun isWeapon(id: Int) = id in RIFLE..LAUNCHER
    fun isThrowable(id: Int) = id == GRENADE || id == MOLOTOV || id == SMOKE
    val weapons: List<ItemDef> get() = all.filter { it.kind == ItemKind.WEAPON }
    val consumables: List<ItemDef> get() = all.filter { it.kind != ItemKind.WEAPON }

    /** Igual a itemMaxStack() no C++. */
    fun maxStack(id: Int, explosives: Int = 0): Int = when (id) {
        MEDKIT -> 3
        BANDAGE -> 5
        ANTIDOTE -> 3
        ADRENALINE -> 5
        FIRE_AMMO -> 5
        ARMOR -> 3
        GRENADE, MOLOTOV, SMOKE -> 8 + explosives / 3
        else -> if (isWeapon(id)) 1 else 0
    }
}

class ItemStack(var id: Int = 0, var count: Int = 0) {
    val empty: Boolean get() = id == 0 || count <= 0
    fun clear() {
        id = 0
        count = 0
    }
}

/**
 * Inventário estilo Minecraft: 6 espaços na barra rápida (usados em jogo) e 18 na mochila.
 * As armas nunca somem (arma = posse); consumíveis empilham.
 */
class Inventory {
    val slots = Array(SIZE) { ItemStack() }
    var weaponSlot = 0
    var throwSlot = -1

    operator fun get(i: Int): ItemStack = slots[i]

    fun count(id: Int): Int = slots.filter { it.id == id }.sumOf { it.count }
    fun has(id: Int): Boolean = count(id) > 0
    fun firstEmpty(): Int = slots.indexOfFirst { it.empty }
    fun weaponCount(): Int = slots.count { Items.isWeapon(it.id) }

    /** Adiciona e retorna o que não coube (barra rápida primeiro, como no Minecraft). */
    fun add(id: Int, amount: Int, explosives: Int = 0): Int {
        if (Items.get(id) == null || amount <= 0) return amount
        if (Items.isWeapon(id) && has(id)) return 0
        val max = Items.maxStack(id, explosives)
        var left = amount
        for (s in slots) {
            if (left <= 0) break
            if (s.id != id || s.count >= max) continue
            val t = minOf(left, max - s.count)
            s.count += t
            left -= t
        }
        for (s in slots) {
            if (left <= 0) break
            if (!s.empty) continue
            val t = minOf(left, max)
            s.id = id
            s.count = t
            left -= t
        }
        return left
    }

    fun canAdd(id: Int, explosives: Int = 0): Boolean {
        if (Items.isWeapon(id)) return !has(id) && firstEmpty() >= 0
        val max = Items.maxStack(id, explosives)
        return slots.any { (it.id == id && it.count < max) || it.empty }
    }

    /** Toque estilo Minecraft: troca dois espaços ou junta pilhas iguais. */
    fun moveOrSwap(from: Int, to: Int, explosives: Int = 0) {
        if (from == to || from !in 0 until SIZE || to !in 0 until SIZE) return
        val a = slots[from]
        val b = slots[to]
        if (!a.empty && a.id == b.id && !Items.isWeapon(a.id)) {
            val max = Items.maxStack(a.id, explosives)
            val t = minOf(a.count, max - b.count)
            b.count += t
            a.count -= t
            if (a.count <= 0) a.clear()
        } else {
            val id = a.id
            val n = a.count
            a.id = b.id
            a.count = b.count
            b.id = id
            b.count = n
            if (weaponSlot == from) weaponSlot = to else if (weaponSlot == to) weaponSlot = from
            if (throwSlot == from) throwSlot = to else if (throwSlot == to) throwSlot = from
        }
        normalize()
    }

    /** Descarta a pilha de consumíveis (armas não podem ser jogadas fora). */
    fun discard(slot: Int): Boolean {
        val s = slots.getOrNull(slot) ?: return false
        if (s.empty || Items.isWeapon(s.id)) return false
        s.clear()
        normalize()
        return true
    }

    // ------------------------------------------------------------------ equipamento estilo Free Fire
    // As armas equipadas ficam na barra rápida (é a barra que o HUD mostra): até 2 principais e a pistola.
    // O resto das armas fica guardado na mochila. Os consumíveis aparecem agrupados por tipo.

    fun firstSlotOf(id: Int): Int = slots.indexOfFirst { !it.empty && it.id == id }
    fun usedSlots(): Int = slots.count { !it.empty }

    /** Espaços das armas principais equipadas (na ordem da barra rápida), no máximo 2. */
    fun primarySlots(): List<Int> =
        (0 until HOTBAR).filter { Items.isWeapon(slots[it].id) && slots[it].id != Items.PISTOL }.take(MAX_PRIMARIES)

    /** Espaço da pistola equipada, ou -1. */
    fun pistolSlot(): Int = (0 until HOTBAR).firstOrNull { slots[it].id == Items.PISTOL } ?: -1

    /** Armas fora do equipamento (guardadas na mochila ou sobrando na barra). */
    fun spareWeaponSlots(): List<Int> {
        val equipped = primarySlots().toSet() + pistolSlot()
        return slots.indices.filter { Items.isWeapon(slots[it].id) && it !in equipped }
    }

    /**
     * Equipa a arma de [from]. Com [target] (espaço de uma arma equipada), as duas trocam de lugar e, se a arma
     * trocada estava na mão, a nova passa a ficar na mão. Sem alvo, ocupa um espaço vazio da barra rápida ou troca
     * com um consumível dela. Retorna false se não houver onde colocar.
     */
    fun equipWeapon(from: Int, target: Int = -1): Boolean {
        if (!Items.isWeapon(slots.getOrNull(from)?.id ?: 0)) return false
        if (target >= 0) {
            if (target == from) return true
            if (!Items.isWeapon(slots.getOrNull(target)?.id ?: 0)) return false
            val handWasTarget = weaponSlot == target
            moveOrSwap(from, target)
            if (handWasTarget) weaponSlot = target
            return true
        }
        if (from < HOTBAR) return true
        val dest = (0 until HOTBAR).firstOrNull { slots[it].empty }
            ?: (0 until HOTBAR).firstOrNull { !Items.isWeapon(slots[it].id) }
            ?: return false
        moveOrSwap(from, dest)
        return true
    }

    /** Guarda uma arma equipada na mochila. A arma da mão não pode ser guardada. */
    fun stowWeapon(slot: Int): Boolean {
        if (slot !in 0 until HOTBAR || slot == weaponSlot || !Items.isWeapon(slots[slot].id)) return false
        val dest = (HOTBAR until SIZE).firstOrNull { slots[it].empty } ?: return false
        moveOrSwap(slot, dest)
        return true
    }

    /** Põe na mão uma arma equipada (na barra rápida). */
    fun holdWeapon(slot: Int): Boolean {
        if (slot !in 0 until HOTBAR || !Items.isWeapon(slots[slot].id)) return false
        weaponSlot = slot
        return true
    }

    /**
     * Arruma o equipamento ao abrir a mochila: a arma da mão vai para a barra rápida e sobram no máximo 2 armas
     * principais nela (a da mão tem prioridade); as outras vão para a mochila se houver espaço.
     * Retorna true se algo mudou.
     */
    fun fitLoadout(): Boolean {
        val before = encode()
        normalize()
        if (weaponSlot >= HOTBAR) equipWeapon(weaponSlot)
        val primaries = (0 until HOTBAR).filter { Items.isWeapon(slots[it].id) && slots[it].id != Items.PISTOL }
        if (primaries.size > MAX_PRIMARIES) {
            val keep = (listOf(weaponSlot).filter { it in primaries } + primaries).distinct().take(MAX_PRIMARIES)
            for (slot in primaries) if (slot !in keep) stowWeapon(slot)
        }
        return encode() != before
    }

    /**
     * Tira [amount] unidades do consumível [id] (das últimas pilhas para as primeiras) e retorna quantas saíram.
     * Armas não saem por aqui.
     */
    fun removeAmount(id: Int, amount: Int): Int {
        if (Items.isWeapon(id) || amount <= 0) return 0
        var left = amount
        for (i in SIZE - 1 downTo 0) {
            if (left <= 0) break
            val s = slots[i]
            if (s.empty || s.id != id) continue
            val t = minOf(left, s.count)
            s.count -= t
            left -= t
            if (s.count <= 0) s.clear()
        }
        normalize()
        return amount - left
    }

    /** Garante uma arma na mão e índices válidos; um inventário sem armas recebe o fuzil. */
    fun normalize() {
        for (s in slots) if (s.empty || Items.get(s.id) == null) s.clear()
        if (weaponCount() == 0) {
            val free = firstEmpty().takeIf { it >= 0 } ?: 0
            slots[free].id = Items.RIFLE
            slots[free].count = 1
        }
        if (!Items.isWeapon(slots.getOrNull(weaponSlot)?.id ?: 0)) weaponSlot = slots.indexOfFirst { Items.isWeapon(it.id) }
        if (throwSlot >= 0 && !Items.isThrowable(slots.getOrNull(throwSlot)?.id ?: 0)) throwSlot = -1
    }

    fun toIntArray(): IntArray {
        val out = IntArray(INV_INT_COUNT)
        slots.forEachIndexed { i, s ->
            out[i * 2] = if (s.empty) 0 else s.id
            out[i * 2 + 1] = if (s.empty) 0 else s.count
        }
        out[SIZE * 2] = weaponSlot
        out[SIZE * 2 + 1] = throwSlot
        return out
    }

    /** Lê o inventário publicado pelo motor no HUD (fim da partida). */
    fun readFromHud(hud: FloatArray) {
        if (hud[Hud.INV_ENABLED] < 0.5f) return
        for (i in 0 until SIZE) {
            slots[i].id = hud[Hud.INV + i * 2].toInt()
            slots[i].count = hud[Hud.INV + i * 2 + 1].toInt()
        }
        weaponSlot = hud[Hud.WEAPON_SLOT].toInt()
        throwSlot = hud[Hud.THROW_SLOT].toInt()
        normalize()
    }

    fun encode(): String = slots.joinToString(",") { if (it.empty) "0:0" else "${it.id}:${it.count}" } + "|$weaponSlot|$throwSlot"

    fun copyFrom(other: Inventory) {
        for (i in 0 until SIZE) {
            slots[i].id = other.slots[i].id
            slots[i].count = other.slots[i].count
        }
        weaponSlot = other.weaponSlot
        throwSlot = other.throwSlot
    }

    companion object {
        const val SIZE = 24
        const val HOTBAR = 6
        const val MAX_PRIMARIES = 2

        /** Kit inicial: fuzil, pistola e alguns consumíveis na barra rápida. */
        fun starter(): Inventory = Inventory().apply {
            add(Items.RIFLE, 1)
            add(Items.PISTOL, 1)
            add(Items.GRENADE, 3)
            add(Items.SMOKE, 2)
            add(Items.BANDAGE, 2)
            add(Items.MEDKIT, 1)
            weaponSlot = 0
        }

        fun decode(text: String): Inventory {
            val inv = Inventory()
            if (text.isBlank()) return starter()
            val parts = text.split('|')
            parts[0].split(',').take(SIZE).forEachIndexed { i, e ->
                val kv = e.split(':')
                val id = kv.getOrNull(0)?.toIntOrNull() ?: 0
                val n = kv.getOrNull(1)?.toIntOrNull() ?: 0
                if (Items.get(id) != null && n > 0) {
                    inv.slots[i].id = id
                    inv.slots[i].count = n.coerceAtMost(maxOf(1, Items.maxStack(id, 60)))
                }
            }
            inv.weaponSlot = parts.getOrNull(1)?.toIntOrNull() ?: 0
            inv.throwSlot = parts.getOrNull(2)?.toIntOrNull() ?: -1
            inv.normalize()
            return inv
        }
    }
}
