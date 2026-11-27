package com.studiopocket.core.video

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renderer program OpenGL ES 3.0 untuk tekstur [GL_TEXTURE_EXTERNAL_OES] (kamera & UVC),
 * dengan potongan kotak, sudut membulat, atau bulat.
 */
class TextureProgram {
    private var program = 0
    private var maPositionLoc = 0
    private var maTextureCoordLoc = 0
    private var muSTMatrixLoc = 0
    private var muTextureLoc = 0
    private var muShapeLoc = 0
    private var muCornerLoc = 0
    private var muAspectLoc = 0

    private val vertexBuffer: FloatBuffer

    init {
        val vertexShaderSource = """
            #version 300 es
            in vec4 aPosition;
            in vec4 aTextureCoord;
            uniform mat4 uSTMatrix;
            out vec2 vTextureCoord;
            out vec2 vQuad;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vTextureCoord = (uSTMatrix * vec4(aTextureCoord.xy, 0.0, 1.0)).xy;
                vQuad = aPosition.xy * 0.5 + 0.5;
            }
        """.trimIndent()

        val fragmentShaderSource = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTextureCoord;
            in vec2 vQuad;
            uniform samplerExternalOES sTexture;
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
                fragColor = texture(sTexture, vTextureCoord);
            }
        """.trimIndent()

        program = GlShader.createProgram(vertexShaderSource, fragmentShaderSource)
        maPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        maTextureCoordLoc = GLES30.glGetAttribLocation(program, "aTextureCoord")
        muSTMatrixLoc = GLES30.glGetUniformLocation(program, "uSTMatrix")
        muTextureLoc = GLES30.glGetUniformLocation(program, "sTexture")
        muShapeLoc = GLES30.glGetUniformLocation(program, "uShape")
        muCornerLoc = GLES30.glGetUniformLocation(program, "uCorner")
        muAspectLoc = GLES30.glGetUniformLocation(program, "uAspect")

        vertexBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    fun draw(
        textureId: Int,
        stMatrix: FloatArray,
        rectData: FloatArray,
        shape: Int = 0,
        corner: Float = 0.12f,
        aspect: Float = 1f,
    ) {
        GLES30.glUseProgram(program)

        vertexBuffer.clear()
        vertexBuffer.put(rectData)
        vertexBuffer.position(0)

        GLES30.glEnableVertexAttribArray(maPositionLoc)
        GLES30.glVertexAttribPointer(maPositionLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(maTextureCoordLoc)
        GLES30.glVertexAttribPointer(maTextureCoordLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        GLES30.glUniformMatrix4fv(muSTMatrixLoc, 1, false, stMatrix, 0)
        GLES30.glUniform1i(muShapeLoc, shape)
        GLES30.glUniform1f(muCornerLoc, corner)
        GLES30.glUniform1f(muAspectLoc, if (aspect <= 0f) 1f else aspect)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(muTextureLoc, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(maPositionLoc)
        GLES30.glDisableVertexAttribArray(maTextureCoordLoc)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES30.glUseProgram(0)
    }

    fun release() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
