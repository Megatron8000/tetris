package com.megatron8000.panelki

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Paint
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
        private const val DAY_NIGHT_SCORE_STEP = 1_000
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

    private val nightBackground: Bitmap = loadBackground("photo_2026-10-15_22-06-02.jpg")
    private val dayBackground: Bitmap = loadBackground("photo_2026-10-15_22-05-31.jpg")
    private val backgroundRect = RectF()

    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        textAlign = Paint.Align.LEFT
    }
    private val handler = Handler(Looper.getMainLooper())
    private val board = Array(ROWS) { IntArray(COLS) { -1 } }
    private val preferences = context.getSharedPreferences("panelki", Context.MODE_PRIVATE)
    var soundEnabled = preferences.getBoolean("sound_enabled", true)
        private set
    var onSoundToggle: ((Boolean) -> Unit)? = null
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
    private var boardLeft = 0f
    private var boardTop = 0f
    private var cell = 0f
    private var uiScale = 1f
    private var boardWidth = 0f
    private var boardHeight = 0f

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

        drawBackground(
            canvas,
            if ((score / DAY_NIGHT_SCORE_STEP) % 2 == 0) nightBackground else dayBackground,
            w,
            h
        )
        drawHeader(canvas, w)
        drawBoard(canvas)
        val recordText = "РЕКОРД  $best"
        val recordSize = 12f * uiScale
        text(canvas, recordText, (w - measure(recordText, recordSize, false)) / 2f,
            boardTop + boardHeight + 22f * uiScale, recordSize, Color.rgb(164, 180, 191))
        drawControls(canvas, w, h)

        if (!started) drawCenterMessage(canvas, "НОВАЯ ИГРА", "КОСНИСЬ ЭКРАНА")
        else if (paused) drawCenterMessage(canvas, "ПАУЗА", "КОСНИСЬ ДЛЯ ПРОДОЛЖЕНИЯ")
        else if (gameOver) drawCenterMessage(canvas, "ИГРА ОКОНЧЕНА", "КОСНИСЬ ДЛЯ РЕСТАРТА")
    }

    private fun loadBackground(fileName: String): Bitmap =
        context.assets.open(fileName).use { input ->
            BitmapFactory.decodeStream(input)
                ?: error("Не удалось загрузить фоновое изображение: $fileName")
        }

    private fun drawBackground(canvas: Canvas, bitmap: Bitmap, w: Float, h: Float) {
        // Scale the supplied image to fill the screen, cropping only when aspect ratios differ.
        val scale = max(w / bitmap.width, h / bitmap.height)
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = (w - scaledWidth) / 2f
        val top = (h - scaledHeight) / 2f
        backgroundRect.set(left, top, left + scaledWidth, top + scaledHeight)
        paint.alpha = 255
        paint.isFilterBitmap = false
        canvas.drawBitmap(bitmap, null, backgroundRect, paint)
    }

    private fun drawHeader(canvas: Canvas, w: Float) {
        text(canvas, "ПАНЕЛЬКИ", w * 0.075f, 42f * uiScale, 29f * uiScale, Color.rgb(229, 221, 196), true)
        text(canvas, "ТЕТРИС ПАНЕЛЬНЫХ ДОМОВ", w * 0.08f, 62f * uiScale, 12f * uiScale, Color.rgb(145, 166, 187))
        text(canvas, "СЧЁТ  $score", w * 0.08f, 91f * uiScale, 16f * uiScale, Color.rgb(244, 189, 111), true)
        text(canvas, "ЛИНИИ  $cleared", w * 0.47f, 91f * uiScale, 14f * uiScale, Color.rgb(196, 204, 205))
        text(canvas, "УР. $level", w * 0.47f, 109f * uiScale, 10f * uiScale, Color.rgb(132, 166, 179))

        val nx = w * 0.80f
        text(canvas, "ДАЛЬШЕ", nx - 2f * uiScale, 64f * uiScale, 12f * uiScale, Color.rgb(145, 166, 187))
        drawMiniPiece(canvas, nextPiece, nx, 76f * uiScale, 10f * uiScale)
        // Sound toggle and pause controls.
        fill(canvas, w - 78f * uiScale, 16f * uiScale, w - 49f * uiScale, 44f * uiScale,
            if (soundEnabled) Color.rgb(30, 45, 68) else Color.rgb(74, 42, 48))
        val soundColor = if (soundEnabled) Color.rgb(235, 211, 164) else Color.rgb(225, 133, 125)
        if (soundEnabled) {
            // Draw the music note as a 14 px-high pixel icon, matching the pause symbol.
            fill(canvas, w - 64f * uiScale, 22f * uiScale, w - 61f * uiScale, 34f * uiScale, soundColor)
            fill(canvas, w - 61f * uiScale, 22f * uiScale, w - 55f * uiScale, 25f * uiScale, soundColor)
            fill(canvas, w - 68f * uiScale, 31f * uiScale, w - 63f * uiScale, 35f * uiScale, soundColor)
        } else {
            // Muted symbol uses the same 14 x 14 px visual bounds.
            fill(canvas, w - 67f * uiScale, 23f * uiScale, w - 65f * uiScale, 25f * uiScale, soundColor)
            fill(canvas, w - 65f * uiScale, 25f * uiScale, w - 61f * uiScale, 29f * uiScale, soundColor)
            fill(canvas, w - 61f * uiScale, 29f * uiScale, w - 57f * uiScale, 33f * uiScale, soundColor)
            fill(canvas, w - 57f * uiScale, 33f * uiScale, w - 55f * uiScale, 35f * uiScale, soundColor)
            fill(canvas, w - 67f * uiScale, 33f * uiScale, w - 65f * uiScale, 35f * uiScale, soundColor)
            fill(canvas, w - 65f * uiScale, 29f * uiScale, w - 61f * uiScale, 33f * uiScale, soundColor)
        }
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
        // Each tetromino cell is a separate piece of facade, with a readable window inset into it.
        // The wall tile and dark outline make the Tetris cell silhouette clear even on a small screen.
        val pad = max(1f, size * 0.055f)
        val l = x + pad
        val t = y + pad
        val r = x + size - pad
        val b = y + size - pad
        val alpha = if (ghost) 78 else 255

        val wall = when (kind % 7) {
            0 -> Color.rgb(190, 183, 160) // pale tiled facade
            1 -> Color.rgb(224, 177, 91)  // warm yellow plaster
            2 -> Color.rgb(174, 190, 177) // faded green-grey
            3 -> Color.rgb(156, 170, 179) // cold concrete
            4 -> Color.rgb(181, 166, 145) // aged panel
            5 -> Color.rgb(166, 190, 198) // blue-grey facade
            else -> Color.rgb(205, 166, 126)
        }
        val frame = when (kind % 7) {
            1, 6 -> Color.rgb(104, 70, 48) // old brown frame
            2 -> Color.rgb(70, 126, 132)
            3, 4 -> Color.rgb(91, 99, 103)
            else -> Color.rgb(53, 113, 145) // weathered blue frame
        }
        val glass = WINDOW_GLASS[kind % WINDOW_GLASS.size]

        // Visible outer block: dark keyline, then a little wall around the window.
        fill(canvas, l, t, r, b, withAlpha(Color.rgb(5, 10, 18), alpha))
        fill(canvas, l + pad * 0.55f, t + pad * 0.55f, r - pad * 0.55f, b - pad * 0.55f,
            withAlpha(wall, alpha))

        // Recess and frame. The window is deliberately smaller than the block, like the references.
        val marginX = size * 0.17f
        val marginY = size * 0.14f
        val wl = l + marginX
        val wt = t + marginY
        val wr = r - marginX
        val wb = b - marginY
        fill(canvas, wl - pad * 0.35f, wt - pad * 0.35f, wr + pad * 0.35f, wb + pad * 0.35f,
            withAlpha(Color.rgb(40, 43, 42), alpha))
        fill(canvas, wl, wt, wr, wb, withAlpha(frame, alpha))

        val frameThickness = max(1f, size * 0.075f)
        val glassL = wl + frameThickness
        val glassT = wt + frameThickness
        val glassR = wr - frameThickness
        val glassB = wb - frameThickness
        val mid = (glassL + glassR) / 2f
        val mullion = max(1f, size * 0.055f)
        val pane = if (WINDOW_LIGHTS[kind % WINDOW_LIGHTS.size]) {
            Color.rgb(255, 196, 105)
        } else glass
        val darkPane = if (WINDOW_LIGHTS[kind % WINDOW_LIGHTS.size]) {
            Color.rgb(232, 166, 80)
        } else Color.rgb(30, 49, 65)

        fill(canvas, glassL, glassT, mid - mullion / 2f, glassB, withAlpha(pane, alpha))
        fill(canvas, mid + mullion / 2f, glassT, glassR, glassB, withAlpha(darkPane, alpha))
        // Clear central upright and horizontal sash, visible even at phone scale.
        fill(canvas, mid - mullion / 2f, wt, mid + mullion / 2f, wb, withAlpha(frame, alpha))
        val crossY = glassT + (glassB - glassT) * 0.58f
        val crossH = max(1f, size * 0.045f)
        fill(canvas, glassL, crossY, glassR, crossY + crossH, withAlpha(frame, alpha))

        // Small bright reflection on the glass and a distinct lower sill.
        fill(canvas, glassL + max(1f, size * 0.04f), glassT + max(1f, size * 0.04f),
            glassL + max(2f, size * 0.10f), crossY - max(1f, size * 0.02f),
            withAlpha(Color.rgb(226, 235, 220), if (ghost) 35 else 210))
        fill(canvas, wl - pad * 0.25f, wb - max(1f, size * 0.035f), wr + pad * 0.25f, wb + pad * 0.25f,
            withAlpha(Color.rgb(219, 205, 178), alpha))
        if (WINDOW_LIGHTS[kind % WINDOW_LIGHTS.size] && !ghost) {
            fill(canvas, glassL + mullion, glassB - max(1f, size * 0.12f),
                glassR - max(1f, size * 0.05f), glassB - max(1f, size * 0.05f),
                Color.rgb(255, 220, 146))
        }
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
        val labels = arrayOf("", "", "", "", "DROP")
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
            if (index < 4) {
                drawControlIcon(canvas, index, cx, centerY, 21f * uiScale, Color.rgb(244, 241, 225))
            } else {
                val textSize = 10f * uiScale
                val tw = measure(label, textSize, true)
                text(canvas, label, cx - tw / 2f, centerY + textSize * 0.35f, textSize, Color.rgb(244, 241, 225), true)
            }
        }
        // Helper caption intentionally removed.
    }

    private fun drawControlIcon(canvas: Canvas, index: Int, cx: Float, cy: Float, size: Float, color: Int) {
        // High-contrast, conventional silhouettes: left, down, right, rotate clockwise.
        val u = size / 8f
        when (index) {
            0 -> { // ◀ left
                fill(canvas, cx - u * 3.5f, cy - u, cx + u * 2.5f, cy + u, color)
                fill(canvas, cx - u * 3.5f, cy - u * 3f, cx - u * 1.5f, cy + u * 3f, color)
                fill(canvas, cx - u * 2.5f, cy - u * 2f, cx - u * 0.5f, cy + u * 2f, color)
            }
            1 -> { // ▼ down
                fill(canvas, cx - u, cy - u * 3.5f, cx + u, cy + u * 1.5f, color)
                fill(canvas, cx - u * 3f, cy + u * 0.5f, cx + u * 3f, cy + u * 2.5f, color)
                fill(canvas, cx - u * 2f, cy + u * 2f, cx + u * 2f, cy + u * 3.5f, color)
            }
            2 -> { // ▶ right
                fill(canvas, cx - u * 2.5f, cy - u, cx + u * 3.5f, cy + u, color)
                fill(canvas, cx + u * 1.5f, cy - u * 3f, cx + u * 3.5f, cy + u * 3f, color)
                fill(canvas, cx + u * 0.5f, cy - u * 2f, cx + u * 2.5f, cy + u * 2f, color)
            }
            else -> { // ↻ clockwise rotate: circular arrow with a clear arrowhead
                val stroke = max(2f * uiScale, u * 1.35f)
                paint.color = color
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.strokeCap = Paint.Cap.SQUARE
                canvas.drawArc(cx - u * 2.5f, cy - u * 2.5f, cx + u * 2.5f, cy + u * 2.5f,
                    -65f, 285f, false, paint)
                paint.style = Paint.Style.FILL
                fill(canvas, cx + u * 0.4f, cy - u * 3.8f, cx + u * 3.5f, cy - u * 1.0f, color)
                fill(canvas, cx + u * 1.8f, cy - u * 1.5f, cx + u * 3.5f, cy + u * 0.3f, color)
            }
        }
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
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
        paint.textSize = size
        paint.typeface = android.graphics.Typeface.create(
            "monospace",
            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.BOLD
        )
        paint.textAlign = Paint.Align.LEFT
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeWidth = max(1f, size * if (bold) 0.13f else 0.075f)
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(9, 18, 32)
        canvas.drawText(value, x, baseline, paint)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawText(value, x, baseline, paint)
    }

    private fun measure(value: String, size: Float, bold: Boolean): Float {
        paint.textSize = size
        paint.typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
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
        }
    }

    private fun onGameTap(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (y < 52f * uiScale && x > w - 90f * uiScale) {
            if (x < w - 46f * uiScale) {
                soundEnabled = !soundEnabled
                preferences.edit().putBoolean("sound_enabled", soundEnabled).apply()
                onSoundToggle?.invoke(soundEnabled)
            } else if (started && !gameOver) {
                paused = !paused
                lastFrameTime = System.currentTimeMillis()
            }
            invalidate()
            return
        }
        if (!started || gameOver) {
            startNewGame()
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
