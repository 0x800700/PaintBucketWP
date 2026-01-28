package com.example.lennycat.wallpaper

import android.graphics.PixelFormat
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

open class GLWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return GLEngine()
    }

    inner class GLEngine : Engine() {
        private var renderThread: EglRenderThread? = null

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.OPAQUE)
            renderThread = EglRenderThread("GLWallpaperThread").also { it.start() }
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
    }
}
