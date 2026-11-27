package com.studiopocket.core.video

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renderer program OpenGL ES 3.0 untuk pola uji (SMPTE Color Bars + indikator animasi).
 * Murni di-render di GPU tanpa alokasi Bitmap / ByteArray CPU.
 */
class ColorBarProgram {
    private var program = 0
    private var maPositionLoc = 0
    private var maTexCoordLoc = 0
    private var muTimeLoc = 0

    private val vertexBuffer: FloatBuffer

    init {
        val vertexShaderSource = """
            #version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragmentShaderSource = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform float uTime;
            out vec4 fragColor;
            void main() {
                float u = vTexCoord.x;
                float v = vTexCoord.y;
                vec3 col = vec3(0.0);
                
                // Color bars (7 baris warna)
                if (u < 0.142) col = vec3(0.75, 0.75, 0.75);      // Light Gray
                else if (u < 0.285) col = vec3(0.75, 0.75, 0.0); // Yellow
                else if (u < 0.428) col = vec3(0.0, 0.75, 0.75); // Cyan
                else if (u < 0.571) col = vec3(0.0, 0.75, 0.0);  // Green
                else if (u < 0.714) col = vec3(0.75, 0.0, 0.75); // Magenta
                else if (u < 0.857) col = vec3(0.75, 0.0, 0.0);  // Red
                else col = vec3(0.0, 0.0, 0.75);                 // Blue
                
                // Indikator bergerak untuk verifikasi fps aktual
                float pos = fract(uTime * 0.5);
                if (v > 0.85 && abs(u - pos) < 0.03) {
                    col = vec3(1.0, 1.0, 1.0);
                }
                
                fragColor = vec4(col, 1.0);
            }
        """.trimIndent()

        program = GlShader.createProgram(vertexShaderSource, fragmentShaderSource)
        maPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        maTexCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        muTimeLoc = GLES30.glGetUniformLocation(program, "uTime")

        vertexBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    fun draw(timeSec: Float, rectData: FloatArray) {
        GLES30.glUseProgram(program)

        vertexBuffer.clear()
        vertexBuffer.put(rectData)
        vertexBuffer.position(0)

        // aPosition: offset 0, stride 16 (4 float)
        GLES30.glEnableVertexAttribArray(maPositionLoc)
        GLES30.glVertexAttribPointer(maPositionLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        // aTexCoord: offset 2 float (8 byte), stride 16 (4 float)
        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(maTexCoordLoc)
        GLES30.glVertexAttribPointer(maTexCoordLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

        GLES30.glUniform1f(muTimeLoc, timeSec)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(maPositionLoc)
        GLES30.glDisableVertexAttribArray(maTexCoordLoc)
        GLES30.glUseProgram(0)
    }

    fun release() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }
}
