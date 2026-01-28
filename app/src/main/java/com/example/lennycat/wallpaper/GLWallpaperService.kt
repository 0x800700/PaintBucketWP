package com.example.lennycat.wallpaper

import android.graphics.PixelFormat
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

open class GLWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return GLEngine()
    }

    inner class GLEngine : Engine() {
        private var renderThread: EglRenderThread? = null
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var hasLastTouch = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.OPAQUE)
            setTouchEventsEnabled(true)
            renderThread = EglRenderThread("GLWallpaperThread", this@GLWallpaperService).also { it.start() }
        }

        override fun onDestroy() {
            renderThread?.shutdown()
            renderThread = null
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            renderThread?.setVisible(visible)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderThread?.setSurface(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderThread?.surfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderThread?.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent) {
            val width = surfaceHolder?.surfaceFrame?.width() ?: return
            val height = surfaceHolder?.surfaceFrame?.height() ?: return
            if (width == 0 || height == 0) return

            val x = event.x / width.toFloat()
            val y = 1f - (event.y / height.toFloat())

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    hasLastTouch = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!hasLastTouch) {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        hasLastTouch = true
                    }
                    val dx = (event.x - lastTouchX) / width.toFloat()
                    val dy = (lastTouchY - event.y) / height.toFloat()
                    renderThread?.queueTouch(x, y, dx, dy)
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    hasLastTouch = false
                }
            }
            super.onTouchEvent(event)
        }
    }
}
