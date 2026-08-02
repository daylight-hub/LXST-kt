/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class OboeLineSinkPrebufferTest {
    @Test
    fun `hybrid Oboe playback starts Codec2 profiles after one decoded packet`() {
        assertEquals(1, effectiveAutostartFrames(frameTimeMs = 400L))
        assertEquals(1, effectiveAutostartFrames(frameTimeMs = 320L))
        assertEquals(1, effectiveAutostartFrames(frameTimeMs = 200L))
    }

    @Test
    fun `hybrid Oboe playback retains short profile targets`() {
        assertEquals(8, effectiveAutostartFrames(frameTimeMs = 60L))
        assertEquals(50, effectiveAutostartFrames(frameTimeMs = 10L))
    }

    private fun effectiveAutostartFrames(frameTimeMs: Long): Int {
        val sink = OboeLineSink(autodigest = false)
        val update = OboeLineSink::class.java.getDeclaredMethod("updateBufferLimits", Long::class.javaPrimitiveType)
        update.isAccessible = true
        update.invoke(sink, frameTimeMs)

        val field = OboeLineSink::class.java.getDeclaredField("effectiveAutostartMin")
        field.isAccessible = true
        return field.getInt(sink)
    }
}
