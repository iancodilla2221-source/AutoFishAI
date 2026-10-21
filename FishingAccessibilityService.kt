package com.example.autofishai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.GestureDescription
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class FishingAccessibilityService : AccessibilityService() {
    companion object { @Volatile var running = false }

    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var lastActionAt = 0L
    private var lastFishBarAt = 0L
    private var lastTapAt = 0L
    private var lastHoldDirection: Boolean? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.post(object : Runnable {
            override fun run() {
                if (running && Build.VERSION.SDK_INT >= 30) scanScreen()
                handler.postDelayed(this, 260L)
            }
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running) return
        // First try accessible buttons, when the game exposes them.
        val root = rootInActiveWindow ?: return
        findClaimNode(root)?.let { node ->
            val now = System.currentTimeMillis()
            if (now - lastTapAt > 1600L) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastTapAt = now
                lastActionAt = now
            }
        }
    }

    private fun findClaimNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
        if (node.isVisibleToUser && node.isClickable &&
            (text.contains("collect") || text.contains("claim") || text == "catch")) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClaimNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun scanScreen() {
        if (!busy.compareAndSet(false, true)) return
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = result.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                    hardware.close()
                    if (wrapped == null) { busy.set(false); return }
                    val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped.recycle()

                    // Analyze the minigame bar directly. This is a visual heuristic, not a
                    // trained model; game UI themes/resolutions can affect detection.
                    val tracking = detectTrackingBar(bitmap)
                    if (tracking != null && running) {
                        lastFishBarAt = System.currentTimeMillis()
                        controlBall(bitmap, tracking)
                    } else {
                        // If no tracking bar is detected, release any held input and use OCR
                        // to look for a Collect/Claim prompt.
                        releaseInput()
                        runClaimOcr(bitmap)
                    }
                    bitmap.recycle()
                    busy.set(false)
                }
                override fun onFailure(errorCode: Int) { busy.set(false) }
            })
        } catch (_: Exception) { busy.set(false) }
    }

    private data class Bar(val x: Int, val top: Int, val bottom: Int, val targetTop: Int, val targetBottom: Int, val ballY: Int)

    private fun detectTrackingBar(b: Bitmap): Bar? {
        val w = b.width
        val h = b.height
        if (w < 200 || h < 200) return null

        // Find columns with purple/magenta outline pixels, typical of the tracking bar.
        val xStart = (w * 0.52f).toInt()
        val xEnd = (w * 0.96f).toInt()
        val yStart = (h * 0.08f).toInt()
        val yEnd = (h * 0.92f).toInt()
        var bestX = -1
        var bestScore = 0
        for (x in xStart until xEnd step max(1, w / 500)) {
            var score = 0
            for (y in yStart until yEnd step max(1, h / 300)) {
                val c = b.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                if (r > 100 && bl > 125 && bl > g * 1.25 && r > g * 1.15) score++
            }
            if (score > bestScore) { bestScore = score; bestX = x }
        }
        if (bestX < 0 || bestScore < 5) return null

        val stepY = max(1, h / 500)
        val purpleYs = mutableListOf<Int>()
        for (y in yStart until yEnd step stepY) {
            var rowScore = 0
            for (x in max(0, bestX - 5) .. min(w - 1, bestX + 5)) {
                val c = b.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                if (r > 100 && bl > 125 && bl > g * 1.25 && r > g * 1.15) rowScore++
            }
            if (rowScore >= 1) purpleYs.add(y)
        }
        if (purpleYs.size < 10) return null
        val barTop = purpleYs.first()
        val barBottom = purpleYs.last()
        if (barBottom - barTop < h * 0.16) return null

        // The green target rectangle is a saturated green cluster adjacent to the bar.
        val gx0 = max(0, bestX - (w * 0.018f).toInt())
        val gx1 = min(w - 1, bestX + (w * 0.018f).toInt())
        val greenRows = mutableListOf<Int>()
        for (y in barTop..barBottom step stepY) {
            var count = 0
            for (x in gx0..gx1) {
                val c = b.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                if (g > 90 && g > r * 1.18 && g > bl * 1.05) count++
            }
            if (count >= max(2, (gx1 - gx0) * 0.08f).toInt()) greenRows.add(y)
        }
        if (greenRows.size < 3) return null

        // Select the longest compact green run as the target zone.
        var runStart = greenRows.first()
        var prev = runStart
        var bestRunStart = runStart
        var bestRunEnd = runStart
        for (y in greenRows.drop(1)) {
            if (y - prev > stepY * 3) {
                if (prev - runStart > bestRunEnd - bestRunStart) {
                    bestRunStart = runStart; bestRunEnd = prev
                }
                runStart = y
            }
            prev = y
        }
        if (prev - runStart > bestRunEnd - bestRunStart) {
            bestRunStart = runStart; bestRunEnd = prev
        }
        if (bestRunEnd - bestRunStart < h * 0.012) return null

        // The moving ball is the bright pale-green/white blob inside the tracking lane.
        val ballX0 = max(0, bestX - (w * 0.012f).toInt())
        val ballX1 = min(w - 1, bestX + (w * 0.012f).toInt())
        var bestBallY = -1
        var bestBallScore = 0
        for (y in barTop..barBottom step stepY) {
            var score = 0
            for (x in ballX0..ballX1) {
                val c = b.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                if (r > 145 && g > 155 && bl > 90 && g >= r * 0.78 && g > bl * 0.88) score++
            }
            if (score > bestBallScore) { bestBallScore = score; bestBallY = y }
        }
        if (bestBallY < 0 || bestBallScore < 2) return null
        return Bar(bestX, barTop, barBottom, bestRunStart, bestRunEnd, bestBallY)
    }

    private fun controlBall(bitmap: Bitmap, bar: Bar) {
        val targetCenter = (bar.targetTop + bar.targetBottom) / 2
        val targetHeight = max(8, bar.targetBottom - bar.targetTop)
        // Hysteresis avoids rapidly flipping hold/release when the ball is near the target.
        val shouldHold = when {
            bar.ballY > targetCenter + targetHeight * 0.18f -> true
            bar.ballY < targetCenter - targetHeight * 0.18f -> false
            else -> lastHoldDirection ?: false
        }
        if (shouldHold != lastHoldDirection) {
            if (shouldHold) pressBriefly() else releaseInput()
            lastHoldDirection = shouldHold
        } else if (shouldHold) {
            // Reapply short hold pulses while the ball is below the target zone.
            pressBriefly()
        }
    }

    private fun pressBriefly() {
        val dm = resources.displayMetrics
        // The game accepts a hold anywhere on the gameplay surface; use center-screen.
        val x = dm.widthPixels * 0.5f
        val y = dm.heightPixels * 0.72f
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
            .build()
        dispatchGesture(gesture, null, null)
        lastActionAt = System.currentTimeMillis()
    }

    private fun releaseInput() {
        // Gesture strokes are short and end on their own, so not dispatching another stroke
        // represents a release. Resetting direction lets the next minigame reacquire safely.
        lastHoldDirection = null
    }

    private fun runClaimOcr(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 900L) return
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                if (!running) return@addOnSuccessListener
                val t = visionText.text.lowercase()
                val line = visionText.textBlocks.flatMap { it.lines }.firstOrNull {
                    val s = it.text.lowercase()
                    s.contains("collect") || s.contains("claim") || s == "catch"
                }
                val box = line?.boundingBox
                if (box != null && System.currentTimeMillis() - lastTapAt > 1500L) {
                    val path = Path().apply { moveTo(box.centerX().toFloat(), box.centerY().toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
                        .build()
                    dispatchGesture(gesture, null, null)
                    lastTapAt = System.currentTimeMillis()
                    lastActionAt = lastTapAt
                }
            }
    }

    override fun onInterrupt() { running = false; releaseInput() }
    override fun onDestroy() { running = false; releaseInput(); recognizer.close(); super.onDestroy() }
}
