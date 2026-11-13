package com.example.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.example.protocol.TouchSample

/**
 * Isolated Display Surface Container:
 * 1) SurfaceView for hardware H.264 decoding (holds last decoded frame during mode switches)
 * 2) LocalCursorEchoView for zero-wait pointer feedback (translation-only, never invalidates SurfaceView)
 * 3) TransparentTouchCaptureView (willNotDraw = true) feeding ~60Hz background coalescer
 */
class DisplaySurfaceContainerView(
    context: Context,
    private val touchDispatcher: TouchCoalescingDispatcher,
    private val onSurfaceAvailable: (Surface?) -> Unit
) : FrameLayout(context) {

    private val videoSurfaceView = SurfaceView(context)
    private val cursorEchoView = LocalCursorEchoView(context)
    private val touchCaptureView = TransparentTouchCaptureView(context)

    init {
        setBackgroundColor(Color.BLACK)

        videoSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceAvailable(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                onSurfaceAvailable(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceAvailable(null)
            }
        })

        addView(
            videoSurfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(
            cursorEchoView,
            LayoutParams(32, 32)
        )
        addView(
            touchCaptureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    private inner class TransparentTouchCaptureView(ctx: Context) : View(ctx) {
        init {
            setWillNotDraw(true)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.coerceAtLeast(1).toFloat()
            val h = height.coerceAtLeast(1).toFloat()
            val normX = (event.x / w).coerceIn(0f, 1f)
            val normY = (event.y / h).coerceIn(0f, 1f)

            val mappedAction = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> TouchSample.ACTION_DOWN
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> TouchSample.ACTION_UP
                else -> TouchSample.ACTION_MOVE
            }

            cursorEchoView.visibility = View.VISIBLE
            cursorEchoView.translationX = event.x - 16f
            cursorEchoView.translationY = event.y - 16f

            touchDispatcher.enqueueTouch(
                action = mappedAction,
                pointerId = event.getPointerId(event.actionIndex).toShort(),
                normX = normX,
                normY = normY
            )
            return true
        }
    }

    private class LocalCursorEchoView(ctx: Context) : View(ctx) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8822D3EE")
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        init {
            visibility = View.INVISIBLE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawCircle(16f, 16f, 11f, fillPaint)
            canvas.drawCircle(16f, 16f, 12f, strokePaint)
        }
    }
}
