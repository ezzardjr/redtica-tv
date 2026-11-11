package com.redtica.tv

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Desentrelazado por mezcla de líneas: cada línea se combina con la de arriba y la de abajo
 * (0.25 / 0.5 / 0.25). Quita las "rayitas" (peine) en movimiento de las señales 1080i/480i.
 */
@UnstableApi
class DeinterlaceEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    private class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val prog = GlProgram(VS, FS)
        private var texelH = 0f

        init {
            prog.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texelH = 1f / inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            prog.use()
            prog.setSamplerTexIdUniform("uTex", inputTexId, 0)
            prog.setFloatUniform("uTexelH", texelH)
            prog.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            prog.delete()
        }
    }

    companion object {
        private const val VS = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}"""
        private const val FS = """
precision mediump float;
uniform sampler2D uTex;
uniform float uTexelH;
varying vec2 vUv;
void main() {
  vec4 a = texture2D(uTex, vUv - vec2(0.0, uTexelH));
  vec4 b = texture2D(uTex, vUv);
  vec4 c = texture2D(uTex, vUv + vec2(0.0, uTexelH));
  gl_FragColor = a * 0.25 + b * 0.5 + c * 0.25;
}"""
    }
}
