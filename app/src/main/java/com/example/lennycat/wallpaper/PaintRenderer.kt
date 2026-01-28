package com.example.lennycat.wallpaper

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.random.Random

class PaintRenderer {
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var simWidth = 0
    private var simHeight = 0

    private val simScale = 0.5f
    private var timeSec = 0f
    private var injectTimer = 0f

    private var quadVao = 0
    private var quadVbo = 0

    private var advectionProgram = 0
    private var injectProgram = 0
    private var finalProgram = 0

    private val paintTex = IntArray(2)
    private val paintFbo = IntArray(2)
    private var srcIndex = 0

    private val random = Random(7)

    fun onSurfaceCreated() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_DITHER)

        setupQuad()
        advectionProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_ADVECTION)
        injectProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_INJECT)
        finalProgram = GlUtil.createProgram(VS_FULLSCREEN, FS_FINAL)

        injectTimer = 0f
        timeSec = 0f
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        val newSimWidth = max(1, (width * simScale).toInt())
        val newSimHeight = max(1, (height * simScale).toInt())
        if (newSimWidth != simWidth || newSimHeight != simHeight) {
            simWidth = newSimWidth
            simHeight = newSimHeight
            createPaintTargets()
        }
    }

    fun onDrawFrame(dt: Float) {
        timeSec += dt
        injectTimer -= dt

        val dstIndex = (srcIndex + 1) % 2

        GLES30.glViewport(0, 0, simWidth, simHeight)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, paintFbo[dstIndex])
        GLES30.glUseProgram(advectionProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, paintTex[srcIndex])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(advectionProgram, "u_paint"), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectionProgram, "u_dt"), dt)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(advectionProgram, "u_texel"),
            1f / simWidth.toFloat(),
            1f / simHeight.toFloat()
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(advectionProgram, "u_time"), timeSec)
        drawFullscreen()

        if (injectTimer <= 0f) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(injectProgram)
            // Top crown: dense pour with warm center
            val radius = 0.26f + random.nextFloat() * 0.12f
            drawInjectSpot(0.22f + random.nextFloat() * 0.08f, 0.94f, radius, floatArrayOf(0.98f, 0.25f, 0.62f))
            drawInjectSpot(0.42f + random.nextFloat() * 0.08f, 0.95f, radius * 1.05f, floatArrayOf(0.98f, 0.58f, 0.16f))
            drawInjectSpot(0.55f + random.nextFloat() * 0.08f, 0.96f, radius * 1.08f, floatArrayOf(0.99f, 0.78f, 0.14f))
            drawInjectSpot(0.68f + random.nextFloat() * 0.08f, 0.94f, radius * 0.95f, floatArrayOf(0.92f, 0.22f, 0.72f))
            drawInjectSpot(0.78f + random.nextFloat() * 0.08f, 0.92f, radius * 0.9f, floatArrayOf(0.22f, 0.66f, 0.96f))

            // Mid burst: colorful splash body
            injectMidBurst()

            // Splashes across the screen from the initial pour
            injectSplashes()
            GLES30.glDisable(GLES30.GL_BLEND)

            injectTimer = 2.0f + random.nextFloat() * 1.2f
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(finalProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, paintTex[dstIndex])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(finalProgram, "u_paint"), 0)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(finalProgram, "u_texel"),
            1f / simWidth.toFloat(),
            1f / simHeight.toFloat()
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(finalProgram, "u_time"), timeSec)
        drawFullscreen()

        srcIndex = dstIndex
    }

    fun release() {
        if (paintFbo[0] != 0) {
            GLES30.glDeleteFramebuffers(2, paintFbo, 0)
        }
        if (paintTex[0] != 0) {
            GLES30.glDeleteTextures(2, paintTex, 0)
        }
        if (quadVao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        }
        if (quadVbo != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        }
        if (advectionProgram != 0) GLES30.glDeleteProgram(advectionProgram)
        if (injectProgram != 0) GLES30.glDeleteProgram(injectProgram)
        if (finalProgram != 0) GLES30.glDeleteProgram(finalProgram)
    }

    private fun createPaintTargets() {
        if (paintTex[0] != 0) {
            GLES30.glDeleteTextures(2, paintTex, 0)
        }
        if (paintFbo[0] != 0) {
            GLES30.glDeleteFramebuffers(2, paintFbo, 0)
        }
        GLES30.glGenTextures(2, paintTex, 0)
        GLES30.glGenFramebuffers(2, paintFbo, 0)

        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, paintTex[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA,
                simWidth,
                simHeight,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null
            )

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, paintFbo[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                paintTex[i],
                0
            )
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, paintFbo[0])
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, paintFbo[1])
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        srcIndex = 0
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

    private fun drawInjectSpot(cx: Float, cy: Float, radius: Float, color: FloatArray) {
        GLES30.glUniform2f(GLES30.glGetUniformLocation(injectProgram, "u_center"), cx, cy)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(injectProgram, "u_radius"), radius)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(injectProgram, "u_color"), color[0], color[1], color[2])
        drawFullscreen()
    }

    private fun injectMidBurst() {
        val palette = arrayOf(
            floatArrayOf(0.98f, 0.32f, 0.62f),
            floatArrayOf(0.99f, 0.76f, 0.18f),
            floatArrayOf(0.95f, 0.45f, 0.12f),
            floatArrayOf(0.22f, 0.68f, 0.98f),
            floatArrayOf(0.62f, 0.24f, 0.92f)
        )
        val count = 24
        for (i in 0 until count) {
            val c = palette[i % palette.size]
            val x = 0.15f + random.nextFloat() * 0.7f
            val y = 0.55f + random.nextFloat() * 0.2f
            val r = 0.08f + random.nextFloat() * 0.08f
            drawInjectSpot(x, y, r, c)
        }
    }

    private fun injectSplashes() {
        val palette = arrayOf(
            floatArrayOf(0.95f, 0.22f, 0.62f),
            floatArrayOf(0.98f, 0.74f, 0.12f),
            floatArrayOf(0.17f, 0.64f, 0.96f),
            floatArrayOf(0.58f, 0.2f, 0.92f)
        )
        val count = 120
        for (i in 0 until count) {
            val c = palette[i % palette.size]
            val x = random.nextFloat()
            val y = 0.15f + random.nextFloat() * 0.75f
            val r = 0.012f + random.nextFloat() * 0.028f
            drawInjectSpot(x, y, r, c)
        }
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

        private const val FS_ADVECTION = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform sampler2D u_paint;
            uniform float u_dt;
            uniform vec2 u_texel;
            uniform float u_time;

            float hash(vec2 p){
                p = fract(p * vec2(123.34, 345.45));
                p += dot(p, p + 34.345);
                return fract(p.x * p.y);
            }

            float noise(vec2 p){
                vec2 i = floor(p);
                vec2 f = fract(p);
                float a = hash(i);
                float b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0));
                float d = hash(i + vec2(1.0, 1.0));
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
            }

            void main() {
                vec4 cur = texture(u_paint, v_uv);
                float h = cur.a;

                float down = 0.15 + 1.2 * h;
                float side = 0.0;
                float n = noise(v_uv * 8.0 + u_time * 0.2);
                side += (n - 0.5) * 0.10 * (0.2 + h);
                vec2 vel = vec2(side, -down);

                vec2 prevUV = v_uv - vel * u_dt;
                prevUV = clamp(prevUV, vec2(0.0), vec2(1.0));

                vec4 adv = texture(u_paint, prevUV);
                vec4 blur =
                    texture(u_paint, prevUV + vec2( u_texel.x, 0.0)) +
                    texture(u_paint, prevUV + vec2(-u_texel.x, 0.0)) +
                    texture(u_paint, prevUV + vec2(0.0,  u_texel.y)) +
                    texture(u_paint, prevUV + vec2(0.0, -u_texel.y)) +
                    texture(u_paint, prevUV + vec2(0.0,  2.0 * u_texel.y)) +
                    texture(u_paint, prevUV + vec2(0.0,  3.5 * u_texel.y));
                blur *= (1.0 / 6.0);

                vec4 outC = mix(adv, blur, 0.05);
                outC.a *= (1.0 - 0.12 * u_dt);

                if (outC.a < 0.015) {
                    outC = vec4(0.0);
                }
                o_color = outC;
            }
        """

        private const val FS_INJECT = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform vec2 u_center;
            uniform float u_radius;
            uniform vec3 u_color;

            void main() {
                float d = length(v_uv - u_center);
                float m = smoothstep(u_radius, u_radius * 0.6, d);
                float a = m;
                o_color = vec4(u_color * a, a);
            }
        """

        private const val FS_FINAL = """
            #version 300 es
            precision highp float;
            in vec2 v_uv;
            out vec4 o_color;
            uniform sampler2D u_paint;
            uniform vec2 u_texel;
            uniform float u_time;

            void main() {
                vec4 p = texture(u_paint, v_uv);
                float hx = texture(u_paint, v_uv + vec2(u_texel.x, 0.0)).a - texture(u_paint, v_uv - vec2(u_texel.x, 0.0)).a;
                float hy = texture(u_paint, v_uv + vec2(0.0, u_texel.y)).a - texture(u_paint, v_uv - vec2(0.0, u_texel.y)).a;

                vec2 refr = vec2(hx, hy) * 0.02;
                vec4 pr = texture(u_paint, v_uv + refr);

                vec3 n = normalize(vec3(-hx * 2.0, -hy * 2.0, 1.0));
                vec3 l = normalize(vec3(0.2, 0.6, 0.77));
                float spec = pow(max(dot(reflect(-l, n), vec3(0.0, 0.0, 1.0)), 0.0), 64.0);
                vec3 glass = vec3(1.0) * spec * 0.35 * pr.a;

                vec3 col = pr.rgb + glass;
                o_color = vec4(col, pr.a);
            }
        """
    }
}
