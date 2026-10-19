package com.fabiosilva.ecosdeeldra.data

/** Etiqueta colorida do painel de atributos (ex.: DANO em vermelho, LONGO ALCANCE em azul). */
data class WeaponTag(val label: String, val color: Int)

/**
 * Atributos exibidos na mochila (estilo Free Fire). Os números vêm de kWeapons em
 * app/src/main/cpp/game/game_items.cpp: dano por disparo (chumbos somados), disparos por minuto (60 / intervalo),
 * alcance em metros, precisão (100 menos a média da dispersão do quadril e da mira, em milésimos) e velocidade
 * de movimento (moveMul x 100). Lança-chamas e lança-granadas acertam em área: a precisão deles é fixa.
 */
data class WeaponSpec(
    val category: String,
    val damage: Int,
    val fireRate: Int,
    val range: Int,
    val accuracy: Int,
    val moveSpeed: Int,
    val tags: List<WeaponTag>,
    /** Traços que aparecem como "acessórios" no card da arma. */
    val traits: List<String> = emptyList(),
)

object WeaponSpecs {
    const val KNIFE = -1

    // Cores das etiquetas.
    private const val RED = 0xFFD9545E.toInt()
    private const val BLUE = 0xFF4F8FE6.toInt()
    private const val ORANGE = 0xFFE8873A.toInt()
    private const val YELLOW = 0xFFD9A62B.toInt()
    private const val TEAL = 0xFF2FB3A3.toInt()
    private const val PURPLE = 0xFF8E63D9.toInt()
    private const val GREEN = 0xFF5DAA46.toInt()

    private val DANO = WeaponTag("DANO", RED)
    private val LONGO = WeaponTag("LONGO ALCANCE", BLUE)
    private val CURTO = WeaponTag("CURTO ALCANCE", ORANGE)
    private val CADENCIA = WeaponTag("CADÊNCIA", YELLOW)
    private val MOBILIDADE = WeaponTag("MOBILIDADE", TEAL)
    private val PRECISAO = WeaponTag("PRECISÃO", PURPLE)
    private val PERFURANTE = WeaponTag("PERFURANTE", PURPLE)
    private val VERSATIL = WeaponTag("VERSÁTIL", GREEN)
    private val FOGO = WeaponTag("FOGO", ORANGE)
    private val EXPLOSIVO = WeaponTag("EXPLOSIVO", RED)
    private val CORPO = WeaponTag("CORPO A CORPO", ORANGE)

    private val specs = mapOf(
        Items.RIFLE to WeaponSpec("FUZIL", 18, 600, 120, 90, 100, listOf(VERSATIL, LONGO)),
        Items.PISTOL to WeaponSpec("PISTOLA", 30, 300, 90, 93, 108, listOf(MOBILIDADE, PRECISAO)),
        Items.SHOTGUN to WeaponSpec("ESCOPETA", 117, 75, 32, 35, 95, listOf(DANO, CURTO), listOf("9 CHUMBOS")),
        Items.SMG to WeaponSpec("SUBMETRALHADORA", 12, 923, 80, 81, 105, listOf(CADENCIA, MOBILIDADE)),
        Items.SNIPER to WeaponSpec("RIFLE DE PRECISÃO", 120, 52, 220, 80, 92, listOf(DANO, LONGO), listOf("ZOOM 2X", "PERFURA 3")),
        Items.LMG to WeaponSpec("METRALHADORA", 17, 706, 120, 79, 82, listOf(CADENCIA, PERFURANTE), listOf("PERFURA 1")),
        Items.FLAMER to WeaponSpec("LANÇA-CHAMAS", 9, 857, 9, 60, 95, listOf(FOGO, CURTO), listOf("INCENDEIA")),
        Items.LAUNCHER to WeaponSpec("LANÇA-GRANADAS", 160, 60, 70, 70, 90, listOf(EXPLOSIVO, DANO), listOf("EXPLOSIVO")),
        // Faca: ataque corpo a corpo (botão ∞), 55 de dano a ~2,4 m, uma vez a cada 0,55 s.
        KNIFE to WeaponSpec("CORPO A CORPO", 55, 109, 2, 100, 100, listOf(CORPO)),
    )

    /** Máximos usados para encher as barras. */
    const val MAX_DAMAGE = 160
    const val MAX_FIRE_RATE = 923
    const val MAX_RANGE = 220
    const val MAX_ACCURACY = 100
    const val MAX_MOVE = 120

    fun get(id: Int): WeaponSpec? = specs[id]
}
