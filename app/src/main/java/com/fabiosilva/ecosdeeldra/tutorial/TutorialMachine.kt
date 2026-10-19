package com.fabiosilva.ecosdeeldra.tutorial

import com.fabiosilva.ecosdeeldra.engine.Ev
import kotlin.math.PI
import kotlin.math.abs

/** O que o HUD deve realçar para a lição ativa. */
enum class CoachFocus { NONE, JOYSTICK, LOOK_AREA, FIRE_BUTTON, MAP_BUTTON, NEST_MARKER, NEST_CORE, CRYSTAL, PORTAL }

enum class Lesson(val title: String, val body: String, val focus: CoachFocus) {
    MOVE("MOVER", "Arraste o polegar na metade ESQUERDA da tela para andar.", CoachFocus.JOYSTICK),
    LOOK("OLHAR", "Arraste o dedo na metade DIREITA da tela para olhar ao redor.", CoachFocus.LOOK_AREA),
    SHOOT("ATIRAR", "Segure ATIRAR para disparar. MIRAR dá zoom e mais precisão.", CoachFocus.FIRE_BUTTON),
    MAP("MAPA", "Toque no MINIMAPA para abrir o mapa da ilha. Toque de novo para voltar.", CoachFocus.MAP_BUTTON),
    MARKER("SEGUIR O MARCADOR", "Siga o marcador roxo NINHO: ele aparece no mundo, na bússola e no mapa.", CoachFocus.NEST_MARKER),
    CORE("NÚCLEO DO NINHO", "O núcleo roxo pulsante é o ponto fraco do ninho. Atire nele!", CoachFocus.NEST_CORE),
    DESTROY("DESTRUIR O NINHO", "Atire até a barra do ninho zerar. Ele chama zumbis: continue andando.", CoachFocus.NEST_CORE),
    CRYSTAL("RECOLHER O CRISTAL", "Um cristal ciano caiu do ninho. Ande até ele para recolher.", CoachFocus.CRYSTAL),
    PORTAL("EXTRAIR", "Portal aberto! Entre na moldura roxa para extrair e vencer a fase.", CoachFocus.PORTAL);

    val bit: Int get() = 1 shl ordinal

    companion object {
        val ALL: Int = (1 shl values().size) - 1
    }
}

/** O que a HUD precisa para desenhar o cartão de dica. Imutável; comparável por igualdade. */
data class CoachHint(
    val lesson: Lesson,
    val step: Int,                  // quantas lições concluídas + 1 (1..total)
    val total: Int,                 // 9
    val title: String,
    val body: String,
    val progress: Float?,           // 0..1 para barra opcional (MARKER, DESTROY)
    val focus: CoachFocus,
    val focusIndex: Int = -1,       // índice do ninho (H_OBJ) / do cristal (H_CRYSTAL_ITEMS); -1 = n/a
    val celebrate: Boolean = false, // true = "concluída": mostrar `body` como confirmação
    val skippable: Boolean = true,  // mostrar o botão PULAR TUTORIAL
)

data class TutorialState(
    val doneMask: Int = 0,
    val base: Base? = null,         // fatos no 1º tick da corrida
    val lastYaw: Float = 0f,
    val yawAccum: Float = 0f,
    val mapOpened: Boolean = false,
    val markerStartDist: Float = 0f,
    val celebrate: Lesson? = null,
    val celebrateUntil: Long = 0L,
    val finished: Boolean = false,
) {
    data class Base(val distance: Float, val shots: Int)

    fun isDone(l: Lesson) = doneMask and l.bit != 0
}

sealed interface TutorialInput {
    val nowMs: Long

    data class Tick(val snap: TutorialSnapshot, override val nowMs: Long) : TutorialInput
    data class Engine(val type: Int, val a: Int, val b: Int, val c: Int, override val nowMs: Long) : TutorialInput
    data class MapOpened(override val nowMs: Long) : TutorialInput
    data class Skip(override val nowMs: Long) : TutorialInput
}

/** Resultado de um passo: novo estado, aviso a mostrar (null = nada) e o que mudou. */
data class Step(val state: TutorialState, val hint: CoachHint?, val newlyDone: Int = 0, val finished: Boolean = false)

/** Máquina de estados pura do tutorial jogável: sem Android, sem relógio próprio (o tempo entra por `nowMs`). */
object TutorialMachine {
    const val MOVE_METERS = 6f
    const val LOOK_RADIANS = 1.0f
    const val SHOTS_NEEDED = 3
    const val NEST_NEAR_METERS = 45f      // igual ao raio de NEST_NEAR no nativo
    const val MAP_SKIP_METERS = 150f
    const val CELEBRATE_MS = 1_400L
    const val CELEBRATE_LONG_MS = 3_200L

    fun start(savedMask: Int): TutorialState {
        val mask = savedMask and Lesson.ALL
        return TutorialState(doneMask = mask, finished = mask == Lesson.ALL)
    }

    fun reduce(prev: TutorialState, input: TutorialInput): Step {
        if (prev.finished) return Step(prev, null)
        var s = prev
        val now = input.nowMs

        fun mark(l: Lesson, loud: Boolean, implyEarlier: Boolean = true) {
            if (s.isDone(l)) return
            var mask = s.doneMask or l.bit
            if (implyEarlier) for (e in Lesson.values()) if (e.ordinal < l.ordinal && e != Lesson.MAP) mask = mask or e.bit
            s = s.copy(doneMask = mask)
            if (loud) {
                val long = l == Lesson.DESTROY || l == Lesson.CRYSTAL
                s = s.copy(celebrate = l, celebrateUntil = now + if (long) CELEBRATE_LONG_MS else CELEBRATE_MS)
            }
            if (s.doneMask == Lesson.ALL) s = s.copy(finished = true)
        }

        fun result(hint: CoachHint?) = Step(s, hint, s.doneMask and prev.doneMask.inv(), s.finished && !prev.finished)

        when (input) {
            is TutorialInput.Skip -> {
                s = s.copy(doneMask = Lesson.ALL, finished = true, celebrate = null)
                return result(null)
            }
            is TutorialInput.MapOpened -> {
                s = s.copy(mapOpened = true)
                mark(Lesson.MAP, loud = true, implyEarlier = false)
                return result(null)   // o jogo está pausado; o próximo Tick monta o aviso
            }
            is TutorialInput.Engine -> {
                when (input.type) {
                    Ev.NEST_DESTROYED -> mark(Lesson.DESTROY, loud = true)
                    Ev.CRYSTAL -> mark(Lesson.CRYSTAL, loud = true)
                    Ev.VICTORY -> {
                        mark(Lesson.PORTAL, loud = false)
                        s = s.copy(doneMask = Lesson.ALL, finished = true)
                    }
                }
                return result(null)
            }
            is TutorialInput.Tick -> Unit
        }

        val snap = (input as TutorialInput.Tick).snap
        if (!snap.valid || snap.dead) return result(null)
        val obj = snap.objective

        if (s.base == null) s = s.copy(base = TutorialState.Base(snap.distanceWalked, snap.shots), lastYaw = snap.yaw)
        val base = s.base!!
        s = s.copy(yawAccum = s.yawAccum + abs(wrapAngle(snap.yaw - s.lastYaw)), lastYaw = snap.yaw)

        // Fatos. "loud" só para a lição que estava no cartão; as outras fecham em silêncio.
        val active = pick(s, snap)
        fun fact(l: Lesson, ok: Boolean, implyEarlier: Boolean = true) {
            if (ok) mark(l, loud = l == active, implyEarlier = implyEarlier)
        }
        fact(Lesson.MOVE, snap.distanceWalked - base.distance >= MOVE_METERS)
        fact(Lesson.LOOK, s.yawAccum >= LOOK_RADIANS)
        fact(Lesson.SHOOT, snap.shots - base.shots >= SHOTS_NEEDED)
        fact(Lesson.MARKER, snap.nearNest >= 0 || obj.nestsDestroyed >= 1)
        fact(Lesson.CORE, (snap.nearNest >= 0 && snap.nearNestHp in 0f..0.999f) || obj.nestsDestroyed >= 1)
        fact(Lesson.DESTROY, obj.nestsDestroyed >= 1)
        fact(Lesson.CRYSTAL, obj.crystals >= 1)
        if (snap.victory) {
            mark(Lesson.PORTAL, loud = false)
            s = s.copy(doneMask = Lesson.ALL, finished = true)
        }
        if (snap.nearestNestDist <= MAP_SKIP_METERS || s.isDone(Lesson.MARKER)) mark(Lesson.MAP, loud = false, implyEarlier = false)
        if (s.finished) return result(null)

        // Aviso
        val celebrating = s.celebrate
        if (celebrating != null && now < s.celebrateUntil) return result(celebrationHint(celebrating, s, obj))
        val lesson = pick(s, snap) ?: return result(null)
        if (lesson == Lesson.MARKER && s.markerStartDist <= 0f && snap.nearestNestDist < Float.MAX_VALUE) {
            s = s.copy(markerStartDist = snap.nearestNestDist)
        }
        return result(hintFor(lesson, s, snap))
    }

    /** Lição que deve aparecer agora (contextuais antes das sequenciais). */
    fun pick(s: TutorialState, snap: TutorialSnapshot): Lesson? {
        val o = snap.objective
        fun open(l: Lesson) = !s.isDone(l)
        if (o.portalOpen && open(Lesson.PORTAL)) return Lesson.PORTAL
        if (o.pendingCrystals > 0 && open(Lesson.CRYSTAL)) return Lesson.CRYSTAL
        if (snap.nearNest >= 0) {
            if (open(Lesson.CORE)) return Lesson.CORE
            if (open(Lesson.DESTROY)) return Lesson.DESTROY
        }
        for (l in listOf(Lesson.MOVE, Lesson.LOOK, Lesson.SHOOT, Lesson.MAP, Lesson.MARKER)) {
            if (!open(l)) continue
            if (l == Lesson.MARKER && o.nestsDestroyed >= o.nestTotal) continue  // sem ninho vivo para seguir
            return l
        }
        return null
    }

    private fun hintFor(l: Lesson, s: TutorialState, snap: TutorialSnapshot): CoachHint {
        val progress = when (l) {
            Lesson.MARKER ->
                if (s.markerStartDist > NEST_NEAR_METERS && snap.nearestNestDist < Float.MAX_VALUE) {
                    (1f - (snap.nearestNestDist - NEST_NEAR_METERS) / (s.markerStartDist - NEST_NEAR_METERS)).coerceIn(0f, 1f)
                } else null
            Lesson.DESTROY -> if (snap.nearNestHp in 0f..1f) 1f - snap.nearNestHp else null
            else -> null
        }
        val index = when (l) {
            Lesson.MARKER, Lesson.CORE, Lesson.DESTROY -> if (snap.nearNest >= 0) snap.nearNest else snap.nearestNestIndex
            Lesson.CRYSTAL -> 0
            else -> -1
        }
        val step = (Integer.bitCount(s.doneMask) + 1).coerceAtMost(Lesson.values().size)
        return CoachHint(l, step, Lesson.values().size, l.title, l.body, progress, l.focus, index)
    }

    private fun celebrationHint(l: Lesson, s: TutorialState, o: Objective): CoachHint {
        val text = when (l) {
            Lesson.DESTROY -> "Ninho destruído! Agora recolha o cristal."
            Lesson.CRYSTAL ->
                if (o.portalOpen) "Cristal recolhido. O portal abriu!"
                else "Cristal recolhido. Repita nos outros ninhos: com os 3 cristais, o portal abre."
            else -> "Muito bem!"
        }
        return CoachHint(
            l, Integer.bitCount(s.doneMask).coerceAtLeast(1), Lesson.values().size, l.title, text, null,
            CoachFocus.NONE, celebrate = true,
        )
    }

    private fun wrapAngle(a: Float): Float {
        var x = a
        val tau = (PI * 2).toFloat()
        while (x > PI) x -= tau
        while (x < -PI) x += tau
        return x
    }
}
