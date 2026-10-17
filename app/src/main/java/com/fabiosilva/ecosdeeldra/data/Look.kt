package com.fabiosilva.ecosdeeldra.data

import android.graphics.Bitmap
import kotlin.random.Random

/** Partes editáveis do personagem de blocos. */
enum class LookPart { SKIN, HAIR_STYLE, HAIR_COLOR, EYES, FACE, TOP, TOP_COLOR, PANTS_COLOR, SHORTS, SHOES, HAT }

/**
 * Aparência de um personagem estilo Minecraft, codificada em 32 bits para o motor C++
 * (mesmo layout de `Look` em app/src/main/cpp/render/blocky.h).
 */
data class Look(
    val skin: Int = 1,
    val hairStyle: Int = 1,
    val hairColor: Int = 1,
    val eyes: Int = 0,
    val face: Int = 0,
    val top: Int = 0,
    val topColor: Int = 6,
    val pantsColor: Int = 0,
    val shoes: Int = 0,
    val hat: Int = 0,
    val shorts: Boolean = false,
) {
    fun encode(): Int =
        (skin and 7) or ((hairStyle and 7) shl 3) or ((hairColor and 7) shl 6) or ((eyes and 3) shl 9) or
            ((face and 3) shl 11) or ((top and 7) shl 13) or ((topColor and 15) shl 16) or ((pantsColor and 7) shl 20) or
            ((shoes and 3) shl 23) or ((hat and 7) shl 25) or (if (shorts) 1 shl 28 else 0)

    operator fun get(part: LookPart): Int = when (part) {
        LookPart.SKIN -> skin
        LookPart.HAIR_STYLE -> hairStyle
        LookPart.HAIR_COLOR -> hairColor
        LookPart.EYES -> eyes
        LookPart.FACE -> face
        LookPart.TOP -> top
        LookPart.TOP_COLOR -> topColor
        LookPart.PANTS_COLOR -> pantsColor
        LookPart.SHORTS -> if (shorts) 1 else 0
        LookPart.SHOES -> shoes
        LookPart.HAT -> hat
    }

    fun with(part: LookPart, value: Int): Look = when (part) {
        LookPart.SKIN -> copy(skin = value)
        LookPart.HAIR_STYLE -> copy(hairStyle = value)
        LookPart.HAIR_COLOR -> copy(hairColor = value)
        LookPart.EYES -> copy(eyes = value)
        LookPart.FACE -> copy(face = value)
        LookPart.TOP -> copy(top = value)
        LookPart.TOP_COLOR -> copy(topColor = value)
        LookPart.PANTS_COLOR -> copy(pantsColor = value)
        LookPart.SHORTS -> copy(shorts = value != 0)
        LookPart.SHOES -> copy(shoes = value)
        LookPart.HAT -> copy(hat = value)
    }

    companion object {
        fun decode(code: Int): Look = Look(
            skin = code and 7,
            hairStyle = (code shr 3) and 7,
            hairColor = (code shr 6) and 7,
            eyes = (code shr 9) and 3,
            face = (code shr 11) and 3,
            top = (code shr 13) and 7,
            topColor = (code shr 16) and 15,
            pantsColor = (code shr 20) and 7,
            shoes = (code shr 23) and 3,
            hat = (code shr 25) and 7,
            shorts = ((code shr 28) and 1) != 0,
        )

        /** Personagem sorteado só com peças livres (os visuais bloqueados ficam de fora). */
        fun random(p: Progress, random: Random = Random): Look {
            var look = Look()
            for (part in LookPart.entries) {
                val options = LookOptions.count(part).let { n -> (0 until n).filter { Cosmetics.available(p, part, it) } }
                if (options.isNotEmpty()) look = look.with(part, options[random.nextInt(options.size)])
            }
            return look.copy(hat = if (random.nextInt(3) == 0) look.hat else 0, face = if (random.nextInt(3) == 0) look.face else 0)
        }
    }
}

/** Um personagem criado pelo jogador. */
class Hero(var name: String, var look: Int)

/** Nomes e cores das opções (as cores são as mesmas da pele gerada no motor). */
object LookOptions {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    val skinColors = intArrayOf(rgb(255, 224, 196), rgb(241, 194, 160), rgb(224, 172, 128), rgb(198, 140, 98), rgb(161, 108, 72), rgb(120, 78, 52), rgb(84, 54, 36), rgb(120, 170, 96))
    val skinNames = listOf("Clara", "Pêssego", "Morena clara", "Morena", "Parda", "Negra", "Retinta", "Morto-vivo")
    val hairStyles = listOf("Careca", "Curto", "Longo", "Moicano", "Rabo de cavalo", "Black power", "Franja", "Coque")
    val hairColors = intArrayOf(rgb(34, 28, 26), rgb(92, 58, 34), rgb(222, 186, 96), rgb(178, 74, 38), rgb(180, 180, 184), rgb(58, 104, 214), rgb(232, 104, 170), rgb(80, 180, 80))
    val hairColorNames = listOf("Preto", "Castanho", "Loiro", "Ruivo", "Grisalho", "Azul", "Rosa", "Verde")
    val eyeColors = intArrayOf(rgb(92, 58, 34), rgb(64, 118, 224), rgb(70, 160, 80), rgb(120, 124, 132))
    val eyeNames = listOf("Castanhos", "Azuis", "Verdes", "Cinzas")
    val faces = listOf("Nada", "Barba", "Bigode", "Cicatriz")
    val tops = listOf("Camiseta", "Regata", "Jaqueta de couro", "Moletom", "Camisa xadrez", "Colete tático", "Armadura de ferro", "Jaleco")
    val topColors = intArrayOf(
        rgb(198, 52, 46), rgb(232, 124, 40), rgb(236, 196, 56), rgb(84, 170, 62), rgb(46, 98, 52), rgb(48, 176, 196),
        rgb(56, 108, 206), rgb(32, 46, 98), rgb(124, 64, 176), rgb(226, 110, 170), rgb(232, 232, 232), rgb(120, 124, 130),
        rgb(36, 38, 44), rgb(110, 72, 44), rgb(214, 192, 150), rgb(232, 186, 48),
    )
    val topColorNames = listOf("Vermelho", "Laranja", "Amarelo", "Verde", "Verde-escuro", "Ciano", "Azul", "Marinho", "Roxo", "Rosa", "Branco", "Cinza", "Preto", "Marrom", "Bege", "Dourado")
    val pantsColors = intArrayOf(rgb(58, 84, 140), rgb(34, 36, 42), rgb(176, 150, 102), rgb(84, 96, 60), rgb(110, 112, 118), rgb(150, 40, 40), rgb(220, 220, 224), rgb(92, 104, 70))
    val pantsNames = listOf("Jeans", "Preta", "Cáqui", "Verde militar", "Cinza", "Vermelha", "Branca", "Camuflada")
    val pantsStyles = listOf("Calça", "Bermuda")
    val shoeColors = intArrayOf(rgb(92, 60, 36), rgb(30, 30, 34), rgb(226, 226, 226), rgb(180, 40, 40))
    val shoeNames = listOf("Marrom", "Preto", "Branco", "Vermelho")
    val hats = listOf("Nenhum", "Boné", "Capacete militar", "Gorro", "Faixa ninja", "Óculos táticos", "Fone gamer", "Coroa")

    fun count(part: LookPart): Int = when (part) {
        LookPart.SKIN -> 8
        LookPart.HAIR_STYLE -> 8
        LookPart.HAIR_COLOR -> 8
        LookPart.EYES -> 4
        LookPart.FACE -> 4
        LookPart.TOP -> 8
        LookPart.TOP_COLOR -> 16
        LookPart.PANTS_COLOR -> 8
        LookPart.SHORTS -> 2
        LookPart.SHOES -> 4
        LookPart.HAT -> 8
    }

    fun name(part: LookPart, value: Int): String = when (part) {
        LookPart.SKIN -> skinNames[value]
        LookPart.HAIR_STYLE -> hairStyles[value]
        LookPart.HAIR_COLOR -> hairColorNames[value]
        LookPart.EYES -> eyeNames[value]
        LookPart.FACE -> faces[value]
        LookPart.TOP -> tops[value]
        LookPart.TOP_COLOR -> topColorNames[value]
        LookPart.PANTS_COLOR -> pantsNames[value]
        LookPart.SHORTS -> pantsStyles[value]
        LookPart.SHOES -> shoeNames[value]
        LookPart.HAT -> hats[value]
    }

    /** Cor de amostra da opção (ou null se a opção é um estilo, não uma cor). */
    fun color(part: LookPart, value: Int): Int? = when (part) {
        LookPart.SKIN -> skinColors[value]
        LookPart.HAIR_COLOR -> hairColors[value]
        LookPart.EYES -> eyeColors[value]
        LookPart.TOP_COLOR -> topColors[value]
        LookPart.PANTS_COLOR -> pantsColors[value]
        LookPart.SHOES -> shoeColors[value]
        else -> null
    }
}

/** Rosto 8x8 em pixel art (ícones do PvP, mapa e mochila), igual à frente da cabeça no jogo. */
object LookPainter {
    /** Largura/altura da miniatura de corpo inteiro usada nos cartões do editor. */
    const val BODY_W = 16
    const val BODY_H = 32

    private fun shade(c: Int, f: Float): Int {
        val r = (((c shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun face(look: Look): Bitmap {
        val px = IntArray(64)
        fun set(x: Int, y: Int, c: Int) {
            if (x in 0..7 && y in 0..7) px[y * 8 + x] = c
        }
        val skin = LookOptions.skinColors[look.skin and 7]
        val hair = LookOptions.hairColors[look.hairColor and 7]
        for (i in 0 until 64) px[i] = shade(skin, 0.94f + 0.08f * (((i * 7919) % 13) / 13f))
        val frontRows = when (look.hairStyle) { 1, 2, 4, 7 -> 1; 5, 6 -> 2; else -> 0 }
        for (y in 0 until frontRows) for (x in 0..7) set(x, y, hair)
        if (look.hairStyle == 6) { for (x in 0..5) set(x, 1, hair); set(0, 2, hair); set(1, 2, hair) }
        if (look.hairStyle == 3) { set(3, 0, hair); set(4, 0, hair) }
        val brow = if (look.hairStyle == 0 || look.hairStyle == 3) shade(skin, 0.7f) else shade(hair, 0.85f)
        for (x in intArrayOf(1, 2, 5, 6)) set(x, 3, brow)
        val iris = LookOptions.eyeColors[look.eyes and 3]
        set(1, 4, 0xFFF4F4F4.toInt()); set(2, 4, iris); set(5, 4, iris); set(6, 4, 0xFFF4F4F4.toInt())
        set(3, 5, shade(skin, 0.86f)); set(4, 5, shade(skin, 0.86f))
        set(3, 6, shade(skin, 0.58f)); set(4, 6, shade(skin, 0.58f))
        when (look.face) {
            1 -> for (y in 5..7) for (x in 0..7) if (!(y == 6 && (x == 3 || x == 4))) set(x, y, shade(hair, 0.9f))
            2 -> for (x in 2..5) set(x, 5, shade(hair, 0.85f))
            3 -> { set(6, 3, 0xFFAA4646.toInt()); set(7, 5, 0xFFAA4646.toInt()); set(6, 6, 0xFFAA4646.toInt()); set(5, 7, 0xFFAA4646.toInt()) }
        }
        val hatColor = LookOptions.topColors[look.topColor and 15]
        when (look.hat) {
            1 -> for (x in 0..7) { set(x, 0, hatColor); set(x, 1, shade(hatColor, 0.75f)) }
            2 -> for (y in 0..2) for (x in 0..7) set(x, y, if (y == 2) 0xFF465030.toInt() else 0xFF5C6840.toInt())
            3 -> for (y in 0..2) for (x in 0..7) set(x, y, if (y == 2) 0xFFECECEC.toInt() else hatColor)
            4 -> for (x in 0..7) set(x, 2, 0xFFC82828.toInt())
            5 -> { for (x in 0..7) set(x, 4, 0xFF1E1E22.toInt()); set(1, 4, 0xFF46C8DC.toInt()); set(2, 4, 0xFF46C8DC.toInt()); set(5, 4, 0xFF46C8DC.toInt()); set(6, 4, 0xFF46C8DC.toInt()) }
            6 -> { set(0, 3, hatColor); set(0, 4, hatColor); set(7, 3, hatColor); set(7, 4, hatColor) }
            7 -> for (x in 0..7) set(x, 0, if (x % 3 == 0) 0xFFDC2846.toInt() else 0xFFECBE34.toInt())
        }
        return Bitmap.createBitmap(px, 8, 8, Bitmap.Config.ARGB_8888)
    }

    /** Miniatura de corpo inteiro ([BODY_W]x[BODY_H]) com fundo transparente. */
    fun body(look: Look): Bitmap =
        Bitmap.createBitmap(bodyPixels(look), BODY_W, BODY_H, Bitmap.Config.ARGB_8888)

    /** Pixels da miniatura de corpo (função pura, sem Android, para poder ser testada). */
    fun bodyPixels(look: Look): IntArray {
        val px = IntArray(BODY_W * BODY_H)
        fun set(x: Int, y: Int, c: Int) {
            if (x in 0 until BODY_W && y in 0 until BODY_H) px[y * BODY_W + x] = c
        }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0..y1) for (x in x0..x1) set(x, y, c)
        }
        val skin = LookOptions.skinColors[look.skin and 7]
        val hair = LookOptions.hairColors[look.hairColor and 7]
        val top = LookOptions.topColors[look.topColor and 15]
        val pants = LookOptions.pantsColors[look.pantsColor and 7]
        val shoe = LookOptions.shoeColors[look.shoes and 3]
        val skinLine = shade(skin, 0.82f)
        val scar = 0xFFAA4646.toInt()

        // Cabeça e cabelo.
        fill(5, 3, 10, 10, skin)
        when (look.hairStyle and 7) {
            1 -> fill(5, 2, 10, 4, hair)
            2 -> { fill(5, 2, 10, 4, hair); fill(4, 5, 4, 10, hair); fill(11, 5, 11, 10, hair) }
            3 -> fill(7, 2, 8, 4, hair)
            4 -> { fill(5, 2, 10, 4, hair); fill(11, 4, 12, 7, hair) }
            5 -> fill(4, 1, 11, 5, hair)
            6 -> fill(5, 3, 10, 5, hair)
            7 -> { fill(6, 1, 9, 4, hair); fill(9, 0, 11, 1, hair) }
        }
        // Rosto.
        set(6, 7, LookOptions.eyeColors[look.eyes and 3])
        set(9, 7, LookOptions.eyeColors[look.eyes and 3])
        set(7, 9, skinLine); set(8, 9, skinLine)
        when (look.face) {
            1 -> fill(5, 9, 10, 10, shade(hair, 0.9f))
            2 -> fill(6, 8, 9, 8, shade(hair, 0.85f))
            3 -> { set(6, 6, scar); set(10, 8, scar); set(5, 10, scar) }
        }
        // Acessório de cabeça.
        val hatColor = top
        when (look.hat and 7) {
            1 -> { fill(5, 1, 10, 3, hatColor); fill(4, 3, 11, 3, shade(hatColor, 0.7f)) }
            2 -> { fill(4, 1, 11, 3, 0xFF5C6840.toInt()); fill(4, 4, 11, 4, 0xFF465030.toInt()) }
            3 -> { fill(5, 1, 10, 3, hatColor); fill(5, 4, 10, 4, 0xFFECECEC.toInt()) }
            4 -> fill(5, 6, 10, 6, 0xFFC82828.toInt())
            5 -> { fill(5, 7, 7, 7, 0xFF1E1E22.toInt()); fill(8, 7, 10, 7, 0xFF1E1E22.toInt()); set(7, 7, 0xFF46C8DC.toInt()); set(8, 7, 0xFF46C8DC.toInt()) }
            6 -> { fill(4, 2, 4, 7, 0xFF2A2A30.toInt()); fill(11, 2, 11, 7, 0xFF2A2A30.toInt()); fill(4, 1, 11, 1, 0xFF2A2A30.toInt()) }
            7 -> { for (x in 5..10) set(x, 2, if (x % 3 == 0) 0xFFDC2846.toInt() else 0xFFECBE34.toInt()); fill(6, 1, 9, 1, 0xFFECBE34.toInt()) }
        }
        // Braços e torso.
        fill(3, 11, 4, 20, skin)
        fill(11, 11, 12, 20, skin)
        fill(5, 11, 10, 20, top)
        when (look.top and 7) {
            1 -> { fill(5, 11, 10, 20, skin); fill(5, 11, 5, 20, top); fill(10, 11, 10, 20, top) }
            2 -> { fill(5, 11, 10, 20, shade(top, 0.82f)); fill(5, 11, 10, 11, shade(top, 0.6f)) }
            3 -> { fill(5, 11, 10, 11, shade(top, 0.7f)); fill(6, 16, 9, 18, shade(top, 0.85f)) }
            4 -> for (y in 11..20) for (x in 5..10) set(x, y, if ((x + y) % 2 == 0) top else shade(top, 0.75f))
            5 -> { fill(5, 11, 10, 20, shade(top, 0.9f)); fill(6, 11, 6, 20, shade(top, 0.55f)); fill(9, 11, 9, 20, shade(top, 0.55f)) }
            6 -> { fill(5, 11, 10, 20, 0xFFB8BCC4.toInt()); fill(3, 11, 4, 12, 0xFFD8DCE4.toInt()); fill(11, 11, 12, 12, 0xFFD8DCE4.toInt()) }
            7 -> {
                fill(5, 11, 10, 20, 0xFFF0F3F8.toInt())
                fill(5, 11, 5, 20, shade(top, 0.9f)); fill(10, 11, 10, 20, shade(top, 0.9f))
                fill(7, 14, 8, 15, 0xFFB0202A.toInt())
            }
        }
        // Pernas e sapatos.
        val legBottom = if (look.shorts) 24 else 30
        fill(5, 21, 7, legBottom, pants)
        fill(8, 21, 10, legBottom, shade(pants, 0.92f))
        fill(5, 21, 10, 21, shade(pants, 0.8f))
        if (look.shorts) fill(5, 25, 10, 30, skin)
        fill(5, 31, 7, 31, shoe)
        fill(8, 31, 10, 31, shade(shoe, 0.9f))
        return px
    }
}
