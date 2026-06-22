package com.libertyclerk.allstarslive.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the composite: the decoded video (an external-OES texture from a
 * SurfaceTexture) filling the frame, then an optional scorebug overlay (a normal
 * 2D texture) blended on top. Both use a single full-screen quad.
 *
 * Must be constructed and used on the thread that owns the EGL context.
 */
class GlScene {

    private val quad: FloatBuffer = floatBuffer(
        // x, y,  u, v   (triangle strip; tex v flipped so bitmaps aren't upside down)
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    private val oesProgram = Program(VERTEX, FRAGMENT_OES)
    private val texProgram = Program(VERTEX, FRAGMENT_2D)

    /** Create an external-OES texture id for the decoder's SurfaceTexture. */
    fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    /** A 2D texture for the overlay; upload/replace its pixels with [updateOverlay]. */
    fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun updateOverlay(texId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /** Draw the video texture full-frame, applying the SurfaceTexture transform. */
    fun drawVideo(oesTexId: Int, texMatrix: FloatArray) {
        GLES20.glDisable(GLES20.GL_BLEND)
        oesProgram.use()
        oesProgram.setTexMatrix(texMatrix)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        oesProgram.bindQuadAndDraw(quad)
    }

    /** Blend the overlay texture (alpha-premultiplied Bitmap) on top, full-frame. */
    fun drawOverlay(texId: Int) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        texProgram.use()
        // The shared quad's v is set up for the OES video (whose SurfaceTexture matrix
        // re-flips it). A plain Bitmap has no such matrix, so flip v here or it draws
        // upside-down. FLIP_V maps v -> 1-v.
        texProgram.setTexMatrix(FLIP_V)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        texProgram.bindQuadAndDraw(quad)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private class Program(vertexSrc: String, fragmentSrc: String) {
        private val id = link(vertexSrc, fragmentSrc)
        private val aPosition = GLES20.glGetAttribLocation(id, "aPosition")
        private val aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord")
        private val uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix")
        private val uTexture = GLES20.glGetUniformLocation(id, "sTexture")

        fun use() {
            GLES20.glUseProgram(id)
            GLES20.glUniform1i(uTexture, 0)
        }

        fun setTexMatrix(m: FloatArray) = GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, m, 0)

        fun bindQuadAndDraw(quad: FloatBuffer) {
            quad.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)
        }
    }

    companion object {
        private val IDENTITY = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

        // v -> 1 - v, so a normal top-down Bitmap isn't drawn upside-down.
        private val FLIP_V = FloatArray(16).also {
            android.opengl.Matrix.setIdentityM(it, 0)
            android.opengl.Matrix.translateM(it, 0, 0f, 1f, 0f)
            android.opengl.Matrix.scaleM(it, 0, 1f, -1f, 1f)
        }

        private const val VERTEX = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """

        private const val FRAGMENT_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """

        private fun floatBuffer(vararg data: Float): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(data); position(0) }

        private fun link(vertexSrc: String, fragmentSrc: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return program
        }

        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
    }
}
