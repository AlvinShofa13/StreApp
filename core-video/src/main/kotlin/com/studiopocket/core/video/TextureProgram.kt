package com.studiopocket.core.video

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renderer program OpenGL ES 3.0 untuk tekstur [GL_TEXTURE_EXTERNAL_OES] (kamera & UVC).
 */
class TextureProgram {
    private var program = 0
    private var maPositionLoc = 0
    private var maTextureCoordLoc = 0
    private var muSTMatrixLoc = 0
    private var muTextureLoc = 0

    private val vertexBuffer: FloatBuffer

    init {
        val vertexShaderSource = """
            #version 300 es
            in vec4 aPosition;
            in vec4 aTextureCoord;
            uniform mat4 uSTMatrix;
            out vec2 vTextureCoord;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vTextureCoord = (uSTMatrix * vec4(aTextureCoord.xy, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fragmentShaderSource = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(sTexture, vTextureCoord);
            }
        """.trimIndent()

        program = GlShader.createProgram(vertexShaderSource, fragmentShaderSource)
        maPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        maTextureCoordLoc = GLES30.glGetAttribLocation(program, "aTextureCoord")
        muSTMatrixLoc = GLES30.glGetUniformLocation(program, "uSTMatrix")
        muTextureLoc = GLES30.glGetUniformLocation(program, "sTexture")

        vertexBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    fun draw(textureId: Int, stMatrix: FloatArray, rectData: FloatArray) {
        GLES30.glUseProgram(program)

        vertexBuffer.clear()
        vertexBuffer.put(rectData)
        vertexBuffer.position(0)

        // aPosition: offset 0, stride 16 (4 float)
        GLES30.glEnableVertexAttribArray(maPositionLoc)
        GLES30.glVertexAttribPointer(maPositionLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        // aTextureCoord: offset 2 float (8 byte), stride 16 (4 float)
        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(maTextureCoordLoc)
        GLES30.glVertexAttribPointer(maTextureCoordLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        GLES30.glUniformMatrix4fv(muSTMatrixLoc, 1, false, stMatrix, 0)

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
