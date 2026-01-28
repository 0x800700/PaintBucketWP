package com.example.lennycat.wallpaper

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder

class EglRenderThread(name: String, context: Context) : HandlerThread(name), Choreographer.FrameCallback {
    private val appContext = context.applicationContext
    private val readyLock = Object()
    private var handler: Handler? = null
    private var choreographer: Choreographer? = null

    private var eglCore: EglCore? = null
    private var eglSurface: android.opengl.EGLSurface? = null
    private var renderer: PaintRenderer? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var visible = false
    private var frameScheduled = false
    private var lastFrameNanos = 0L

    private val pendingTouches = ArrayList<PaintRenderer.TouchEvent>()
    private val touchLock = Any()

    override fun onLooperPrepared() {
        handler = Handler(looper)
        choreographer = Choreographer.getInstance()
        synchronized(readyLock) {
            readyLock.notifyAll()
        }
    }

    private fun runOnThread(block: () -> Unit) {
        val h = awaitHandler()
        h.post(block)
    }

    private fun awaitHandler(): Handler {
        synchronized(readyLock) {
            while (handler == null) {
                readyLock.wait()
            }
            return handler!!
        }
    }

    fun setSurface(holder: SurfaceHolder) {
        runOnThread {
            initEglIfNeeded()
            createSurface(holder.surface)
            if (renderer == null) {
                renderer = PaintRenderer(appContext)
                renderer?.onSurfaceCreated()
            }
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                renderer?.onSurfaceChanged(surfaceWidth, surfaceHeight)
            }
            scheduleFrameIfNeeded()
        }
    }

    fun surfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        runOnThread {
            renderer?.onSurfaceChanged(width, height)
        }
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        runOnThread {
            if (visible) {
                scheduleFrameIfNeeded()
            } else {
                choreographer?.removeFrameCallback(this)
                frameScheduled = false
                lastFrameNanos = 0L
            }
        }
    }

    fun surfaceDestroyed() {
        runOnThread {
            choreographer?.removeFrameCallback(this)
            frameScheduled = false
            lastFrameNanos = 0L
            eglSurface?.let { surface ->
                eglCore?.releaseSurface(surface)
            }
            eglSurface = null
        }
    }

    fun shutdown() {
        runOnThread {
            choreographer?.removeFrameCallback(this)
            frameScheduled = false
            lastFrameNanos = 0L
            renderer?.release()
            renderer = null
            eglSurface?.let { surface ->
                eglCore?.releaseSurface(surface)
            }
            eglSurface = null
            eglCore?.release()
            eglCore = null
        }
        quitSafely()
    }

    fun queueTouch(x: Float, y: Float, dx: Float, dy: Float) {
        synchronized(touchLock) {
            pendingTouches.add(PaintRenderer.TouchEvent(x, y, dx, dy))
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!visible || eglSurface == null || renderer == null) {
            return
        }
        val touches = drainTouches()
        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameTimeNanos
        }
        val dt = ((frameTimeNanos - lastFrameNanos).coerceAtMost(100_000_000L)).toFloat() / 1_000_000_000f
        lastFrameNanos = frameTimeNanos

        eglCore?.makeCurrent(eglSurface!!)
        if (touches.isNotEmpty()) {
            touches.forEach { renderer?.enqueueTouch(it.x, it.y, it.dx, it.dy) }
        }
        renderer?.onDrawFrame(dt)
        eglCore?.swapBuffers(eglSurface!!)

        scheduleFrameIfNeeded()
    }

    private fun scheduleFrameIfNeeded() {
        if (!visible || frameScheduled) return
        frameScheduled = true
        choreographer?.postFrameCallback(this)
    }

    private fun initEglIfNeeded() {
        if (eglCore != null) return
        eglCore = EglCore()
    }

    private fun createSurface(surface: Surface) {
        eglSurface?.let { old ->
            eglCore?.releaseSurface(old)
        }
        eglSurface = eglCore?.createWindowSurface(surface)
        eglCore?.makeCurrent(eglSurface!!)
        eglCore?.setSwapInterval(1)
    }

    private fun drainTouches(): List<PaintRenderer.TouchEvent> {
        synchronized(touchLock) {
            if (pendingTouches.isEmpty()) return emptyList()
            val copy = ArrayList<PaintRenderer.TouchEvent>(pendingTouches)
            pendingTouches.clear()
            return copy
        }
    }
}
