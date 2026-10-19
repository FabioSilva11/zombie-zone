package com.fabiosilva.ecosdeeldra.tutorial

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.NativeBridge
import com.fabiosilva.ecosdeeldra.engine.Res

/** Quem desenha o cartão do tutorial (o HudView). `null` remove o cartão. Chamado na thread de UI. */
interface CoachSink {
    fun showCoach(hint: CoachHint?)
}

/**
 * Cola Android do tutorial: lê o HUD a 10 Hz, alimenta a [TutorialMachine] e publica o aviso no [CoachSink].
 * Roda inteiro na thread de UI (os eventos do motor chegam por `runOnUiThread` na MainActivity).
 * Não é testável na JVM; a lógica está toda em [TutorialMachine].
 */
class CoachController(
    initialMask: Int,
    private val sink: CoachSink,
    // A persistência fica nos callbacks: no "rever tutorial" eles gravam em memória, nunca no save.
    private val onMask: (Int) -> Unit,
    private val onFinished: () -> Unit,
    private val onCelebrate: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val hudBuf = FloatArray(Hud.SIZE)
    private val resBuf = FloatArray(Res.SIZE)
    private var state = TutorialMachine.start(initialMask)
    private var lastHint: CoachHint? = null
    private var running = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    val active: Boolean get() = !state.finished

    fun start() {
        if (state.finished || running) return
        running = true
        handler.post(ticker)
    }

    /** Mapa, pausa, mochila, anúncio, derrota: o cartão some enquanto o jogo não está rodando. */
    fun pause() {
        running = false
        handler.removeCallbacks(ticker)
    }

    /** Volta de uma pausa e republica o cartão. */
    fun resume() {
        if (state.finished || running) return
        running = true
        lastHint = null
        handler.post(ticker)
    }

    fun stop() {
        pause()
        sink.showCoach(null)
        lastHint = null
    }

    fun onEngineEvent(type: Int, a: Int, b: Int, c: Int) = apply(TutorialInput.Engine(type, a, b, c, SystemClock.uptimeMillis()))
    fun onMapOpened() = apply(TutorialInput.MapOpened(SystemClock.uptimeMillis()))
    fun skip() = apply(TutorialInput.Skip(SystemClock.uptimeMillis()))

    private fun tick() {
        NativeBridge.copyHud(hudBuf)
        NativeBridge.copyResult(resBuf)
        apply(TutorialInput.Tick(TutorialSnapshot.from(hudBuf, resBuf), SystemClock.uptimeMillis()))
    }

    private fun apply(input: TutorialInput) {
        val step = TutorialMachine.reduce(state, input)
        if (step.state.doneMask != state.doneMask) {
            onMask(step.state.doneMask)
            Log.i(TAG, "mascara=0x%x".format(step.state.doneMask))
        }
        if (step.state.celebrate != state.celebrate && step.state.celebrate != null) onCelebrate()
        state = step.state
        // Eventos do motor/mapa/pular devolvem hint nulo: só o Tick (10 Hz) republica o cartão, senão ele pisca no combate.
        if (input is TutorialInput.Tick && step.hint != lastHint) {
            lastHint = step.hint
            sink.showCoach(step.hint)
        }
        if (step.finished) {
            onFinished()
            pause()
            lastHint = null
            sink.showCoach(null)
        }
    }

    private companion object {
        const val TAG = "ZZCoach"
        const val TICK_MS = 100L
    }
}
