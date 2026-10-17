package com.fabiosilva.ecosdeeldra.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Geometrias reutilizadas pelo lobby e pelos diálogos de recompensa preservados. */
object Shapes {
    fun chamfer(path: Path, l: Float, t: Float, r: Float, b: Float, c: Float) {
        path.reset()
        path.moveTo(l + c, t); path.lineTo(r, t); path.lineTo(r, b - c)
        path.lineTo(r - c, b); path.lineTo(l, b); path.lineTo(l, t + c); path.close()
    }

    fun slanted(path: Path, l: Float, t: Float, r: Float, b: Float, skew: Float) {
        path.reset()
        path.moveTo(l + skew, t); path.lineTo(r, t); path.lineTo(r - skew, b); path.lineTo(l, b); path.close()
    }

    fun chevron(path: Path, l: Float, t: Float, r: Float, b: Float, tip: Float) {
        path.reset()
        path.moveTo(l, t); path.lineTo(r - tip, t); path.lineTo(r, (t + b) / 2f)
        path.lineTo(r - tip, b); path.lineTo(l, b); path.lineTo(l + tip, (t + b) / 2f); path.close()
    }
}

/** Ícones da interface, todos desenhados em vetor (sem imagens). */
enum class Icon {
    GEAR, BAG, BULLET, STAR, DIAMOND, HEART, LOCK, CHECK, CHEST, SWORDS, CLOSE, PLAY,
    MISSIONS, UPGRADE, VAULT, RIFLE, HELMET, VEST, PISTOL, FIST, JUMP, SCOPE, WIFI, GIFT, PLUS, MAP_FLAG,
    SKIN, HAIR, FACE, SHIRT, PANTS, ACCESSORY,
}

object Icons {
    private val path = Path()
    private val rect = RectF()

    /**
     * Desenha [icon] centrado em ([cx], [cy]). [s] é a metade do lado da caixa do ícone.
     * O [paint] é reaproveitado: estilo e cor são redefinidos aqui e o estilo volta a FILL no fim.
     */
    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, s: Float, color: Int, paint: Paint) {
        fun x(u: Float) = cx + u * s
        fun y(v: Float) = cy + v * s
        fun fillMode() { paint.style = Paint.Style.FILL; paint.color = color }
        fun strokeMode(width: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * width
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = color
        }
        fun poly(vararg pts: Float) {
            path.reset()
            path.moveTo(x(pts[0]), y(pts[1]))
            var i = 2
            while (i < pts.size) { path.lineTo(x(pts[i]), y(pts[i + 1])); i += 2 }
            path.close()
        }

        when (icon) {
            Icon.CLOSE -> {
                strokeMode(0.3f)
                canvas.drawLine(x(-0.7f), y(-0.7f), x(0.7f), y(0.7f), paint)
                canvas.drawLine(x(0.7f), y(-0.7f), x(-0.7f), y(0.7f), paint)
            }
            Icon.CHECK -> {
                strokeMode(0.3f)
                path.reset()
                path.moveTo(x(-0.75f), y(0.05f)); path.lineTo(x(-0.25f), y(0.6f)); path.lineTo(x(0.8f), y(-0.6f))
                canvas.drawPath(path, paint)
            }
            Icon.PLUS -> {
                strokeMode(0.3f)
                canvas.drawLine(x(-0.7f), y(0f), x(0.7f), y(0f), paint)
                canvas.drawLine(x(0f), y(-0.7f), x(0f), y(0.7f), paint)
            }
            Icon.PLAY -> { fillMode(); poly(-0.45f, -0.75f, 0.8f, 0f, -0.45f, 0.75f); canvas.drawPath(path, paint) }
            Icon.STAR -> {
                fillMode()
                path.reset()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 1f else 0.42f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val px = x((r * cos(a)).toFloat()); val py = y((r * sin(a)).toFloat() + 0.06f)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.DIAMOND -> {
                fillMode()
                poly(-0.55f, -0.85f, 0.55f, -0.85f, 1f, -0.2f, 0f, 1f, -1f, -0.2f)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.09f
                paint.color = Color.argb(110, 255, 255, 255)
                canvas.drawLine(x(-1f), y(-0.2f), x(1f), y(-0.2f), paint)
                canvas.drawLine(x(-0.3f), y(-0.85f), x(-0.5f), y(-0.2f), paint)
                canvas.drawLine(x(0.3f), y(-0.85f), x(0.5f), y(-0.2f), paint)
                canvas.drawLine(x(-0.5f), y(-0.2f), x(0f), y(0.95f), paint)
                canvas.drawLine(x(0.5f), y(-0.2f), x(0f), y(0.95f), paint)
            }
            Icon.HEART -> {
                fillMode()
                path.reset()
                path.moveTo(x(0f), y(0.9f))
                path.cubicTo(x(-1.3f), y(0f), x(-0.7f), y(-1f), x(0f), y(-0.4f))
                path.cubicTo(x(0.7f), y(-1f), x(1.3f), y(0f), x(0f), y(0.9f))
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.LOCK -> {
                fillMode()
                rect.set(x(-0.7f), y(-0.05f), x(0.7f), y(0.9f))
                canvas.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint)
                strokeMode(0.2f)
                rect.set(x(-0.45f), y(-0.85f), x(0.45f), y(0.05f))
                canvas.drawArc(rect, 180f, 180f, false, paint)
                canvas.drawLine(x(-0.45f), y(-0.4f), x(-0.45f), y(-0.02f), paint)
                canvas.drawLine(x(0.45f), y(-0.4f), x(0.45f), y(-0.02f), paint)
            }
            Icon.GEAR -> {
                fillMode()
                for (i in 0 until 8) {
                    canvas.save()
                    canvas.rotate(45f * i, cx, cy)
                    rect.set(x(-0.15f), y(-1f), x(0.15f), y(-0.55f))
                    canvas.drawRect(rect, paint)
                    canvas.restore()
                }
                strokeMode(0.3f)
                canvas.drawCircle(cx, cy, s * 0.52f, paint)
            }
            Icon.BAG -> {
                fillMode()
                rect.set(x(-0.68f), y(-0.5f), x(0.68f), y(0.95f))
                canvas.drawRoundRect(rect, s * 0.28f, s * 0.28f, paint)
                strokeMode(0.16f)
                rect.set(x(-0.32f), y(-1f), x(0.32f), y(-0.25f))
                canvas.drawArc(rect, 180f, 180f, false, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(80, 0, 0, 0)
                rect.set(x(-0.42f), y(0.28f), x(0.42f), y(0.78f))
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                canvas.drawRect(x(-0.68f), y(-0.12f), x(0.68f), y(-0.04f), paint)
            }
            Icon.BULLET -> {
                canvas.save()
                canvas.rotate(40f, cx, cy)
                fillMode()
                path.reset()
                path.moveTo(x(-0.28f), y(-0.2f))
                path.quadTo(x(-0.28f), y(-0.95f), x(0f), y(-1f))
                path.quadTo(x(0.28f), y(-0.95f), x(0.28f), y(-0.2f))
                path.lineTo(x(0.28f), y(0.7f)); path.lineTo(x(-0.28f), y(0.7f))
                path.close()
                canvas.drawPath(path, paint)
                rect.set(x(-0.36f), y(0.78f), x(0.36f), y(1f))
                canvas.drawRect(rect, paint)
                canvas.restore()
            }
            Icon.CHEST -> {
                fillMode()
                rect.set(x(-0.9f), y(-0.65f), x(0.9f), y(-0.02f))
                canvas.drawRoundRect(rect, s * 0.2f, s * 0.2f, paint)
                paint.color = BlockDrawable.shade(color, 0.75f)
                rect.set(x(-0.9f), y(0.02f), x(0.9f), y(0.85f))
                canvas.drawRect(rect, paint)
                paint.color = Color.argb(150, 0, 0, 0)
                rect.set(x(-0.14f), y(-0.2f), x(0.14f), y(0.22f))
                canvas.drawRect(rect, paint)
            }
            Icon.GIFT -> {
                fillMode()
                rect.set(x(-0.85f), y(-0.3f), x(0.85f), y(0.95f))
                canvas.drawRect(rect, paint)
                rect.set(x(-0.95f), y(-0.65f), x(0.95f), y(-0.2f))
                canvas.drawRect(rect, paint)
                paint.color = Color.argb(140, 0, 0, 0)
                canvas.drawRect(x(-0.12f), y(-0.65f), x(0.12f), y(0.95f), paint)
                strokeMode(0.16f)
                paint.color = color
                rect.set(x(-0.6f), y(-1f), x(0f), y(-0.62f)); canvas.drawOval(rect, paint)
                rect.set(x(0f), y(-1f), x(0.6f), y(-0.62f)); canvas.drawOval(rect, paint)
            }
            Icon.MAP_FLAG -> {
                strokeMode(0.18f)
                canvas.drawLine(x(-0.6f), y(-0.95f), x(-0.6f), y(0.95f), paint)
                fillMode()
                poly(-0.6f, -0.9f, 0.85f, -0.5f, -0.6f, -0.05f)
                canvas.drawPath(path, paint)
            }
            Icon.SWORDS -> {
                strokeMode(0.22f)
                canvas.drawLine(x(-0.85f), y(-0.85f), x(0.85f), y(0.85f), paint)
                canvas.drawLine(x(0.85f), y(-0.85f), x(-0.85f), y(0.85f), paint)
                strokeMode(0.18f)
                canvas.drawLine(x(-0.2f), y(0.55f), x(-0.7f), y(0.05f), paint)
                canvas.drawLine(x(0.2f), y(0.55f), x(0.7f), y(0.05f), paint)
            }
            Icon.MISSIONS -> {
                strokeMode(0.16f)
                rect.set(x(-0.7f), y(-0.8f), x(0.7f), y(0.95f))
                canvas.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint)
                fillMode()
                rect.set(x(-0.32f), y(-1f), x(0.32f), y(-0.55f))
                canvas.drawRoundRect(rect, s * 0.1f, s * 0.1f, paint)
                strokeMode(0.13f)
                canvas.drawLine(x(-0.4f), y(-0.1f), x(0.4f), y(-0.1f), paint)
                canvas.drawLine(x(-0.4f), y(0.28f), x(0.4f), y(0.28f), paint)
                canvas.drawLine(x(-0.4f), y(0.65f), x(0.1f), y(0.65f), paint)
            }
            Icon.UPGRADE -> {
                fillMode()
                poly(0f, -0.95f, 0.85f, 0f, 0.38f, 0f, 0.38f, 0.9f, -0.38f, 0.9f, -0.38f, 0f, -0.85f, 0f)
                canvas.drawPath(path, paint)
            }
            Icon.JUMP -> {
                strokeMode(0.26f)
                path.reset()
                path.moveTo(x(-0.7f), y(0.15f)); path.lineTo(x(0f), y(-0.45f)); path.lineTo(x(0.7f), y(0.15f))
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(x(-0.7f), y(0.8f)); path.lineTo(x(0f), y(0.2f)); path.lineTo(x(0.7f), y(0.8f))
                canvas.drawPath(path, paint)
            }
            Icon.VAULT -> {
                strokeMode(0.16f)
                rect.set(x(-0.85f), y(-0.8f), x(0.85f), y(0.8f))
                canvas.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint)
                canvas.drawCircle(cx, cy, s * 0.3f, paint)
                canvas.drawLine(x(0f), y(-0.3f), x(0f), y(-0.62f), paint)
                canvas.drawLine(x(0f), y(0.3f), x(0f), y(0.62f), paint)
            }
            Icon.SCOPE -> {
                strokeMode(0.14f)
                canvas.drawCircle(cx, cy, s * 0.6f, paint)
                canvas.drawLine(x(-1f), y(0f), x(-0.3f), y(0f), paint)
                canvas.drawLine(x(1f), y(0f), x(0.3f), y(0f), paint)
                canvas.drawLine(x(0f), y(-1f), x(0f), y(-0.3f), paint)
                canvas.drawLine(x(0f), y(1f), x(0f), y(0.3f), paint)
                fillMode()
                canvas.drawCircle(cx, cy, s * 0.1f, paint)
            }
            Icon.WIFI -> {
                strokeMode(0.16f)
                for (k in 1..3) {
                    val r = 0.3f * k
                    rect.set(x(-r), y(0.75f - r), x(r), y(0.75f + r))
                    canvas.drawArc(rect, 225f, 90f, false, paint)
                }
                fillMode()
                canvas.drawCircle(x(0f), y(0.75f), s * 0.1f, paint)
            }
            Icon.RIFLE -> {
                fillMode()
                rect.set(x(-1f), y(-0.12f), x(-0.3f), y(0f)); canvas.drawRect(rect, paint)
                rect.set(x(-0.35f), y(-0.22f), x(0.5f), y(0.16f)); canvas.drawRect(rect, paint)
                rect.set(x(-0.2f), y(-0.34f), x(0.2f), y(-0.22f)); canvas.drawRect(rect, paint)
                poly(0.5f, -0.16f, 1f, -0.05f, 1f, 0.32f, 0.78f, 0.32f, 0.5f, 0.12f)
                canvas.drawPath(path, paint)
                poly(-0.1f, 0.16f, 0.16f, 0.16f, 0.22f, 0.8f, -0.04f, 0.8f)
                canvas.drawPath(path, paint)
                poly(0.22f, 0.16f, 0.42f, 0.16f, 0.46f, 0.6f, 0.32f, 0.6f)
                canvas.drawPath(path, paint)
            }
            Icon.PISTOL -> {
                fillMode()
                rect.set(x(-0.95f), y(-0.45f), x(0.85f), y(-0.05f)); canvas.drawRect(rect, paint)
                poly(0.2f, -0.05f, 0.8f, -0.05f, 0.62f, 0.85f, 0.22f, 0.85f)
                canvas.drawPath(path, paint)
                strokeMode(0.1f)
                rect.set(x(-0.1f), y(-0.05f), x(0.3f), y(0.4f)); canvas.drawArc(rect, 0f, 180f, false, paint)
            }
            Icon.FIST -> {
                fillMode()
                rect.set(x(-0.7f), y(-0.25f), x(0.75f), y(0.8f)); canvas.drawRoundRect(rect, s * 0.25f, s * 0.25f, paint)
                for (i in 0 until 4) {
                    rect.set(x(-0.7f + i * 0.37f), y(-0.75f), x(-0.38f + i * 0.37f), y(-0.1f))
                    canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                }
                rect.set(x(-1f), y(-0.05f), x(-0.55f), y(0.45f)); canvas.drawRoundRect(rect, s * 0.14f, s * 0.14f, paint)
            }
            Icon.HELMET -> {
                fillMode()
                rect.set(x(-0.9f), y(-0.9f), x(0.9f), y(0.9f))
                canvas.drawArc(rect, 180f, 180f, true, paint)
                canvas.drawRect(x(-1f), y(0f), x(1f), y(0.2f), paint)
                canvas.drawRect(x(-0.82f), y(0.2f), x(-0.6f), y(0.75f), paint)
                canvas.drawRect(x(0.6f), y(0.2f), x(0.82f), y(0.75f), paint)
                paint.color = Color.argb(90, 0, 0, 0)
                canvas.drawRect(x(-0.7f), y(-0.35f), x(0.7f), y(-0.2f), paint)
            }
            Icon.VEST -> {
                fillMode()
                path.reset()
                path.moveTo(x(-0.8f), y(-0.85f)); path.lineTo(x(-0.35f), y(-0.85f))
                path.quadTo(x(0f), y(-0.3f), x(0.35f), y(-0.85f)); path.lineTo(x(0.8f), y(-0.85f))
                path.lineTo(x(0.92f), y(0.2f)); path.lineTo(x(0.62f), y(0.92f)); path.lineTo(x(-0.62f), y(0.92f)); path.lineTo(x(-0.92f), y(0.2f))
                path.close()
                canvas.drawPath(path, paint)
                paint.color = Color.argb(90, 0, 0, 0)
                canvas.drawRect(x(-0.04f), y(-0.2f), x(0.04f), y(0.9f), paint)
            }
            Icon.SKIN -> {
                strokeMode(0.16f)
                canvas.drawCircle(cx, cy, s * 0.78f, paint)
                fillMode()
                canvas.drawCircle(x(-0.32f), y(-0.12f), s * 0.14f, paint)
                canvas.drawCircle(x(0.32f), y(-0.12f), s * 0.14f, paint)
                strokeMode(0.14f)
                path.reset()
                path.moveTo(x(-0.3f), y(0.36f)); path.quadTo(x(0f), y(0.62f), x(0.3f), y(0.36f))
                canvas.drawPath(path, paint)
            }
            Icon.HAIR -> {
                fillMode()
                path.reset()
                path.moveTo(x(-0.88f), y(0.5f))
                path.cubicTo(x(-0.95f), y(-1f), x(0.95f), y(-1f), x(0.88f), y(0.5f))
                path.cubicTo(x(0.5f), y(0.05f), x(0.3f), y(0.45f), x(0f), y(0.1f))
                path.cubicTo(x(-0.3f), y(0.45f), x(-0.5f), y(0.05f), x(-0.88f), y(0.5f))
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.FACE -> {
                fillMode()
                rect.set(x(-0.8f), y(-0.85f), x(0.8f), y(0.8f))
                canvas.drawRoundRect(rect, s * 0.32f, s * 0.32f, paint)
                paint.color = Color.argb(120, 0, 0, 0)
                rect.set(x(-0.5f), y(-0.4f), x(-0.16f), y(-0.06f)); canvas.drawRoundRect(rect, s * 0.07f, s * 0.07f, paint)
                rect.set(x(0.16f), y(-0.4f), x(0.5f), y(-0.06f)); canvas.drawRoundRect(rect, s * 0.07f, s * 0.07f, paint)
                rect.set(x(-0.42f), y(0.24f), x(0.42f), y(0.42f)); canvas.drawRoundRect(rect, s * 0.09f, s * 0.09f, paint)
            }
            Icon.SHIRT -> {
                fillMode()
                path.reset()
                path.moveTo(x(-0.34f), y(-0.92f)); path.lineTo(x(0.34f), y(-0.92f))
                path.lineTo(x(0.92f), y(-0.5f)); path.lineTo(x(0.62f), y(-0.22f))
                path.lineTo(x(0.62f), y(0.92f)); path.lineTo(x(-0.62f), y(0.92f))
                path.lineTo(x(-0.62f), y(-0.22f)); path.lineTo(x(-0.92f), y(-0.5f))
                path.close()
                canvas.drawPath(path, paint)
                paint.color = Color.argb(110, 0, 0, 0)
                path.reset()
                path.moveTo(x(-0.34f), y(-0.92f)); path.lineTo(x(0f), y(-0.4f)); path.lineTo(x(0.34f), y(-0.92f))
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.PANTS -> {
                fillMode()
                rect.set(x(-0.82f), y(-0.95f), x(0.82f), y(-0.55f))
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                rect.set(x(-0.82f), y(-0.5f), x(-0.12f), y(0.95f))
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                rect.set(x(0.12f), y(-0.5f), x(0.82f), y(0.95f))
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
            }
            Icon.ACCESSORY -> {
                strokeMode(0.2f)
                rect.set(x(-0.95f), y(-0.42f), x(-0.1f), y(0.3f))
                canvas.drawRoundRect(rect, s * 0.18f, s * 0.18f, paint)
                rect.set(x(0.1f), y(-0.42f), x(0.95f), y(0.3f))
                canvas.drawRoundRect(rect, s * 0.18f, s * 0.18f, paint)
                canvas.drawLine(x(-0.1f), y(-0.12f), x(0.1f), y(-0.12f), paint)
                canvas.drawLine(x(-0.95f), y(-0.5f), x(-0.95f), y(-0.82f), paint)
                canvas.drawLine(x(0.95f), y(-0.5f), x(0.95f), y(-0.82f), paint)
            }
        }
        paint.style = Paint.Style.FILL
    }
}

/** View simples que mostra um [Icon]. */
class IconView(context: Context, var icon: Icon, var color: Int = Color.WHITE) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(icon: Icon, color: Int) {
        this.icon = icon
        this.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 2f * 0.92f
        Icons.draw(canvas, icon, width / 2f, height / 2f, s, color, paint)
    }
}
