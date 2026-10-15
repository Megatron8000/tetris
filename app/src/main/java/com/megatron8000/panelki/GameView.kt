package com.megatron8000.panelki

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Entire game is drawn directly to Canvas to keep the image crisp and dependency-free.
 * Window blocks are strictly front-facing: no perspective, side walls or isometric angles.
 */
class GameView(context: Context) : View(context) {
    companion object {
        private const val COLS = 10
        private const val ROWS = 20
        private val SHAPES = arrayOf(
            intArrayOf(0x0F00, 0x2222, 0x00F0, 0x4444), // I
            intArrayOf(0x6600, 0x6600, 0x6600, 0x6600), // O
            intArrayOf(0x4E00, 0x4640, 0x0E40, 0x4C40), // T
            intArrayOf(0x6C00, 0x4620, 0x06C0, 0x8C40), // S
            intArrayOf(0xC600, 0x2640, 0x0C60, 0x4C80), // Z
            intArrayOf(0x8E00, 0x6440, 0x0E20, 0x44C0), // J
            intArrayOf(0x2E00, 0x4460, 0x0E80, 0xC440)  // L
        )
        private val WINDOW_GLASS = intArrayOf(
            Color.rgb(65, 132, 163),
            Color.rgb(255, 184, 84),
            Color.rgb(79, 145, 151),
            Color.rgb(75, 107, 143),
            Color.rgb(110, 123, 128),
            Color.rgb(91, 147, 177),
            Color.rgb(241, 135, 58)
        )
        private val WINDOW_LIGHTS = booleanArrayOf(false, true, false, false, false, false, true)
    }

    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        textAlign = Paint.Align.LEFT
    }
    private val handler = Handler(Looper.getMainLooper())
    private val board = Array(ROWS) { IntArray(COLS) { -1 } }
    private val stars = List(115) {
        val rng = Random(it * 79 + 712)
        Star(rng.nextFloat(), rng.nextFloat(), if (it % 7 == 0) 2.4f else 1.2f, it % 4 == 0)
    }
    private val clouds = listOf(
        Cloud(0.04f, 0.13f, 1.0f), Cloud(0.71f, 0.24f, 0.9f),
        Cloud(0.55f, 0.055f, 0.58f), Cloud(0.17f, 0.71f, 0.7f),
        Cloud(0.77f, 0.78f, 1.0f)
    )
    private val preferences = context.getSharedPreferences("panelki", Context.MODE_PRIVATE)
    private var score = 0
    private var best = preferences.getInt("best", 0)
    private var cleared = 0
    private var level = 1
    private var piece = 0
    private var rotation = 0
    private var pieceX = 3
    private var pieceY = 0
    private var nextPiece = Random.nextInt(SHAPES.size)
    private var bag = mutableListOf<Int>()
    private var started = false
    private var paused = false
    private var gameOver = false
    private var lastFrameTime = 0L
    private var scoreFlash = 0
    private var boardLeft = 0f
    private var boardTop = 0f
    private var cell = 0f
    private var uiScale = 1f
    private var boardWidth = 0f
    private var boardHeight = 0f

    private data class Star(val x: Float, val y: Float, val size: Float, val bright: Boolean)
    private data class Cloud(val x: Float, val y: Float, val scale: Float)

    private val ticker = object : Runnable {
        override fun run() {
            if (started && !paused && !gameOver) {
                val now = System.currentTimeMillis()
                if (lastFrameTime == 0L || now - lastFrameTime >= fallInterval()) {
                    moveDown()
                    lastFrameTime = now
                }
            }
            invalidate()
            handler.postDelayed(this, 32L)
        }
    }

    init {
        isFocusable = true
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        best = max(best, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeGameClock()
    }

    override fun onDetachedFromWindow() {
        pauseGameClock()
        super.onDetachedFromWindow()
    }

    fun resumeGameClock() {
        handler.removeCallbacks(ticker)
        lastFrameTime = System.currentTimeMillis()
        handler.post(ticker)
    }

    fun pauseGameClock() {
        handler.removeCallbacks(ticker)
    }

    private fun fallInterval(): Long = max(90L, 680L - (level - 1) * 48L)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        uiScale = min(w / 400f, h / 800f).coerceAtLeast(0.7f)
        cell = min(w / 12.4f, h * 0.60f / ROWS)
        boardWidth = cell * COLS
        boardHeight = cell * ROWS
        boardLeft = (w - boardWidth) / 2f
        boardTop = max(102f * uiScale, (h - boardHeight - 190f * uiScale) * 0.45f)

        drawNightSky(canvas, w, h)
        drawHeader(canvas, w)
        drawBoard(canvas)
        drawControls(canvas, w, h)

        if (!started) drawCenterMessage(canvas, "НОВАЯ ИГРА", "КОСНИСЬ ЭКРАНА")
        else if (paused) drawCenterMessage(canvas, "ПАУЗА", "КОСНИСЬ ДЛЯ ПРОДОЛЖЕНИЯ")
        else if (gameOver) drawCenterMessage(canvas, "ИГРА ОКОНЧЕНА", "КОСНИСЬ ДЛЯ РЕСТАРТА")
    }

    private fun drawNightSky(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(Color.rgb(8, 13, 31))
        fill(canvas, 0f, 0f, w, h * 0.42f, Color.rgb(10, 18, 43))
        fill(canvas, 0f, h * 0.42f, w, h, Color.rgb(12, 21, 43))
        stars.forEach { star ->
            val color = if (star.bright) Color.rgb(219, 224, 221) else Color.rgb(93, 123, 159)
            fill(canvas, star.x * w, star.y * h, star.x * w + star.size * uiScale,
                star.y * h + star.size * uiScale, color)
            if (star.bright) {
                fill(canvas, star.x * w - uiScale, star.y * h + star.size * uiScale * 0.4f,
                    star.x * w + star.size * uiScale * 1.7f, star.y * h + star.size * uiScale,
                    Color.rgb(133, 156, 186))
            }
        }
        clouds.forEach { drawCloud(canvas, it, w, h) }
        // A quiet far-off skyline silhouette sits below the playfield, never behind the windows.
        val base = h * 0.91f
        fill(canvas, 0f, base, w, h, Color.rgb(13, 24, 43))
        val blocks = floatArrayOf(0.02f, 0.13f, 0.29f, 0.43f, 0.64f, 0.79f, 0.9f)
        blocks.forEachIndexed { i, x ->
            val bw = w * (0.07f + (i % 3) * 0.018f)
            val bh = h * (0.035f + (i % 4) * 0.012f)
            fill(canvas, x * w, base - bh, x * w + bw, h, Color.rgb(17, 29, 48))
            for (row in 0..2) for (col in 0..1) {
                if ((i + row + col) % 3 == 0) {
                    fill(canvas, x * w + (5 + col * 10) * uiScale, base - bh + (6 + row * 10) * uiScale,
                        x * w + (8 + col * 10) * uiScale, base - bh + (9 + row * 10) * uiScale,
                        Color.rgb(110, 91, 75))
                }
            }
        }
    }

    private fun drawCloud(canvas: Canvas, cloud: Cloud, w: Float, h: Float) {
        val unit = 5f * uiScale * cloud.scale
        val x = cloud.x * w
        val y = cloud.y * h
        val shade = Color.rgb(31, 46, 75)
        val shade2 = Color.rgb(40, 57, 86)
        // Stepped pixel silhouette, deliberately flat and low contrast.
        fill(canvas, x + unit, y, x + unit * 5, y + unit, shade2)
        fill(canvas, x, y + unit, x + unit * 7, y + unit * 2, shade)
        fill(canvas, x - unit, y + unit * 2, x + unit * 8, y + unit * 3, shade)
        fill(canvas, x + unit * 2, y + unit * 3, x + unit * 6, y + unit * 4, Color.rgb(25, 39, 65))
        fill(canvas, x + unit * 7, y + unit * 2, x + unit * 9, y + unit * 3, shade)
    }

    private fun drawHeader(canvas: Canvas, w: Float) {
        text(canvas, "ПАНЕЛЬКИ", w * 0.075f, 42f * uiScale, 29f * uiScale, Color.rgb(229, 221, 196), true)
        text(canvas, "ТЕТРИС НОЧНОГО РАЙОНА", w * 0.08f, 62f * uiScale, 9f * uiScale, Color.rgb(119, 145, 171))
        text(canvas, "СЧЁТ  $score", w * 0.08f, 91f * uiScale, 14f * uiScale, Color.rgb(244, 189, 111), true)
        text(canvas, "РЕКОРД  $best", w * 0.08f, 111f * uiScale, 10f * uiScale, Color.rgb(164, 180, 191))
        text(canvas, "ЛИНИИ  $cleared", w * 0.47f, 91f * uiScale, 12f * uiScale, Color.rgb(196, 204, 205))
        text(canvas, "УР. $level", w * 0.47f, 109f * uiScale, 10f * uiScale, Color.rgb(132, 166, 179))

        val nx = w * 0.80f
        text(canvas, "ДАЛЬШЕ", nx - 2f * uiScale, 64f * uiScale, 9f * uiScale, Color.rgb(145, 166, 187))
        drawMiniPiece(canvas, nextPiece, nx, 76f * uiScale, 10f * uiScale)
        // Pause control
        fill(canvas, w - 43f * uiScale, 16f * uiScale, w - 15f * uiScale, 44f * uiScale, Color.rgb(30, 45, 68))
        fill(canvas, w - 36f * uiScale, 23f * uiScale, w - 32f * uiScale, 37f * uiScale, Color.rgb(235, 211, 164))
        fill(canvas, w - 26f * uiScale, 23f * uiScale, w - 22f * uiScale, 37f * uiScale, Color.rgb(235, 211, 164))
    }

    private fun drawBoard(canvas: Canvas) {
        fill(canvas, boardLeft - 4f * uiScale, boardTop - 4f * uiScale,
            boardLeft + boardWidth + 4f * uiScale, boardTop + boardHeight + 4f * uiScale,
            Color.rgb(76, 82, 91))
        fill(canvas, boardLeft, boardTop, boardLeft + boardWidth, boardTop + boardHeight, Color.rgb(12, 22, 40))
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                val left = boardLeft + x * cell
                val top = boardTop + y * cell
                if (board[y][x] >= 0) {
                    drawWindow(canvas, left, top, cell, board[y][x])
                } else {
                    fill(canvas, left + 1f * uiScale, top + 1f * uiScale,
                        left + cell - 1f * uiScale, top + cell - 1f * uiScale,
                        if ((x + y) % 2 == 0) Color.rgb(14, 26, 46) else Color.rgb(15, 27, 47))
                    // Very subtle cell seams make the playfield legible without competing with sprites.
                    fill(canvas, left, top + cell - uiScale, left + cell, top + cell,
                        Color.rgb(23, 36, 56))
                }
            }
        }
        if (started && !gameOver) {
            // Ghost landing position
            var ghostY = pieceY
            while (isValid(pieceX, ghostY + 1, piece, rotation)) ghostY++
            if (ghostY != pieceY) drawPiece(canvas, pieceX, ghostY, piece, rotation, ghost = true)
            drawPiece(canvas, pieceX, pieceY, piece, rotation, ghost = false)
        }
    }

    private fun drawWindow(canvas: Canvas, x: Float, y: Float, size: Float, kind: Int, ghost: Boolean = false) {
        val pad = max(1f, size * 0.08f)
        val l = x + pad
        val t = y + pad
        val r = x + size - pad
        val b = y + size - pad
        val frame = when (kind % 7) {
            4 -> Color.rgb(88, 68, 57)
            6 -> Color.rgb(108, 83, 62)
            else -> Color.rgb(177, 166, 143)
        }
        val glass = WINDOW_GLASS[kind % WINDOW_GLASS.size]
        val alpha = if (ghost) 95 else 255
        fill(canvas, l, t, r, b, withAlpha(Color.rgb(39, 39, 43), alpha))
        fill(canvas, l + pad * .35f, t + pad * .35f, r - pad * .35f, b - pad * .35f, withAlpha(frame, alpha))

        val insideL = l + pad * 0.72f
        val insideT = t + pad * 0.72f
        val insideR = r - pad * 0.72f
        val insideB = b - pad * 0.72f
        val gap = max(1f, size * 0.045f)
        val mid = (insideL + insideR) / 2f
        val paneColor = if (WINDOW_LIGHTS[kind % WINDOW_LIGHTS.size]) {
            if (kind % 2 == 0) Color.rgb(246, 177, 81) else Color.rgb(255, 203, 107)
        } else glass
        fill(canvas, insideL, insideT, mid - gap, insideB, withAlpha(paneColor, alpha))
        fill(canvas, mid + gap, insideT, insideR, insideB, withAlpha(if (kind % 3 == 0) Color.rgb(28, 49, 70) else paneColor, alpha))
        // Front-facing sash and reflected sky highlights: no side walls or perspective.
        fill(canvas, mid - gap * .55f, insideT, mid + gap * .55f, insideB, withAlpha(frame, alpha))
        fill(canvas, insideL, insideT + (insideB - insideT) * .62f, insideR, insideT + (insideB - insideT) * .62f + gap,
            withAlpha(frame, alpha))
        fill(canvas, insideL + gap, insideT + gap, insideL + gap * 2f, insideT + (insideB - insideT) * .35f,
            withAlpha(Color.rgb(204, 219, 214), if (ghost) 45 else 180))
        if (WINDOW_LIGHTS[kind % WINDOW_LIGHTS.size] && !ghost) {
            fill(canvas, insideL + gap * 2f, insideB - gap * 2f, insideR - gap, insideB - gap, Color.rgb(252, 215, 145))
        }
        // Chunky sill at the bottom is viewed head-on, as a horizontal strip.
        fill(canvas, l, b - pad * 0.4f, r, b, withAlpha(Color.rgb(207, 194, 168), alpha))
    }

    private fun drawPiece(canvas: Canvas, px: Int, py: Int, kind: Int, rot: Int, ghost: Boolean) {
        val mask = SHAPES[kind][rot]
        for (y in 0..3) for (x in 0..3) {
            if ((mask and (0x8000 shr (y * 4 + x))) != 0) {
                val gx = px + x
                val gy = py + y
                if (gx in 0 until COLS && gy in 0 until ROWS) {
                    drawWindow(canvas, boardLeft + gx * cell, boardTop + gy * cell, cell, kind, ghost)
                }
            }
        }
    }

    private fun drawMiniPiece(canvas: Canvas, kind: Int, x: Float, y: Float, size: Float) {
        val mask = SHAPES[kind][0]
        for (row in 0..3) for (col in 0..3) {
            if ((mask and (0x8000 shr (row * 4 + col))) != 0) {
                drawWindow(canvas, x + col * size, y + row * size, size, kind)
            }
        }
    }

    private fun drawControls(canvas: Canvas, w: Float, h: Float) {
        val labels = arrayOf("◀", "▼", "▶", "↻", "DROP")
        val centers = floatArrayOf(0.10f, 0.30f, 0.50f, 0.70f, 0.90f)
        val buttonW = w * 0.17f
        val buttonH = 48f * uiScale
        val centerY = h - 48f * uiScale
        labels.forEachIndexed { index, label ->
            val cx = centers[index] * w
            val left = cx - buttonW / 2f
            val top = centerY - buttonH / 2f
            fill(canvas, left + 2f * uiScale, top + 3f * uiScale, left + buttonW + 2f * uiScale, top + buttonH + 3f * uiScale,
                Color.rgb(5, 9, 22))
            fill(canvas, left, top, left + buttonW, top + buttonH, Color.rgb(35, 48, 68))
            fill(canvas, left, top, left + buttonW, top + 3f * uiScale, Color.rgb(97, 120, 139))
            val textSize = if (label == "DROP") 10f * uiScale else 21f * uiScale
            val tw = measure(label, textSize, true)
            text(canvas, label, cx - tw / 2f, centerY + textSize * 0.35f, textSize, Color.rgb(231, 219, 187), true)
        }
        text(canvas, "ДВИГАЙ • ПОВОРАЧИВАЙ • ПЕРЕЖИВИ НОЧЬ", w * 0.08f,
            h - 85f * uiScale, 8.5f * uiScale, Color.rgb(104, 127, 153))
    }

    private fun drawCenterMessage(canvas: Canvas, title: String, subtitle: String) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = boardTop + boardHeight / 2f
        val boxW = boardWidth * 0.92f
        val boxH = 96f * uiScale
        fill(canvas, cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f, Color.rgb(9, 17, 31))
        fill(canvas, cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy - boxH / 2f + 3f * uiScale, Color.rgb(207, 158, 97))
        val titleSize = 17f * uiScale
        text(canvas, title, cx - measure(title, titleSize, true) / 2f, cy - 4f * uiScale, titleSize, Color.rgb(245, 198, 127), true)
        val subSize = 8.2f * uiScale
        text(canvas, subtitle, cx - measure(subtitle, subSize, false) / 2f, cy + 21f * uiScale, subSize, Color.rgb(183, 197, 202))
    }

    private fun fill(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        if (r <= l || b <= t) return
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawRect(l, t, r, b, paint)
    }

    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
            else android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(value, x, baseline, paint)
    }

    private fun measure(value: String, size: Float, bold: Boolean): Float {
        paint.textSize = size
        paint.typeface = if (bold) android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
            else android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
        return paint.measureText(value)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun nextFromBag(): Int {
        if (bag.isEmpty()) {
            bag = (0 until SHAPES.size).toMutableList()
            bag.shuffle()
        }
        return bag.removeAt(bag.lastIndex)
    }

    private fun startNewGame() {
        for (row in board) row.fill(-1)
        score = 0
        cleared = 0
        level = 1
        gameOver = false
        paused = false
        started = true
        bag.clear()
        piece = nextFromBag()
        nextPiece = nextFromBag()
        rotation = 0
        pieceX = 3
        pieceY = -1
        spawnPiece()
        lastFrameTime = System.currentTimeMillis()
        invalidate()
    }

    private fun spawnPiece() {
        piece = nextPiece
        nextPiece = nextFromBag()
        rotation = 0
        pieceX = 3
        pieceY = -1
        if (!isValid(pieceX, pieceY, piece, rotation)) {
            gameOver = true
            started = true
            if (score > best) {
                best = score
                preferences.edit().putInt("best", best).apply()
            }
        }
    }

    private fun isValid(px: Int, py: Int, kind: Int, rot: Int): Boolean {
        val mask = SHAPES[kind][rot]
        for (y in 0..3) for (x in 0..3) {
            if ((mask and (0x8000 shr (y * 4 + x))) != 0) {
                val gx = px + x
                val gy = py + y
                if (gx < 0 || gx >= COLS || gy >= ROWS) return false
                if (gy >= 0 && board[gy][gx] >= 0) return false
            }
        }
        return true
    }

    private fun tryMove(dx: Int, dy: Int): Boolean {
        if (!started || paused || gameOver) return false
        if (isValid(pieceX + dx, pieceY + dy, piece, rotation)) {
            pieceX += dx
            pieceY += dy
            return true
        }
        return false
    }

    private fun rotatePiece() {
        if (!started || paused || gameOver) return
        val next = (rotation + 1) % 4
        // Small wall kicks preserve the familiar rotation feel near edges.
        for (kick in intArrayOf(0, -1, 1, -2, 2)) {
            if (isValid(pieceX + kick, pieceY, piece, next)) {
                pieceX += kick
                rotation = next
                invalidate()
                return
            }
        }
    }

    private fun moveDown() {
        if (!started || paused || gameOver) return
        if (!tryMove(0, 1)) lockPiece()
        invalidate()
    }

    private fun hardDrop() {
        if (!started || paused || gameOver) return
        var distance = 0
        while (isValid(pieceX, pieceY + 1, piece, rotation)) {
            pieceY++
            distance++
        }
        score += distance * 2
        lockPiece()
        invalidate()
    }

    private fun lockPiece() {
        val mask = SHAPES[piece][rotation]
        for (y in 0..3) for (x in 0..3) {
            if ((mask and (0x8000 shr (y * 4 + x))) != 0) {
                val gx = pieceX + x
                val gy = pieceY + y
                if (gy in 0 until ROWS && gx in 0 until COLS) board[gy][gx] = piece
            }
        }
        clearLines()
        if (score > best) {
            best = score
            preferences.edit().putInt("best", best).apply()
        }
        spawnPiece()
    }

    private fun clearLines() {
        var y = ROWS - 1
        var count = 0
        while (y >= 0) {
            if (board[y].all { it >= 0 }) {
                for (row in y downTo 1) board[row] = board[row - 1].copyOf()
                board[0] = IntArray(COLS) { -1 }
                count++
            } else {
                y--
            }
        }
        if (count > 0) {
            cleared += count
            score += when (count) {
                1 -> 100 * level
                2 -> 300 * level
                3 -> 500 * level
                else -> 800 * level
            }
            level = cleared / 10 + 1
            scoreFlash = 18
        }
    }

    private fun onGameTap(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!started || gameOver) {
            startNewGame()
            return
        }
        if (y < 52f * uiScale && x > w - 60f * uiScale) {
            paused = !paused
            lastFrameTime = System.currentTimeMillis()
            invalidate()
            return
        }
        if (paused) {
            paused = false
            lastFrameTime = System.currentTimeMillis()
            invalidate()
            return
        }
        if (y >= h - 100f * uiScale) {
            when ((x / w * 5f).toInt().coerceIn(0, 4)) {
                0 -> tryMove(-1, 0)
                1 -> moveDown()
                2 -> tryMove(1, 0)
                3 -> rotatePiece()
                4 -> hardDrop()
            }
            invalidate()
        } else if (y > boardTop && y < boardTop + boardHeight) {
            if (x < w * 0.30f) tryMove(-1, 0)
            else if (x > w * 0.70f) tryMove(1, 0)
            else rotatePiece()
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onGameTap(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}
