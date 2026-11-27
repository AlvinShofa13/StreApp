package com.studiopocket.core.video

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Quad penuh untuk transisi fade ke hitam. */
class SolidProgram {
    private var program = 0
    private var aPosition = 0
    private var uColor = 0
    private val vertices = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    init {
        val vs = """
            #version 300 es
            in vec2 aPosition;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
        """.trimIndent()
        val fs = """
            #version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() { fragColor = uColor; }
        """.trimIndent()
        program = GlShader.createProgram(vs, fs)
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        uColor = GLES30.glGetUniformLocation(program, "uColor")
    }

    fun draw(alpha: Float) {
        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUniform4f(uColor, 0f, 0f, 0f, alpha.coerceIn(0f, 1f))
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 8, vertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(0)
    }

    fun release() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
