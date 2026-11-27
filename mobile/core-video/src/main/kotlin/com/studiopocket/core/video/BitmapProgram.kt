package com.studiopocket.core.video

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Tekstur RGBA 2D untuk overlay gambar dan teks yang diunggah sekali, bukan per frame. */
class BitmapProgram {
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexture = 0
    private var uShape = 0
    private var uCorner = 0
    private var uAspect = 0
    private val vertexBuffer: FloatBuffer

    init {
        val vs = """
            #version 300 es
            in vec4 aPosition;
            in vec4 aTextureCoord;
            out vec2 vTex;
            out vec2 vQuad;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vTex = aTextureCoord.xy;
                vQuad = aPosition.xy * 0.5 + 0.5;
            }
        """.trimIndent()
        val fs = """
            #version 300 es
            precision mediump float;
            in vec2 vTex;
            in vec2 vQuad;
            uniform sampler2D sTexture;
            uniform int uShape;
            uniform float uCorner;
            uniform float uAspect;
            out vec4 fragColor;

            float roundedBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            void main() {
                vec2 p = (vQuad - 0.5) * vec2(uAspect, 1.0);
                if (uShape == 1) {
                    float r = max(0.001, uCorner * min(uAspect, 1.0));
                    if (roundedBox(p, vec2(0.5 * uAspect, 0.5), r) > 0.0) discard;
                } else if (uShape == 2) {
                    if (length(p) > 0.5) discard;
                }
                vec4 c = texture(sTexture, vTex);
                if (c.a < 0.01) discard;
                fragColor = c;
            }
        """.trimIndent()
        program = GlShader.createProgram(vs, fs)
        aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES30.glGetAttribLocation(program, "aTextureCoord")
        uTexture = GLES30.glGetUniformLocation(program, "sTexture")
        uShape = GLES30.glGetUniformLocation(program, "uShape")
        uCorner = GLES30.glGetUniformLocation(program, "uCorner")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        vertexBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    fun draw(textureId: Int, rectData: FloatArray, shape: Int, corner: Float, aspect: Float) {
        GLES30.glUseProgram(program)
        vertexBuffer.clear()
        vertexBuffer.put(rectData)
        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glUniform1i(uShape, shape)
        GLES30.glUniform1f(uCorner, corner)
        GLES30.glUniform1f(uAspect, if (aspect <= 0f) 1f else aspect)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glUseProgram(0)
    }

    fun release() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
