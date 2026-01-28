package com.example.lennycat.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class PaintRenderer(private val context: Context) {
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var simWidth = 0
    private var simHeight = 0

    private val simScale = 0.5f
    private var timeSec = 0f

    private var quadVao = 0
    private var quadVbo = 0

    private var splatVelProgram = 0
    private var splatMaskProgram = 0
    private var advectVelProgram = 0
    private var advectMaskProgram = 0
    private var compositeProgram = 0
    private var initMaskProgram = 0

    private val velTex = IntArray(2)
    private val maskTex = IntArray(2)
    private val velFbo = IntArray(2)
    private val maskFbo = IntArray(2)

    private var velSrc = 0
    private var maskSrc = 0

    private var paintTex = 0
    private var glassTex = 0
    private var fallbackTex = 0
    private var hasGlass = false

    private var useHalfFloat = true

    private val pendingTouches = ArrayList<TouchEvent>()
    private val touchLock = Any()

    private val random = Random(7)
    private val drops = ArrayList<Drop>(140)

    data class Drop(var x: Float, var y: Float, var vy: Float, var radius: Float)

    data class TouchEvent(val x: Float, val y: Float, val dx: Float, val dy: Float)

    fun onSurfaceCreated() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_DITHER)

        setupQuad()
        splatVelProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_SPLAT_VEL)
        splatMaskProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_SPLAT_MASK)
        advectVelProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_ADVECT_VEL)
        advectMaskProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_ADVECT_MASK)
        compositeProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_COMPOSITE)
        initMaskProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_INIT_MASK)

        paintTex = loadTextureFromAssets("paint_ref.png")
            ?: createGradientTexture(256, 256)

        val glass = loadTextureFromAssets("glass_ref.png")
        if (glass != null) {
            glassTex = glass
            hasGlass = true
        } else {
            glassTex = 0
            hasGlass = false
        }

        fallbackTex = createSolidTexture(1, 1, 0, 0, 0, 0)
        timeSec = 0f
        initDrops()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        val newSimWidth = max(1, (width * simScale).toInt())
        val newSimHeight = max(1, (height * simScale).toInt())
        if (newSimWidth != simWidth || newSimHeight != simHeight) {
            simWidth = newSimWidth
            simHeight = newSimHeight
            createTargets()
            initMaskFromReference()
        }
    }

    fun onDrawFrame(dt: Float) {
        timeSec += dt
        val touches = consumeTouches()

        GLES30.glViewport(0, 0, simWidth, simHeight)
        GLES30.glDisable(GLES30.GL_BLEND)

        if (touches.isNotEmpty()) {
            for (t in touches) {
                val force = 2.8f
                val fx = t.dx * force
                val fy = t.dy * force
                splatVelocity(t.x, t.y, fx, fy, 0.08f)
                splatMask(t.x, t.y, 0.08f, 0.22f)
            }
        }

        // periodic gentle drip seed at the top
        if (random.nextFloat() < 0.04f) {
            val x = 0.1f + random.nextFloat() * 0.8f
            val y = 0.92f + random.nextFloat() * 0.05f
            val r = 0.07f + random.nextFloat() * 0.06f
            splatMask(x, y, r, 0.35f)
            splatVelocity(x, y, (random.nextFloat() - 0.5f) * 0.7f, -1.15f, r)
        }

        advectVelocity(dt)
        advectMask(dt)
        updateDrops(dt)
        renderComposite()
    }

    fun enqueueTouch(x: Float, y: Float, dx: Float, dy: Float) {
        synchronized(touchLock) {
            pendingTouches.add(TouchEvent(x, y, dx, dy))
        }
    }

    fun release() {
        if (velFbo[0] != 0) GLES30.glDeleteFramebuffers(2, velFbo, 0)
        if (maskFbo[0] != 0) GLES30.glDeleteFramebuffers(2, maskFbo, 0)
        if (velTex[0] != 0) GLES30.glDeleteTextures(2, velTex, 0)
        if (maskTex[0] != 0) GLES30.glDeleteTextures(2, maskTex, 0)
        if (paintTex != 0) GLES30.glDeleteTextures(1, intArrayOf(paintTex), 0)
        if (glassTex != 0) GLES30.glDeleteTextures(1, intArrayOf(glassTex), 0)
        if (fallbackTex != 0) GLES30.glDeleteTextures(1, intArrayOf(fallbackTex), 0)

        if (quadVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)

        if (splatVelProgram != 0) GLES30.glDeleteProgram(splatVelProgram)
        if (splatMaskProgram != 0) GLES30.glDeleteProgram(splatMaskProgram)
        if (advectVelProgram != 0) GLES30.glDeleteProgram(advectVelProgram)
        if (advectMaskProgram != 0) GLES30.glDeleteProgram(advectMaskProgram)
        if (compositeProgram != 0) GLES30.glDeleteProgram(compositeProgram)
        if (initMaskProgram != 0) GLES30.glDeleteProgram(initMaskProgram)
    }

    private fun consumeTouches(): List<TouchEvent> {
        synchronized(touchLock) {
            if (pendingTouches.isEmpty()) return emptyList()
            val copy = ArrayList<TouchEvent>(pendingTouches)
            pendingTouches.clear()
            return copy
        }
    }

    private fun createTargets() {
        deleteTargets()
        useHalfFloat = true
        if (!createTargetsInternal(true)) {
            deleteTargets()
            useHalfFloat = false
            createTargetsInternal(false)
        }
        velSrc = 0
        maskSrc = 0
    }

    private fun deleteTargets() {
        if (velTex[0] != 0) GLES30.glDeleteTextures(2, velTex, 0)
        if (maskTex[0] != 0) GLES30.glDeleteTextures(2, maskTex, 0)
        if (velFbo[0] != 0) GLES30.glDeleteFramebuffers(2, velFbo, 0)
        if (maskFbo[0] != 0) GLES30.glDeleteFramebuffers(2, maskFbo, 0)
        velTex.fill(0)
        maskTex.fill(0)
        velFbo.fill(0)
        maskFbo.fill(0)
    }

    private fun createTargetsInternal(halfFloat: Boolean): Boolean {
        val internalFormat = if (halfFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA
        val format = GLES30.GL_RGBA
        val type = if (halfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE

        GLES30.glGenTextures(2, velTex, 0)
        GLES30.glGenTextures(2, maskTex, 0)
        GLES30.glGenFramebuffers(2, velFbo, 0)
        GLES30.glGenFramebuffers(2, maskFbo, 0)

        for (i in 0..1) {
            setupTexture(velTex[i], internalFormat, format, type)
            setupTexture(maskTex[i], internalFormat, format, type)
        }

        if (!attachAndCheck(velFbo[0], velTex[0])) return false
        if (!attachAndCheck(velFbo[1], velTex[1])) return false
        if (!attachAndCheck(maskFbo[0], maskTex[0])) return false
        if (!attachAndCheck(maskFbo[1], maskTex[1])) return false

        clearTexture(velFbo[0])
        clearTexture(velFbo[1])
        clearTexture(maskFbo[0])
        clearTexture(maskFbo[1])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        return true
    }

    private fun setupTexture(textureId: Int, internalFormat: Int, format: Int, type: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            internalFormat,
            simWidth,
            simHeight,
            0,
            format,
            type,
            null
        )
    }

    private fun attachAndCheck(fbo: Int, textureId: Int): Boolean {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        return status == GLES30.GL_FRAMEBUFFER_COMPLETE
    }

    private fun clearTexture(fbo: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun setupQuad() {
        val quadData = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            -1f, 1f,
            1f, -1f,
            1f, 1f
        )
        val buffer = ByteBuffer.allocateDirect(quadData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(quadData).position(0)

        val vao = IntArray(1)
        val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        quadVao = vao[0]
        quadVbo = vbo[0]

        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quadData.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun drawFullscreen() {
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
    }

    private fun splatVelocity(x: Float, y: Float, fx: Float, fy: Float, radius: Float) {
        val dst = 1 - velSrc
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, velFbo[dst])
        GLES30.glUseProgram(splatVelProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, velTex[velSrc])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(splatVelProgram, "u_vel"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(splatVelProgram, "u_point"), x, y)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(splatVelProgram, "u_force"), fx, fy)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(splatVelProgram, "u_radius"), radius)
        drawFullscreen()
        velSrc = dst
    }

    private fun splatMask(x: Float, y: Float, radius: Float, amount: Float) {
        val dst = 1 - maskSrc
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, maskFbo[dst])
        GLES30.glUseProgram(splatMaskProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex[maskSrc])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(splatMaskProgram, "u_mask"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(splatMaskProgram, "u_point"), x, y)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(splatMaskProgram, "u_radius"), radius)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(splatMaskProgram, "u_amount"), amount)
        drawFullscreen()
        maskSrc = dst
    }

    private fun advectVelocity(dt: Float) {
        val dst = 1 - velSrc
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, velFbo[dst])
        GLES30.glUseProgram(advectVelProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, velTex[velSrc])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(advectVelProgram, "u_vel"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex[maskSrc])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(advectVelProgram, "u_mask"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectVelProgram, "u_dt"), dt)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectVelProgram, "u_dissipation"), 0.985f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectVelProgram, "u_gravity"), -1.2f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectVelProgram, "u_time"), timeSec)
        drawFullscreen()
        velSrc = dst
    }

    private fun advectMask(dt: Float) {
        val dst = 1 - maskSrc
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, maskFbo[dst])
        GLES30.glUseProgram(advectMaskProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex[maskSrc])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(advectMaskProgram, "u_mask"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, velTex[velSrc])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(advectMaskProgram, "u_vel"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectMaskProgram, "u_dt"), dt)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectMaskProgram, "u_dissipation"), 0.999f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectMaskProgram, "u_threshold"), 0.004f)
        drawFullscreen()
        maskSrc = dst
    }

    private fun renderComposite() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(compositeProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (paintTex != 0) paintTex else fallbackTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "u_paintRef"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasGlass) glassTex else fallbackTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "u_glassRef"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex[maskSrc])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(compositeProgram, "u_mask"), 2)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(compositeProgram, "u_texel"),
            1f / simWidth.toFloat(),
            1f / simHeight.toFloat()
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "u_time"), timeSec)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(compositeProgram, "u_hasGlass"), if (hasGlass) 1f else 0f)
        drawFullscreen()
    }

    private fun initMaskFromReference() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, maskFbo[0])
        GLES30.glViewport(0, 0, simWidth, simHeight)
        GLES30.glUseProgram(initMaskProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (paintTex != 0) paintTex else fallbackTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(initMaskProgram, "u_paintRef"), 0)
        drawFullscreen()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, maskFbo[1])
        GLES30.glUseProgram(initMaskProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (paintTex != 0) paintTex else fallbackTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(initMaskProgram, "u_paintRef"), 0)
        drawFullscreen()

        maskSrc = 0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun initDrops() {
        drops.clear()
        repeat(140) {
            drops.add(
                Drop(
                    x = random.nextFloat(),
                    y = 0.1f + random.nextFloat() * 0.85f,
                    vy = 0.08f + random.nextFloat() * 0.28f,
                    radius = 0.01f + random.nextFloat() * 0.025f
                )
            )
        }
    }

    private fun updateDrops(dt: Float) {
        for (d in drops) {
            d.y -= d.vy * dt
            if (d.y < -0.05f) {
                d.y = 1.02f + random.nextFloat() * 0.2f
                d.x = random.nextFloat()
                d.vy = 0.08f + random.nextFloat() * 0.28f
                d.radius = 0.01f + random.nextFloat() * 0.025f
            }
            splatMask(d.x, d.y, d.radius, 0.1f)
            splatVelocity(d.x, d.y, 0f, -0.9f, d.radius * 2.2f)
        }
    }

    private fun loadTextureFromAssets(name: String): Int? {
        val bitmap = loadBitmapFromAssets(name) ?: return null
        val textureId = IntArray(1)
        GLES30.glGenTextures(1, textureId, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return textureId[0]
    }

    private fun loadBitmapFromAssets(name: String): Bitmap? {
        return try {
            context.assets.open(name).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createGradientTexture(width: Int, height: Int): Int {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            val fy = y / (height - 1f)
            for (x in 0 until width) {
                val fx = x / (width - 1f)
                val r = min(1f, 1.2f * (1f - fx) + 0.2f * fy)
                val g = min(1f, 0.4f + 0.6f * fx)
                val b = min(1f, 0.8f * fx + 0.2f * (1f - fy))
                val color = (255 shl 24) or
                    ((r * 255).toInt() shl 16) or
                    ((g * 255).toInt() shl 8) or
                    (b * 255).toInt()
                bitmap.setPixel(x, y, color)
            }
        }
        val textureId = IntArray(1)
        GLES30.glGenTextures(1, textureId, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return textureId[0]
    }

    private fun createSolidTexture(width: Int, height: Int, r: Int, g: Int, b: Int, a: Int): Int {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val color = (a shl 24) or (r shl 16) or (g shl 8) or b
        bitmap.eraseColor(color)
        val textureId = IntArray(1)
        GLES30.glGenTextures(1, textureId, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return textureId[0]
    }

    companion object {
        private const val VS_FULLSCREEN = """
            #version 300 es
            layout(location=0) in vec2 a_pos;
            out vec2 v_uv;
            void main() {
                v_uv = a_pos * 0.5 + 0.5;
                gl_Position = vec4(a_pos, 0.0, 1.0);
            }
        """

        private const val FS_SPLAT_VEL = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform sampler2D u_vel;
            uniform vec2 u_point;
            uniform vec2 u_force;
            uniform float u_radius;
            void main(){
                vec2 vel = texture(u_vel, v_uv).xy;
                float d = distance(v_uv, u_point);
                float w = exp(-(d*d)/(u_radius*u_radius));
                vel += u_force * w;
                o_color = vec4(vel, 0.0, 1.0);
            }
        """

        private const val FS_SPLAT_MASK = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform sampler2D u_mask;
            uniform vec2 u_point;
            uniform float u_amount;
            uniform float u_radius;
            void main(){
                float m = texture(u_mask, v_uv).r;
                float d = distance(v_uv, u_point);
                float w = exp(-(d*d)/(u_radius*u_radius));
                m = clamp(m + u_amount * w, 0.0, 1.0);
                o_color = vec4(m, 0.0, 0.0, 1.0);
            }
        """

        private const val FS_ADVECT_VEL = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform sampler2D u_vel;
            uniform sampler2D u_mask;
            uniform float u_dt;
            uniform float u_dissipation;
            uniform float u_gravity;
            uniform float u_time;
            float hash(vec2 p){
                p = fract(p*vec2(123.34, 345.45));
                p += dot(p, p+34.345);
                return fract(p.x*p.y);
            }
            void main(){
                vec2 vel = texture(u_vel, v_uv).xy;
                vec2 prev = clamp(v_uv - vel * u_dt, vec2(0.0), vec2(1.0));
                vec2 adv = texture(u_vel, prev).xy * u_dissipation;
                float m = texture(u_mask, v_uv).r;
                float n = hash(v_uv * 8.0 + u_time * 0.2) - 0.5;
                adv.x += n * 0.08 * (0.2 + m);
                adv.y += u_gravity * u_dt * (0.2 + m);
                o_color = vec4(adv, 0.0, 1.0);
            }
        """

        private const val FS_ADVECT_MASK = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform sampler2D u_mask;
            uniform sampler2D u_vel;
            uniform float u_dt;
            uniform float u_dissipation;
            uniform float u_threshold;
            void main(){
                vec2 vel = texture(u_vel, v_uv).xy;
                vec2 prev = clamp(v_uv - vel * u_dt, vec2(0.0), vec2(1.0));
                float m = texture(u_mask, prev).r * u_dissipation;
                if (m < u_threshold) m = 0.0;
                o_color = vec4(m, 0.0, 0.0, 1.0);
            }
        """

        private const val FS_INIT_MASK = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform sampler2D u_paintRef;
            void main(){
                vec2 uv = vec2(v_uv.x, 1.0 - v_uv.y);
                vec3 c = texture(u_paintRef, uv).rgb;
                float luma = dot(c, vec3(0.299, 0.587, 0.114));
                float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                float base = max(luma, sat);
                float m = smoothstep(0.03, 0.6, base);
                m = pow(m, 0.6);
                o_color = vec4(m, 0.0, 0.0, 1.0);
            }
        """

        private const val FS_COMPOSITE = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform sampler2D u_paintRef;
            uniform sampler2D u_glassRef;
            uniform sampler2D u_mask;
            uniform vec2 u_texel;
            uniform float u_time;
            uniform float u_hasGlass;

            float hash(vec2 p){
                p = fract(p*vec2(123.34, 345.45));
                p += dot(p, p+34.345);
                return fract(p.x*p.y);
            }

            void main(){
                float m = texture(u_mask, v_uv).r;
                if (m < 0.003) {
                    float grain = hash(v_uv * 512.0 + u_time * 0.05) * 0.02;
                    vec3 glass = vec3(grain);
                    if (u_hasGlass > 0.5) {
                        vec2 uv = vec2(v_uv.x, 1.0 - v_uv.y);
                        glass = texture(u_glassRef, uv).rgb * 0.08 + grain;
                    }
                    o_color = vec4(glass, 0.0);
                    return;
                }

                float mx = texture(u_mask, v_uv + vec2(u_texel.x, 0.0)).r - texture(u_mask, v_uv - vec2(u_texel.x, 0.0)).r;
                float my = texture(u_mask, v_uv + vec2(0.0, u_texel.y)).r - texture(u_mask, v_uv - vec2(0.0, u_texel.y)).r;

                vec2 refr = vec2(mx, my) * 0.065;
                vec2 uvR = clamp(v_uv + refr, vec2(0.0), vec2(1.0));
                uvR.y = 1.0 - uvR.y;

                vec4 paint = texture(u_paintRef, uvR);
                float alpha = smoothstep(0.02, 0.6, m);
                vec3 base = paint.rgb * alpha;

                vec3 n = normalize(vec3(-mx*2.4, -my*2.4, 1.0));
                vec3 l = normalize(vec3(0.25, 0.65, 0.72));
                float spec = pow(max(dot(reflect(-l, n), vec3(0.0, 0.0, 1.0)), 0.0), 48.0);
                vec3 glassSpec = vec3(1.0) * spec * 0.5 * alpha;

                vec3 glass = vec3(0.0);
                if (u_hasGlass > 0.5) {
                    vec2 uv = vec2(v_uv.x, 1.0 - v_uv.y);
                    glass = texture(u_glassRef, uv).rgb * 0.08;
                }

                vec3 col = base + glassSpec + glass;
                o_color = vec4(col, alpha);
            }
        """
    }
}
