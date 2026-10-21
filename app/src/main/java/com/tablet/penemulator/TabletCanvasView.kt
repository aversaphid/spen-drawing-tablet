package com.tablet.penemulator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class TabletCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class AspectRatioMode(val ratioW: Float, val ratioH: Float, val title: String) {
        MONITOR_16_9(16f, 9f, "16:9 Standard"),
        TABLET_16_10(16f, 10f, "16:10 Full"),
        ULTRAWIDE_21_9(21f, 9f, "21:9 Ultrawide")
    }

    var aspectRatioMode: AspectRatioMode = AspectRatioMode.MONITOR_16_9
        set(value) {
            field = value
            updateActiveArea()
            invalidate()
        }

    var onPenEventListener: ((
        tipDown: Boolean,
        barrelButton: Boolean,
        inRange: Boolean,
        x: Int,
        y: Int,
        pressure: Int,
        tiltX: Int,
        tiltY: Int
    ) -> Unit)? = null

    // Active drawing area bounding rect
    private val activeAreaRect = RectF()

    // Paints
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#0B0D13")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#151923")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val activeBorderPaint = Paint().apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val inactiveShadePaint = Paint().apply {
        color = Color.parseColor("#50000000")
        style = Paint.Style.FILL
    }

    private val cursorPaint = Paint().apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val cursorFillPaint = Paint().apply {
        color = Color.parseColor("#4038BDF8")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hudTextPaint = Paint().apply {
        color = Color.parseColor("#94A3B8")
        textSize = 28f
        isAntiAlias = true
    }

    private val hudHighlightPaint = Paint().apply {
        color = Color.parseColor("#34D399")
        textSize = 28f
        isFakeBoldText = true
        isAntiAlias = true
    }

    // Telemetry state for visual rendering
    private var currentNormX = 0f
    private var currentNormY = 0f
    private var currentPressure = 0f
    private var currentTiltX = 0
    private var currentTiltY = 0
    private var isTipDown = false
    private var isBarrelPressed = false
    private var isInRange = false
    private var rawTouchX = 0f
    private var rawTouchY = 0f

    init {
        // Essential to capture hover events from S-Pen
        isHovered = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateActiveArea()
    }

    private fun updateActiveArea() {
        if (width <= 0 || height <= 0) return

        val viewRatio = width.toFloat() / height.toFloat()
        val targetRatio = aspectRatioMode.ratioW / aspectRatioMode.ratioH

        if (viewRatio > targetRatio) {
            // View is wider than target ratio: letterbox left and right
            val targetWidth = height * targetRatio
            val sideInset = (width - targetWidth) / 2f
            activeAreaRect.set(sideInset, 0f, width - sideInset, height.toFloat())
        } else {
            // View is taller than target ratio: letterbox top and bottom
            val targetHeight = width / targetRatio
            val topInset = (height - targetHeight) / 2f
            activeAreaRect.set(0f, topInset, width.toFloat(), height - topInset)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Handle S-Pen Hover events
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        ) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_ENTER -> {
                    processMotionEvent(event, tipDown = false, inRange = true)
                    return true
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    processMotionEvent(event, tipDown = false, inRange = false)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only process stylus events (palm rejection against finger touches)
        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            // Finger touch: ignore or let parent handle
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                processMotionEvent(event, tipDown = true, inRange = true)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                processMotionEvent(event, tipDown = false, inRange = false)
                return true
            }
        }
        return true
    }

    private fun processMotionEvent(event: MotionEvent, tipDown: Boolean, inRange: Boolean) {
        rawTouchX = event.x
        rawTouchY = event.y

        // Normalize within active area (0.0 to 1.0)
        val clampedX = rawTouchX.coerceIn(activeAreaRect.left, activeAreaRect.right)
        val clampedY = rawTouchY.coerceIn(activeAreaRect.top, activeAreaRect.bottom)

        val activeWidth = activeAreaRect.width().coerceAtLeast(1f)
        val activeHeight = activeAreaRect.height().coerceAtLeast(1f)

        currentNormX = (clampedX - activeAreaRect.left) / activeWidth
        currentNormY = (clampedY - activeAreaRect.top) / activeHeight

        currentPressure = if (tipDown) event.pressure.coerceIn(0f, 1f) else 0f
        isTipDown = tipDown
        isInRange = inRange
        isBarrelPressed = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)

        // Calculate Tilt
        val tiltRad = event.getAxisValue(MotionEvent.AXIS_TILT)
        val orientationRad = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)

        // Convert spherical tilt/orientation to Cartesian X, Y degrees (-90..+90)
        val sinTilt = sin(tiltRad.toDouble())
        val tiltXDeg = (sin(orientationRad.toDouble()) * sinTilt * (180.0 / PI)).roundToInt()
        val tiltYDeg = (-cos(orientationRad.toDouble()) * sinTilt * (180.0 / PI)).roundToInt()

        currentTiltX = tiltXDeg.coerceIn(-90, 90)
        currentTiltY = tiltYDeg.coerceIn(-90, 90)

        // Scale to HID ranges:
        // X, Y: 0..32767
        // Pressure: 0..4095
        val hidX = (currentNormX * 32767).roundToInt().coerceIn(0, 32767)
        val hidY = (currentNormY * 32767).roundToInt().coerceIn(0, 32767)
        val hidPressure = (currentPressure * 4095).roundToInt().coerceIn(0, 4095)

        onPenEventListener?.invoke(
            isTipDown,
            isBarrelPressed,
            isInRange,
            hidX,
            hidY,
            hidPressure,
            currentTiltX,
            currentTiltY
        )

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // 2. Draw subtle grid inside active area
        val gridSize = 80f
        var x = activeAreaRect.left
        while (x <= activeAreaRect.right) {
            canvas.drawLine(x, activeAreaRect.top, x, activeAreaRect.bottom, gridPaint)
            x += gridSize
        }
        var y = activeAreaRect.top
        while (y <= activeAreaRect.bottom) {
            canvas.drawLine(activeAreaRect.left, y, activeAreaRect.right, y, gridPaint)
            y += gridSize
        }

        // 3. Shade letterbox/pillarbox inactive zones
        if (activeAreaRect.left > 0) {
            canvas.drawRect(0f, 0f, activeAreaRect.left, height.toFloat(), inactiveShadePaint)
            canvas.drawRect(activeAreaRect.right, 0f, width.toFloat(), height.toFloat(), inactiveShadePaint)
        }
        if (activeAreaRect.top > 0) {
            canvas.drawRect(0f, 0f, width.toFloat(), activeAreaRect.top, inactiveShadePaint)
            canvas.drawRect(0f, activeAreaRect.bottom, width.toFloat(), height.toFloat(), inactiveShadePaint)
        }

        // 4. Draw active area glowing border
        canvas.drawRect(activeAreaRect, activeBorderPaint)

        // 5. Draw pen cursor if hovering or touching
        if (isInRange) {
            val radius = 12f + (currentPressure * 30f)
            canvas.drawCircle(rawTouchX, rawTouchY, radius, cursorFillPaint)
            canvas.drawCircle(rawTouchX, rawTouchY, radius, cursorPaint)
            canvas.drawLine(rawTouchX - radius - 8, rawTouchY, rawTouchX + radius + 8, rawTouchY, cursorPaint)
            canvas.drawLine(rawTouchX, rawTouchY - radius - 8, rawTouchX, rawTouchY + radius + 8, cursorPaint)
        }

        // 6. Draw telemetry HUD in bottom-left corner
        val hudX = activeAreaRect.left + 30f
        val hudY = activeAreaRect.bottom - 40f
        val statusText = if (isTipDown) "DRAWING" else if (isInRange) "HOVERING" else "OUT OF RANGE"
        val statusColor = if (isTipDown) "#38BDF8" else if (isInRange) "#34D399" else "#64748B"
        hudHighlightPaint.color = Color.parseColor(statusColor)

        canvas.drawText("STATE: ", hudX, hudY, hudTextPaint)
        canvas.drawText(statusText, hudX + 110f, hudY, hudHighlightPaint)

        val posText = "POS: ${(currentNormX * 100).roundToInt()}%, ${(currentNormY * 100).roundToInt()}%"
        canvas.drawText(posText, hudX + 320f, hudY, hudTextPaint)

        val pressText = "PRESSURE: ${(currentPressure * 100).roundToInt()}%"
        canvas.drawText(pressText, hudX + 570f, hudY, hudTextPaint)

        val tiltText = "TILT: ${currentTiltX}°, ${currentTiltY}°"
        canvas.drawText(tiltText, hudX + 830f, hudY, hudTextPaint)

        if (isBarrelPressed) {
            hudHighlightPaint.color = Color.parseColor("#FBBF24")
            canvas.drawText("[BUTTON PRESSED]", hudX + 1040f, hudY, hudHighlightPaint)
        }
    }
}
