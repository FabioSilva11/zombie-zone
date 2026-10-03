package com.fabiosilva.ecosdeeldra.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.fabiosilva.ecosdeeldra.data.Items

/** Ícones dos itens desenhados em Canvas (sem imagens): usados no HUD, na mochila e no arsenal. */
object ItemIcons {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    /** Desenha o item [id] centrado em (cx, cy) num quadrado de lado [size]. */
    fun draw(canvas: Canvas, id: Int, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
        val s = size / 32f
        val p = paint
        p.style = Paint.Style.FILL
        p.alpha = alpha
        fun box(l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 1.5f) {
            p.color = color
            p.alpha = alpha
            rect.set(cx + l * s, cy + t * s, cx + r * s, cy + b * s)
            canvas.drawRoundRect(rect, radius * s, radius * s, p)
        }
        val metal = Color.rgb(70, 76, 88)
        val dark = Color.rgb(34, 36, 42)
        val wood = Color.rgb(150, 98, 52)
        val accent = Items.get(id)?.color ?: Color.WHITE
        when (id) {
            Items.RIFLE -> {
                box(-14f, -3f, 10f, 3f, metal)
                box(8f, -2f, 15f, 0f, dark)
                box(-15f, -2f, -9f, 4f, wood)
                box(-3f, 2f, 1f, 10f, dark)
                box(3f, 2f, 6f, 8f, dark)
                box(-4f, -6f, 3f, -3f, dark)
                box(4f, -4f, 7f, -2f, accent)
            }
            Items.PISTOL -> {
                box(-9f, -6f, 9f, 0f, metal)
                box(-8f, 0f, -2f, 10f, dark)
                box(6f, -7f, 8f, -5f, accent)
            }
            Items.SHOTGUN -> {
                box(-15f, -4f, 15f, -1f, dark)
                box(-15f, -1f, 15f, 1f, dark)
                box(2f, 1f, 9f, 5f, wood)
                box(-16f, -3f, -6f, 5f, wood)
                box(-6f, -3f, 1f, 3f, metal)
                box(13f, -6f, 15f, -4f, accent)
            }
            Items.SMG -> {
                box(-9f, -5f, 7f, 2f, metal)
                box(6f, -3f, 13f, -1f, dark)
                box(-2f, 2f, 2f, 13f, dark)
                box(-8f, 2f, -5f, 8f, dark)
                box(-15f, -3f, -9f, -1f, dark)
                box(-4f, -8f, 2f, -5f, accent)
            }
            Items.SNIPER -> {
                box(-12f, -3f, 6f, 2f, metal)
                box(5f, -2f, 16f, 0f, dark)
                box(-7f, -9f, 3f, -5f, dark)
                box(3f, -8f, 4f, -6f, Color.rgb(100, 220, 255))
                box(-16f, -2f, -10f, 5f, wood)
                box(-6f, 2f, -3f, 8f, dark)
            }
            Items.LMG -> {
                box(-12f, -5f, 8f, 3f, Color.rgb(84, 98, 58))
                box(7f, -3f, 16f, 0f, dark)
                box(-6f, 3f, 2f, 10f, Color.rgb(66, 76, 44))
                box(-15f, -3f, -10f, 3f, dark)
                box(12f, 0f, 13f, 9f, dark)
                box(-3f, -8f, 1f, -5f, accent)
            }
            Items.FLAMER -> {
                box(-14f, -5f, -4f, 7f, Color.rgb(200, 50, 40), 4f)
                box(-4f, -3f, 13f, 0f, dark)
                box(-2f, 0f, 1f, 8f, dark)
                p.color = Color.rgb(255, 170, 60)
                p.alpha = alpha
                canvas.drawCircle(cx + 15f * s, cy - 1.5f * s, 2.6f * s, p)
            }
            Items.LAUNCHER -> {
                box(-15f, -5f, 12f, 2f, Color.rgb(84, 98, 58), 3f)
                box(11f, -6f, 14f, 3f, accent)
                box(-3f, 2f, 1f, 10f, dark)
                box(-6f, -9f, -3f, -5f, dark)
            }
            Items.MEDKIT -> {
                box(-12f, -9f, 12f, 11f, Color.rgb(240, 240, 240), 3f)
                box(-4f, -13f, 4f, -9f, Color.rgb(200, 200, 200))
                box(-2.5f, -6f, 2.5f, 8f, Color.rgb(220, 40, 40))
                box(-7f, -1.5f, 7f, 3.5f, Color.rgb(220, 40, 40))
            }
            Items.BANDAGE -> {
                p.color = Color.rgb(238, 220, 190)
                p.alpha = alpha
                canvas.drawCircle(cx, cy, 11f * s, p)
                p.color = Color.rgb(196, 176, 146)
                p.alpha = alpha
                canvas.drawCircle(cx, cy, 5f * s, p)
                box(4f, 6f, 15f, 11f, Color.rgb(238, 220, 190))
            }
            Items.ANTIDOTE -> {
                box(-7f, -4f, 7f, 13f, Color.rgb(110, 230, 90), 4f)
                box(-3f, -10f, 3f, -4f, Color.rgb(200, 230, 200))
                box(-4f, -13f, 4f, -10f, Color.rgb(120, 80, 50))
                box(-5f, 2f, 5f, 7f, Color.rgb(240, 255, 240))
            }
            Items.ADRENALINE -> {
                canvas.save()
                canvas.rotate(-40f, cx, cy)
                box(-12f, -3.5f, 6f, 3.5f, Color.rgb(70, 220, 255), 2f)
                box(6f, -1f, 15f, 1f, Color.rgb(200, 200, 210))
                box(-15f, -5f, -12f, 5f, Color.rgb(220, 220, 230))
                box(-8f, -3.5f, -5f, 3.5f, Color.rgb(255, 255, 255))
                canvas.restore()
            }
            Items.FIRE_AMMO -> {
                for (k in -1..1) {
                    val x = k * 8f
                    box(x - 3f, -4f, x + 3f, 12f, Color.rgb(196, 150, 60), 1f)
                    path.reset()
                    path.moveTo(cx + (x - 3f) * s, cy - 4f * s)
                    path.lineTo(cx + x * s, cy - 13f * s)
                    path.lineTo(cx + (x + 3f) * s, cy - 4f * s)
                    path.close()
                    p.color = Color.rgb(255, 120, 40)
                    p.alpha = alpha
                    canvas.drawPath(path, p)
                }
            }
            Items.ARMOR -> {
                path.reset()
                path.moveTo(cx - 12f * s, cy - 8f * s)
                path.lineTo(cx - 5f * s, cy - 13f * s)
                path.lineTo(cx - 3f * s, cy - 8f * s)
                path.lineTo(cx + 3f * s, cy - 8f * s)
                path.lineTo(cx + 5f * s, cy - 13f * s)
                path.lineTo(cx + 12f * s, cy - 8f * s)
                path.lineTo(cx + 11f * s, cy + 12f * s)
                path.lineTo(cx - 11f * s, cy + 12f * s)
                path.close()
                p.color = Color.rgb(90, 110, 150)
                p.alpha = alpha
                canvas.drawPath(path, p)
                box(-8f, -1f, 8f, 3f, Color.rgb(170, 186, 220))
                box(-8f, 6f, 8f, 9f, Color.rgb(170, 186, 220))
            }
            Items.GRENADE -> {
                p.color = Color.rgb(120, 140, 70)
                p.alpha = alpha
                canvas.drawCircle(cx, cy + 3f * s, 10f * s, p)
                box(-4f, -11f, 4f, -6f, Color.rgb(150, 150, 150))
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * s
                p.color = Color.rgb(200, 200, 200)
                p.alpha = alpha
                canvas.drawCircle(cx + 7f * s, cy - 10f * s, 3.5f * s, p)
                p.style = Paint.Style.FILL
                box(-8f, 1f, 8f, 3f, Color.rgb(90, 106, 52))
            }
            Items.MOLOTOV -> {
                box(-7f, -2f, 7f, 14f, Color.rgb(90, 130, 70), 4f)
                box(-3f, -9f, 3f, -2f, Color.rgb(110, 150, 90))
                box(-2f, -12f, 2f, -9f, Color.rgb(230, 220, 200))
                p.color = Color.rgb(255, 150, 40)
                p.alpha = alpha
                canvas.drawCircle(cx, cy - 15f * s, 3.5f * s, p)
                p.color = Color.rgb(255, 230, 120)
                p.alpha = alpha
                canvas.drawCircle(cx, cy - 14f * s, 1.8f * s, p)
            }
            else -> Unit
        }
        p.alpha = 255
    }
}

/** View simples com o ícone de um item (para listas do arsenal). */
class ItemIconView(context: Context, var itemId: Int) : View(context) {
    override fun onDraw(canvas: Canvas) {
        ItemIcons.draw(canvas, itemId, width / 2f, height / 2f, minOf(width, height) * 0.9f)
    }
}
