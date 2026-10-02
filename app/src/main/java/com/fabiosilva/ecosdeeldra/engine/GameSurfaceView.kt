package com.fabiosilva.ecosdeeldra.engine

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.Choreographer
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Superfície OpenGL ES 3 que hospeda o motor C++.
 * Renderiza sob demanda a no máximo ~60 FPS (o aparelho pode ter tela de 120 Hz).
 */
@SuppressLint("ViewConstructor")
class GameSurfaceView(
    context: Context,
    private val onEvents: (IntArray, Int) -> Unit,
) : GLSurfaceView(context), Choreographer.FrameCallback {

    private val events = IntArray(4096)
    private var lastRequest = 0L
    private var running = false
    private var renderScale = 0.8f
    private var viewW = 0
    private var viewH = 0

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                NativeBridge.surfaceCreated()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                NativeBridge.surfaceChanged(width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                NativeBridge.drawFrame(System.nanoTime())
                val n = NativeBridge.drainEvents(events)
                if (n > 0) onEvents(events, n)
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** 0 = baixa (60% da resolução), 1 = média (80%), 2 = alta (100%). */
    fun setQuality(level: Int) {
        renderScale = when (level) { 0 -> 0.6f; 2 -> 1.0f; else -> 0.8f }
        applyFixedSize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w
        viewH = h
        applyFixedSize()
    }

    private fun applyFixedSize() {
        if (viewW <= 0 || viewH <= 0) return
        holder.setFixedSize((viewW * renderScale).toInt().coerceAtLeast(320), (viewH * renderScale).toInt().coerceAtLeast(180))
    }

    fun start() {
        if (running) return
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (frameTimeNanos - lastRequest >= 15_500_000L) {
            lastRequest = frameTimeNanos
            requestRender()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Executa um comando do motor na thread GL. */
    fun engine(block: () -> Unit) = queueEvent(block)
}
