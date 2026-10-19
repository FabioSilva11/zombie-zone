package com.fabiosilva.ecosdeeldra.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.OverScroller
import android.widget.TextView
import com.fabiosilva.ecosdeeldra.MainActivity
import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.StageTree
import com.fabiosilva.ecosdeeldra.data.ZombieKinds
import com.fabiosilva.ecosdeeldra.tutorial.ObjectiveText
import kotlin.math.abs
import kotlin.math.max

/** Cores exatas do CSS do mapa HTML ("Árvore de zonas"). */
private object H {
    const val BG = 0xFF0B1220.toInt()
    const val LINE = 0xFF2D3D55.toInt()
    const val LINE_SOFT = 0xFF2B394F.toInt()
    const val TAG_BG = 0xEE111B2C.toInt()
    const val TAG_LINE = 0xFF34445C.toInt()
    const val LABEL_SOFT = 0xFFAEBBD0.toInt()
    const val LIME = 0xFFB6FF48.toInt()
    const val LIME_DARK = 0xFF92D85A.toInt()
    const val GOLD = 0xFFFFC857.toInt()
    const val CYAN = 0xFF4CE8EB.toInt()
    const val MUTED = 0xFF8E9CB4.toInt()
    const val TEXT = 0xFFF3F7FF.toInt()
    const val TEXT_SOFT = 0xFFBECBDD.toInt()
    const val DARK = 0xFF18220F.toInt()
    const val DIALOG = 0xFF121D30.toInt()
    const val DIALOG_LINE = 0xFF3A4D68.toInt()
    const val DIALOG_TEXT = 0xFFA9B7CC.toInt()
    const val SECONDARY = 0xFF26344A.toInt()
    const val SECONDARY_TEXT = 0xFFDBE4F3.toInt()
    const val SHADOW_SOLID = 0xFF0A1220.toInt()
    const val NODE_LOCKED_TOP = 0xFF263448.toInt()
    const val NODE_LOCKED_BOT = 0xFF1A2537.toInt()
    const val NODE_LOCKED_LINE = 0xFF3C4D65.toInt()
    const val NODE_DONE_TOP = 0xFF416E36.toInt()
    const val NODE_DONE_BOT = 0xFF203C27.toInt()
    const val NODE_DONE_LINE = 0xFFA9FB66.toInt()
    const val NODE_AVAIL_TOP = 0xFF243D30.toInt()
    const val NODE_AVAIL_BOT = 0xFF14241F.toInt()
    const val LABEL = 0xFFD8E2EF.toInt()
    const val LABEL_LOCKED = 0xFF7F8FA6.toInt()
    const val LABEL_AVAIL = 0xFFD8F6BD.toInt()
    const val LABEL_DONE = 0xFFA8D98E.toInt()
    const val STATE_DONE = 0xFFA8F47A.toInt()
    const val STATE_LOCKED = 0xFF8494AA.toInt()
    const val ROOT_LINE = 0xFFB6FF48.toInt()
}

private fun lerpColor(a: Int, b: Int, t: Float): Int {
    fun mix(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return Color.argb(mix(Color.alpha(a), Color.alpha(b)), mix(Color.red(a), Color.red(b)), mix(Color.green(a), Color.green(b)), mix(Color.blue(a), Color.blue(b)))
}

/** Estilo de uma ligação entre nós (cores e espessura do HTML: padrão, aberta, concluída, selecionada). */
private class EdgeStyle(val color: Int, val width: Float, val glow: Int, val glowDp: Float)

private class EdgeNow(var color: Int, var width: Float, var glow: Int, var glowDp: Float)

private val EDGE_DEFAULT = EdgeStyle(Color.argb(148, 0x53, 0x66, 0x7D), 2.2f, 0, 0f)
private val EDGE_OPEN = EdgeStyle(Color.argb(230, 0x91, 0xBD, 0x81), 2.5f, Color.argb(0x20, 0x8D, 0xF6, 0x5B), 2f)
private val EDGE_DONE = EdgeStyle(Color.rgb(0xA8, 0xFF, 0x52), 3f, Color.argb(0x60, 0xA8, 0xFF, 0x52), 4f)
private val EDGE_SELECTED = EdgeStyle(Color.rgb(0xD7, 0xFF, 0xD0), 3.2f, Color.argb(0x66, 0xA8, 0xFF, 0x52), 5f)

/**
 * Mapa de zonas portado do HTML: fundo com brilho e listras, etiquetas fixas, árvore que rola nos dois
 * eixos e nós com estados disponível, bloqueado e concluído. Transições de cor, espessura e elevação ao toque
 * seguem o CSS (0,18 s a 0,25 s).
 */
@SuppressLint("ViewConstructor")
class TreeMapView(private val app: MainActivity, private val onTap: (Int) -> Unit) : View(app) {
    private val dp = resources.displayMetrics.density
    /** Escala da interface: acompanha o menor lado da tela. */
    private val ui = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / dp / 360f).coerceIn(0.85f, 1.3f)
    /** Zoom da árvore: menor que 1 afasta os nós para caber vários de uma vez. */
    private var zoom = 0.75f
    private fun u(v: Float) = v * dp * ui
    private fun d(v: Float) = v * dp * ui * zoom
    private val progress = app.progress

    /** Abaixo de 850 dp o HTML encolhe os nós e as margens (breakpoint de celular). */
    private val compact = resources.displayMetrics.widthPixels / dp <= 850f
    private val s = if (compact) 0.88f else 1f
    private val vpTopDp = 0f
    private val vpBottomDp = 0f

    /** Nó selecionado (anel claro e ligações claras). */
    var selected = StageTree.ROOT
        set(value) {
            field = value
            invalidate()
        }

    private class LNode(val id: Int, val level: Int) {
        val children = ArrayList<LNode>(2)
        var x = 0f
        var y = 0f
    }

    private val nodes = ArrayList<LNode>()
    private val byId = HashMap<Int, LNode>()
    private var sceneWDp = 0f
    private var sceneHDp = 0f
    private var maxLevel = 0
    private var scrollX = 0f
    private var scrollY = 0f
    private var focused = false
    private var hovered = -1
    private var liftId = -1
    private var liftNow = 0f
    private val edgeNow = HashMap<Int, EdgeNow>()
    private var bg: Bitmap? = null
    private val scroller = OverScroller(app)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val titleCache = HashMap<Int, Pair<Int, StaticLayout>>()

    init {
        setWillNotDraw(false)
        buildLayout()
    }

    /** Monta a árvore visível: raiz, 3 níveis abertos e, abaixo deles, só os filhos de nós vencidos (como o HTML). */
    private fun buildLayout() {
        fun build(id: Int): LNode {
            val n = LNode(id, StageTree.depth(id) - 1)
            for (c in StageTree.children(id)) if (visible(c)) n.children.add(build(c))
            return n
        }
        val root = build(StageTree.ROOT)
        var leaf = 0
        fun assign(n: LNode): Float {
            if (n.children.isEmpty()) {
                n.y = 90f + leaf * 150f
                leaf++
            } else {
                val ys = n.children.map { assign(it) }
                n.y = (ys.first() + ys.last()) / 2f
            }
            n.x = 150f + n.level * 215f
            nodes.add(n)
            byId[n.id] = n
            maxLevel = max(maxLevel, n.level)
            return n.y
        }
        assign(root)
        sceneWDp = max(880f, 300f + maxLevel * 215f)
        sceneHDp = max(640f, 150f + leaf * 150f)
    }

    private fun visible(id: Int): Boolean =
        StageTree.depth(id) <= 4 || progress.isStageCompleted(StageTree.parent(id) ?: id)

    private fun completed(id: Int) = progress.isStageCompleted(id)
    private fun available(id: Int) = StageTree.isAvailable(progress, id) && !completed(id)

    private fun edgeFor(parent: LNode, child: LNode): EdgeStyle = when {
        parent.id == selected || child.id == selected -> EDGE_SELECTED
        completed(parent.id) && completed(child.id) -> EDGE_DONE
        completed(parent.id) -> EDGE_OPEN
        else -> EDGE_DEFAULT
    }

    private fun vpTop() = u(vpTopDp)
    private fun vpBottom() = u(vpBottomDp)
    private fun maxX() = max(0f, d(sceneWDp) - width)
    private fun maxY() = max(0f, d(sceneHDp) - (height - vpTop() - vpBottom()))

    /** Centraliza o nó como o HTML: 42% da largura e 52% da altura visível. */
    fun focusOn(id: Int) {
        val n = byId[id] ?: return
        val vpH = height - vpTop() - vpBottom()
        scrollX = (d(n.x) - width * 0.42f).coerceIn(0f, maxX())
        scrollY = (d(n.y) - vpH * 0.52f).coerceIn(0f, maxY())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        zoom = minOf(1f, (h - u(vpTopDp) - u(vpBottomDp)) / u(520f), w / u(700f)).coerceIn(0.5f, 0.8f)
        bg?.recycle()
        bg = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawBackground(Canvas(it), w.toFloat(), h.toFloat()) } else null
        if (!focused && w > 0 && h > 0) {
            focused = true
            focusOn(selected)
        }
    }

    /** Fundo estático (uma vez por tamanho): gradiente 155deg, brilho, listras 135deg, vinheta e borda. */
    private fun drawBackground(c: Canvas, w: Float, h: Float) {
        val r = u(22f)
        val frame = Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW) }
        c.save()
        c.clipPath(frame)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(w * 0.3f, 0f, w * 0.7f, h, intArrayOf(0xFF172438.toInt(), 0xFF111B2C.toInt(), 0xFF101827.toInt()), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.22f, h * 0.5f, max(w, h) * 0.58f, Color.argb(0x32, 0x31, 0x45, 0x5D), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w / 2f, h / 2f, max(w, h) * 0.75f, intArrayOf(0x00050A13, 0x00050A13, 0xAA050A13.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // Listras 135deg (tile de 38px): faixas de 25% em #ffffff04 e de 25% em #ffffff03.
        val period = u(38f) * 1.4142f
        val band = period * 0.25f
        var u = -h
        while (u < w + h) {
            stripe(c, u, band, Color.argb(4, 255, 255, 255), h)
            stripe(c, u + period * 0.5f, band, Color.argb(3, 255, 255, 255), h)
            u += period
        }
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = u(1f)
        paint.color = H.LINE
        c.drawRoundRect(RectF(u(0.5f), u(0.5f), w - u(0.5f), h - u(0.5f)), r, r, paint)
        paint.style = Paint.Style.FILL
    }

    /** Faixa diagonal (x + y entre u e u + largura). */
    private fun stripe(c: Canvas, u: Float, w: Float, color: Int, h: Float) {
        paint.color = color
        path.reset()
        path.moveTo(u, 0f)
        path.lineTo(u + w, 0f)
        path.lineTo(u + w - h, h)
        path.lineTo(u - h, h)
        path.close()
        c.drawPath(path, paint)
    }

    private val gestures = GestureDetector(app, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            hovered = nodeAt(e.x, e.y)?.id ?: -1
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            hovered = -1
            scrollX = (scrollX + dx).coerceIn(0f, maxX())
            scrollY = (scrollY + dy).coerceIn(0f, maxY())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(scrollX.toInt(), scrollY.toInt(), -vx.toInt(), -vy.toInt(), 0, maxX().toInt(), 0, maxY().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val n = nodeAt(e.x, e.y) ?: return false
            hovered = -1
            onTap(n.id)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            hovered = -1
            invalidate()
        }
        return handled || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, maxX())
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxY())
            postInvalidateOnAnimation()
        }
    }

    /** Nó sob o toque (coordenadas da view), considerando rolagem e a área do mapa. */
    private fun nodeAt(ex: Float, ey: Float): LNode? {
        val sx = ex + scrollX
        val sy = ey - vpTop() + scrollY
        val half = d(34f) * s
        for (i in nodes.indices.reversed()) {
            val n = nodes[i]
            if (abs(sx - d(n.x)) < half && abs(sy - d(n.y)) < half) return n
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        bg?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val top = vpTop()
        val bottom = height - vpBottom()
        val animating = stepTransitions()

        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        canvas.translate(-scrollX, top - scrollY)
        drawGuides(canvas)
        drawEdges(canvas)
        drawNodes(canvas)
        canvas.restore()
        drawLegend(canvas)

        if (animating || !scroller.isFinished) postInvalidateOnAnimation()
    }

    /** Avança as transições de cor, espessura e elevação. Devolve true enquanto ainda há animação. */
    private fun stepTransitions(): Boolean {
        var moving = false
        for (child in nodes) {
            val parent = child.id.let { StageTree.parent(it) }?.let { byId[it] } ?: continue
            val target = edgeFor(parent, child)
            val now = edgeNow.getOrPut(child.id) { EdgeNow(target.color, target.width, target.glow, target.glowDp) }
            now.color = lerpColor(now.color, target.color, 0.2f)
            now.width += (target.width - now.width) * 0.2f
            now.glowDp += (target.glowDp - now.glowDp) * 0.2f
            now.glow = target.glow
            if (abs(now.width - target.width) > 0.01f || now.color != target.color) moving = true
        }
        val liftTarget = if (hovered == liftId && hovered != -1) -2f else 0f
        if (hovered != -1) liftId = hovered
        liftNow += (liftTarget - liftNow) * 0.3f
        if (abs(liftNow - liftTarget) > 0.02f) moving = true
        return moving
    }

    private fun drawGuides(c: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d(1f)
        paint.color = Color.argb(8, 255, 255, 255)
        paint.pathEffect = DashPathEffect(floatArrayOf(d(3f), d(9f)), 0f)
        for (level in 0..maxLevel) {
            val x = d(100f + level * 215f)
            c.drawLine(x, d(34f), x, d(sceneHDp - 28f), paint)
        }
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
    }

    private fun drawEdges(c: Canvas) {
        for (child in nodes) {
            val parent = StageTree.parent(child.id)?.let { byId[it] } ?: continue
            val target = edgeFor(parent, child)
            val now = edgeNow[child.id] ?: continue
            val x1 = d(parent.x + 31f * s)
            val y1 = d(parent.y)
            val x2 = d(child.x - 26f * s)
            val y2 = d(child.y)
            path.reset()
            path.moveTo(x1, y1)
            path.cubicTo(x1 + d(80f), y1, x2 - d(80f), y2, x2, y2)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = d(now.width)
            paint.color = now.color
            if (target.glowDp > 0f && now.glowDp > 0.1f) paint.setShadowLayer(d(now.glowDp), 0f, 0f, target.glow) else paint.clearShadowLayer()
            c.drawPath(path, paint)
            paint.clearShadowLayer()
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawNodes(c: Canvas) {
        for (n in nodes) if (n.id != selected) drawNode(c, n)
        nodes.firstOrNull { it.id == selected }?.let { drawNode(c, it) }
    }

    private fun drawNode(c: Canvas, n: LNode) {
        val isDone = completed(n.id)
        val isAvail = available(n.id)
        val isRoot = n.id == StageTree.ROOT
        val lift = if (n.id == liftId) liftNow * dp else 0f
        val cx = d(n.x)
        val cy = d(n.y) + lift
        val core = d(50f) * s
        val alpha = if (!isDone && !isAvail) 200 else 255
        val layer = if (alpha < 255) c.saveLayerAlpha(cx - d(40f), cy - d(40f), cx + d(40f), cy + d(40f), alpha) else -1

        if (isRoot) {
            drawRoot(c, cx, cy, n)
        } else {
            val r = d(17f) * s
            rect.set(cx - core / 2, cy - core / 2, cx + core / 2, cy + core / 2)
            // Sombra sólida (0 5px 0 #0a1220) e sombra difusa (0 9px 20px #0003) do estado base.
            paint.style = Paint.Style.FILL
            paint.color = H.SHADOW_SOLID
            rect.offset(0f, d(5f) * s)
            c.drawRoundRect(rect, r, r, paint)
            rect.offset(0f, -d(5f) * s)
            if (!isAvail) {
                paint.setShadowLayer(d(10f), 0f, d(9f), 0x33000000)
                paint.color = Color.BLACK
                c.drawRoundRect(rect, r, r, paint)
                paint.clearShadowLayer()
            }
            if (isAvail) {
                // Anel 3px #aaff4812 e brilho 20px #aaff481b.
                paint.setShadowLayer(d(20f), 0f, 0f, Color.argb(0x1B, 0xAA, 0xFF, 0x48))
                paint.color = Color.argb(0x12, 0xAA, 0xFF, 0x48)
                val ring = RectF(rect).apply { inset(-d(3f) * s, -d(3f) * s) }
                c.drawRoundRect(ring, r + d(3f) * s, r + d(3f) * s, paint)
                paint.clearShadowLayer()
            }
            val (top, bot, line) = when {
                isDone -> Triple(H.NODE_DONE_TOP, H.NODE_DONE_BOT, H.NODE_DONE_LINE)
                isAvail -> Triple(H.NODE_AVAIL_TOP, H.NODE_AVAIL_BOT, H.LIME_DARK)
                else -> Triple(H.NODE_LOCKED_TOP, H.NODE_LOCKED_BOT, H.NODE_LOCKED_LINE)
            }
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, top, bot, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(1f)
            paint.color = line
            c.drawRoundRect(RectF(rect).apply { inset(d(0.5f), d(0.5f)) }, r, r, paint)
            paint.style = Paint.Style.FILL
            drawGlyph(c, cx, cy, isDone, isAvail)
        }

        if (n.id == selected) {
            // Contorno 2px #f0ffd6 a 5px do nó.
            val rr = (if (isRoot) d(31f) * s else core / 2) + d(6f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(2f)
            paint.color = 0xFFF0FFD6.toInt()
            c.drawCircle(cx, cy, rr, paint)
            paint.style = Paint.Style.FILL
        }
        if (layer >= 0) c.restoreToCount(layer)
        drawLabel(c, n, cx, cy, isDone, isAvail)
    }

    /** Raiz: círculo com gradiente radial de paradas nítidas (7%, 12-23%, 28-56%, 61%) e anéis de brilho. */
    private fun drawRoot(c: Canvas, cx: Float, cy: Float, n: LNode) {
        val radius = d(31f) * s
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(0x17, 0xB6, 0xFF, 0x48)
        c.drawCircle(cx, cy, radius + d(6f), paint)
        paint.setShadowLayer(d(28f), 0f, 0f, Color.argb(0x40, 0xAA, 0xFF, 0x48))
        paint.color = Color.BLACK
        c.drawCircle(cx, cy, radius, paint)
        paint.clearShadowLayer()
        val stops = floatArrayOf(0f, 0.07f, 0.12f, 0.23f, 0.28f, 0.56f, 0.61f, 1f)
        val colors = intArrayOf(0xFFCAFF87.toInt(), 0xFFCAFF87.toInt(), 0xFF70BF2F.toInt(), 0xFF70BF2F.toInt(), 0xFF1B3527.toInt(), 0xFF1B3527.toInt(), 0xFF111C2B.toInt(), 0xFF111C2B.toInt())
        paint.shader = RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d(2f)
        paint.color = H.ROOT_LINE
        c.drawCircle(cx, cy, radius - d(1f), paint)
        paint.strokeWidth = d(4f)
        paint.color = Color.argb(0x24, 0xB6, 0xFF, 0x48)
        c.drawCircle(cx, cy, radius - d(2f), paint)
        paint.style = Paint.Style.FILL
        drawText(c, "✦", cx, cy, d(25f), 0xFFEAFFD8.toInt(), Fonts.title)
    }

    private fun drawGlyph(c: Canvas, cx: Float, cy: Float, isDone: Boolean, isAvail: Boolean) {
        when {
            isDone -> drawText(c, "✓", cx, cy, d(19f) * s, 0xFFE6FFD2.toInt(), Fonts.title)
            isAvail -> drawText(c, "✦", cx, cy, d(19f) * s, H.LIME, Fonts.title)
            else -> {
                // Cadeado: arco 15×13 (borda 3px) e corpo 23×19 com cantos de 4px.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = d(3f) * s
                paint.color = 0xFFB9C6D6.toInt()
                rect.set(cx - d(7.5f) * s, cy - d(9f) * s, cx + d(7.5f) * s, cy + d(4f) * s)
                c.drawArc(rect, 180f, 180f, false, paint)
                paint.style = Paint.Style.FILL
                paint.color = 0xFFB9C6D6.toInt()
                rect.set(cx - d(11.5f) * s, cy - d(1f) * s, cx + d(11.5f) * s, cy + d(18f) * s)
                c.drawRoundRect(rect, d(4f) * s, d(4f) * s, paint)
            }
        }
    }

    private fun drawText(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, font: android.graphics.Typeface) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.typeface = font
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, paint)
    }

    /** Rótulo: título em negrito (largura 142 px, centro) e o estado em caixa-alta espaçado. */
    private fun drawLabel(c: Canvas, n: LNode, cx: Float, cy: Float, isDone: Boolean, isAvail: Boolean) {
        val labelW = u(if (compact) 104f else 128f)
        val color = when {
            isDone -> H.LABEL_DONE
            isAvail -> H.LABEL_AVAIL
            !isDone && !isAvail -> H.LABEL_LOCKED
            else -> H.LABEL
        }
        // Quebra de linha só muda com o estado (cor): guarda o layout para não refazê-lo a cada quadro.
        val cached = titleCache[n.id]
        val layout = if (cached != null && cached.first == color) cached.second else {
            val title = Campaign.stage(n.id).title
            textPaint.color = color
            textPaint.textSize = u(if (compact) 8f else 10f)
            textPaint.typeface = Fonts.bold
            textPaint.setShadowLayer(d(3.5f), 0f, d(2f), 0x99000000.toInt())
            StaticLayout.Builder.obtain(title, 0, title.length, textPaint, labelW.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.25f)
                .setIncludePad(false)
                .build()
                .also { titleCache[n.id] = color to it }
        }
        c.save()
        c.translate(cx - labelW / 2f, cy + d(39f) * s)
        layout.draw(c)
        c.restore()
        val stateText = when {
            isDone -> "CONCLUÍDA"
            isAvail -> "DISPONÍVEL"
            else -> "BLOQUEADA"
        }
        val stateColor = when {
            isDone -> H.STATE_DONE
            isAvail -> H.LIME
            else -> H.STATE_LOCKED
        }
        textPaint.setShadowLayer(d(3.5f), 0f, d(2f), 0x99000000.toInt())
        textPaint.color = stateColor
        textPaint.textSize = u(if (compact) 7f else 8f)
        textPaint.typeface = Fonts.title
        textPaint.letterSpacing = 0.12f
        val stateY = cy + d(39f) * s + layout.height + d(4f) + textPaint.textSize
        textPaint.textAlign = Paint.Align.CENTER
        c.drawText(stateText, cx, stateY, textPaint)
        textPaint.letterSpacing = 0f
        textPaint.clearShadowLayer()
    }

    /** Etiqueta "REGIÃO ATUAL" (esquerda) e "ÁRVORE DE ZONAS" (direita): fixas sobre o mapa. */
    private fun drawTagBox(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val r = u(12f)
        paint.style = Paint.Style.FILL
        paint.color = H.TAG_BG
        c.drawRoundRect(RectF(x, y, x + w, y + h), r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = u(1f)
        paint.color = H.TAG_LINE
        c.drawRoundRect(RectF(x + u(0.5f), y + u(0.5f), x + w - u(0.5f), y + h - u(0.5f)), r, r, paint)
        paint.style = Paint.Style.FILL
    }

    /** Legenda inferior: ponto lima com brilho e texto. */
    private fun drawLegend(c: Canvas) {
        val left = u(if (compact) 10f else 18f)
        val bottom = height - u(if (compact) 9f else 14f)
        paint.typeface = Fonts.title
        paint.textSize = u(7f)
        paint.letterSpacing = 0f
        paint.textAlign = Paint.Align.LEFT
        val a = "RAMOS DISPONÍVEIS"
        val b = " • "
        val cc = "CONCLUÍDA: VERDE"
        val textW = paint.measureText(a) + paint.measureText(b) + paint.measureText(cc)
        val padX = u(11f)
        val h = u(9f) * 2 + u(10f) + u(4f)
        val w = u(7f) + u(8f) + textW + padX * 2
        val y = bottom - h
        drawTagBox(c, left, y, w, h)
        paint.setShadowLayer(u(10f), 0f, 0f, H.LIME)
        paint.color = H.LIME
        c.drawCircle(left + padX + u(3.5f), y + h / 2f, u(3.5f), paint)
        paint.clearShadowLayer()
        var x = left + padX + u(7f) + u(8f)
        val baseline = y + h / 2f + u(3.5f)
        paint.color = 0xFFB9C6D8.toInt()
        c.drawText(a, x, baseline, paint)
        x += paint.measureText(a)
        paint.color = 0xFF8191A9.toInt()
        c.drawText(b, x, baseline, paint)
        x += paint.measureText(b)
        paint.color = 0xFFB9C6D8.toInt()
        c.drawText(cc, x, baseline, paint)
    }
}

/** Caixa que encaixa o painel no espaço disponível: escala tudo junto (texto, ícones, cartões). Sem rolagem. */
private class FitBox(context: Context, private val designW: Int) : FrameLayout(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        getChildAt(0)?.measure(
            MeasureSpec.makeMeasureSpec(designW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        val cw = child.measuredWidth.toFloat().coerceAtLeast(1f)
        val ch = child.measuredHeight.toFloat().coerceAtLeast(1f)
        val k = minOf(width / cw, height / ch, 1.25f)
        child.layout(0, 0, cw.toInt(), ch.toInt())
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = k
        child.scaleY = k
        child.translationX = (width - cw * k) / 2f
        child.translationY = (height - ch * k) / 2f
    }
}

/** Escolha de fase: árvore de zonas (como o mapa HTML) e painel de missão do nó selecionado. */
@SuppressLint("ViewConstructor")
class StagesScreen(private val app: MainActivity) : FrameLayout(app) {
    private var selected = app.progress.frontierStage()
    private val tree = TreeMapView(app) { onNode(it) }
    private val panelContent = Ui.column(app)
    private val dp = resources.displayMetrics.density

    init {
        setBackgroundColor(H.BG)
        Ui.blockTouches(this)
        val screenDp = resources.displayMetrics.widthPixels / dp
        val screenHDp = resources.displayMetrics.heightPixels / dp
        val asideDp = when {
            screenDp > 1150f -> 400
            screenDp > 850f -> 340
            else -> 0
        }
        val panel = FitBox(app, app.dpi(if (asideDp > 0) 340 else 360)).apply { addView(panelContent) }
        val column = Ui.column(app)
        column.addView(mapHeader())
        if (asideDp > 0) {
            val body = Ui.row(app, Gravity.TOP)
            body.addView(tree, Ui.lp(0, Ui.MATCH, 1f).apply { setMargins(app.dpi(14), 0, app.dpi(14), app.dpi(14)) })
            body.addView(panel, Ui.lp(app.dpi(asideDp), Ui.MATCH).apply { setMargins(0, 0, app.dpi(14), app.dpi(14)) })
            column.addView(body, Ui.lp(Ui.MATCH, 0, 1f))
        } else {
            // Celular: o mapa ocupa metade da altura e o painel encaixa no restante.
            val mapH = (screenHDp * 0.5f).coerceIn(340f, 600f)
            column.addView(tree, Ui.lp(Ui.MATCH, app.dpi(mapH.toInt())).apply { setMargins(app.dpi(8), 0, app.dpi(8), app.dpi(8)) })
            column.addView(panel, Ui.lp(Ui.MATCH, 0, 1f).apply { setMargins(app.dpi(8), 0, app.dpi(8), app.dpi(8)) })
        }
        addView(column, Ui.frame(Ui.MATCH, Ui.MATCH))
        select(selected, focus = true)
    }

    /** Cabeçalho do mapa: só título e voltar (sem estrelas nem diamantes). */
    private fun mapHeader(): View {
        val row = Ui.row(app)
        row.setPadding(app.dpi(16), app.dpi(8), app.dpi(16), app.dpi(4))
        row.addView(Ui.title(app, "MAPA DAS ZONAS", 20f), Ui.lp(0, Ui.WRAP, 1f))
        row.addView(Ui.iconButton(app, Icon.CLOSE, Palette.stone, Color.WHITE) { app.showHome() }, Ui.lp(app.dpi(40), app.dpi(40)))
        return row
    }

    private fun onNode(id: Int) {
        val p = app.progress
        if (!StageTree.isAvailable(p, id) && !p.isStageCompleted(id)) {
            showBlocked()
            return
        }
        select(id, focus = false)
    }

    private fun select(id: Int, focus: Boolean) {
        selected = id
        tree.selected = id
        if (focus) tree.focusOn(id)
        renderPanel(id)
    }

    // ------------------------------------------------------------------ painel de missão (CSS do HTML, compacto)

    private fun txt(text: CharSequence, sizeSp: Float, color: Int, font: android.graphics.Typeface = Fonts.body, letter: Float = 0f): TextView =
        TextView(app).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = font
            letterSpacing = letter
            includeFontPadding = false
        }

    private fun lpWrap(bottom: Int = 0) = Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = app.dpi(bottom) }

    private fun renderPanel(id: Int) {
        panelContent.removeAllViews()
        val p = app.progress
        val stage = Campaign.stage(id)
        val record = p.stages[id]
        val done = record?.completed == true
        val level = stage.depth - 1
        val diff = Campaign.difficulty(stage.depth, p)
        val c = panelContent

        c.addView(txt("CENTRAL DE MISSÕES · PROGRESSÃO", 9f, H.GOLD, Fonts.title, 0.12f), lpWrap(2))
        c.addView(txt("Árvore de zonas", 22f, H.TEXT, Fonts.title, -0.02f), lpWrap(8))

        val card = Ui.column(app).apply {
            background = PanelDrawable(app, 0xFF141F33.toInt(), H.LINE, 16f)
            setPadding(app.dpi(14), app.dpi(14), app.dpi(14), app.dpi(14))
        }
        // Zona e nível de ameaça.
        val top = Ui.row(app)
        top.addView(
            txt("ZONA · ${stage.zone.name.uppercase()}", 9f, H.LIME, Fonts.title, 0.05f).apply {
                setPadding(app.dpi(7), app.dpi(4), app.dpi(7), app.dpi(4))
                background = PanelDrawable(app, 0x10B6FF48, 0x33B6FF48, 6f)
            },
        )
        top.addView(View(app), Ui.lp(0, 0, 1f))
        val threat = when {
            level < 2 -> "AMEAÇA MODERADA"
            level < 5 -> "AMEAÇA ELEVADA"
            else -> "AMEAÇA EXTREMA"
        }
        top.addView(txt("● $threat", 8f, H.GOLD, Fonts.bold, 0.04f))
        card.addView(top, lpWrap(8))

        card.addView(txt(if (id == StageTree.ROOT) "Primeiro alarme" else "Fase ${stage.depth} · ${stage.title}", 20f, H.TEXT, Fonts.title, -0.02f), lpWrap(6))

        // Estrelas e melhor tempo.
        val stars = record?.stars ?: 0
        val best = record?.bestTime?.takeIf { it > 0 }?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "—"
        val rating = Ui.row(app)
        rating.addView(txt("MELHOR TEMPO ", 8f, H.MUTED, Fonts.body, 0.03f))
        rating.addView(txt(if (done) best else "—", 10f, H.TEXT, Fonts.bold))
        card.addView(rating, lpWrap(8))

        // Extensão, ninhos e horda (fórmulas do HTML).
        val stats = Ui.row(app)
        val distance = "%.1f".format(5.1 + level * 0.7).replace(".", ",")
        listOf(Triple("EXTENSÃO", distance, "km"), Triple("NINHOS", String.format("%02d", stage.crystalTotal), "alvos"), Triple("HORDA", String.format("%02d", diff.zombieCount), "infectados")).forEachIndexed { i, (label, value, unit) ->
            val cell = Ui.column(app).apply {
                background = PanelDrawable(app, 0xFF0B1321.toInt(), H.LINE_SOFT, 9f)
                setPadding(app.dpi(7), app.dpi(6), app.dpi(7), app.dpi(6))
            }
            cell.addView(txt(label, 7f, H.MUTED, Fonts.bold, 0.06f))
            val valueRow = Ui.row(app, Gravity.BOTTOM)
            valueRow.addView(txt(value, 13f, H.TEXT, Fonts.bold))
            valueRow.addView(txt(" $unit", 8f, H.CYAN, Fonts.bold).apply { setPadding(app.dpi(2), 0, 0, 0) })
            cell.addView(valueRow, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = app.dpi(2) })
            stats.addView(cell, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = if (i > 0) app.dpi(6) else 0 })
        }
        card.addView(stats, lpWrap(10))

        // Ameaças liberadas nesta profundidade (até 3 nomes; NOVO no nível em que aparecem).
        card.addView(txt("AMEAÇAS IDENTIFICADAS", 7f, 0xFF93A2BA.toInt(), Fonts.bold, 0.1f), lpWrap(4))
        val enemies = Ui.row(app)
        for (z in ZombieKinds.available(stage.depth).take(3)) {
            val chip = Ui.row(app).apply {
                background = PanelDrawable(app, 0xFF1D2A3D.toInt(), 0xFF2D3D53.toInt(), 7f)
                setPadding(app.dpi(6), app.dpi(4), app.dpi(6), app.dpi(4))
            }
            chip.addView(View(app).apply { background = BlockDrawable(app, z.color, 2f, 0f, false) }, Ui.lp(app.dpi(6), app.dpi(6)))
            chip.addView(txt(z.name, 8f, H.TEXT, Fonts.bold).apply { setPadding(app.dpi(4), 0, 0, 0) })
            if (z.minStage == stage.depth) chip.addView(txt("NOVO", 7f, H.GOLD, Fonts.bold, 0.04f).apply { setPadding(app.dpi(4), 0, 0, 0) })
            enemies.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = app.dpi(5) })
        }
        card.addView(enemies, lpWrap(10))

        // Objetivo e metas (1 linha cada).
        val objectiveText = if (id == StageTree.ROOT) ObjectiveText.SENTENCE_CAPITALIZED + "." else "Investigue ${stage.title.lowercase()} e encontre a saída segura."
        card.addView(txt("Objetivo: $objectiveText", 9f, 0xFFAEBBD0.toInt()).apply { setLineSpacing(0f, 1.3f) }, lpWrap(6))
        val target = "%02d:%02d".format(stage.targetSeconds / 60, stage.targetSeconds % 60)
        val goalsRow = Ui.row(app)
        listOf("✓" to "Concluir", "♥" to "Vida ≥ 50%", "◷" to "Até ").forEachIndexed { i, (icon, text) ->
            val cell = Ui.row(app)
            cell.addView(
                txt(icon, 8f, H.LIME, Fonts.body).apply { gravity = Gravity.CENTER; background = PanelDrawable(app, 0x00000000, 0xFF52617A.toInt(), 4f) },
                Ui.lp(app.dpi(12), app.dpi(12)).apply { marginEnd = app.dpi(4) },
            )
            cell.addView(txt(text, 8f, 0xFFBAC5D6.toInt()))
            goalsRow.addView(cell, Ui.lp(0, Ui.WRAP, 1f).apply { marginStart = if (i > 0) app.dpi(4) else 0 })
        }
        card.addView(goalsRow, lpWrap(6))

        c.addView(card, lpWrap(10))

        if (done) {
            c.addView(Ui.button(app, "MISSÃO CONCLUÍDA ✓", 0xFF344154.toInt(), 0xFF8D9BB0.toInt(), 14f) {}, Ui.lp(Ui.MATCH, app.dpi(46)))
        } else {
            c.addView(
                Ui.button(app, "INICIAR MISSÃO →", H.LIME, H.DARK, 14f) { app.showExpeditionPrep(ExpeditionKind.CAMPAIGN, id, back = { app.showStages() }) },
                Ui.lp(Ui.MATCH, app.dpi(46)),
            )
        }
    }

    /** "Ramo bloqueado": cartão do HTML (escuro, borda 3A4D68, marca lima com ✦). */
    private fun showBlocked() {
        val layer = FrameLayout(app).apply {
            setBackgroundColor(Color.argb(186, 5, 10, 19))
            isClickable = true
            isFocusable = true
        }
        val box = Ui.column(app).apply {
            background = PanelDrawable(app, H.DIALOG, H.DIALOG_LINE, 20f)
            setPadding(app.dpi(22), app.dpi(22), app.dpi(22), app.dpi(22))
            isClickable = true
        }
        val mark = txt("✦", 18f, H.DARK, Fonts.title).apply {
            gravity = Gravity.CENTER
            background = BlockDrawable(app, H.LIME, 13f, 0f, false)
        }
        box.addView(mark, Ui.lp(app.dpi(40), app.dpi(40)).apply { bottomMargin = app.dpi(12) })
        box.addView(Ui.title(app, "Ramo bloqueado", 20f).apply { setPadding(0, 0, 0, app.dpi(8)) })
        box.addView(txt("Conclua a zona anterior conectada a este ramo para descobrir esta região.", 12f, H.DIALOG_TEXT).apply {
            setLineSpacing(0f, 1.5f)
            setPadding(0, 0, 0, app.dpi(18))
        })
        box.addView(Ui.button(app, "ENTENDI", H.SECONDARY, H.SECONDARY_TEXT, 12f) { removeView(layer) }, Ui.lp(Ui.MATCH, app.dpi(42)))
        layer.addView(box, Ui.frame(app.dpi(380), Ui.WRAP, Gravity.CENTER).apply { setMargins(app.dpi(20), 0, app.dpi(20), 0) })
        layer.setOnClickListener { removeView(layer) }
        addView(layer, Ui.frame(Ui.MATCH, Ui.MATCH))
        Ui.popIn(box)
    }
}
