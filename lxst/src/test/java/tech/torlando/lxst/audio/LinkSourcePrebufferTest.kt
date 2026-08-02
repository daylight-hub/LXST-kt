/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.torlando.lxst.core.PacketRouter

class LinkSourcePrebufferTest {
    @Test
    fun `ULBW native playback starts after one 400ms packet`() {
        val prebufferFrames = LinkSource.computePrebufferFrames(frameTimeMs = 400)

        assertEquals(
            "ULBW must not retain the five-frame two-second startup floor",
            1,
            prebufferFrames,
        )
    }

    @Test
    fun `VLBW and LBW native playback start after one packet`() {
        assertEquals(1, LinkSource.computePrebufferFrames(frameTimeMs = 320))
        assertEquals(1, LinkSource.computePrebufferFrames(frameTimeMs = 200))
    }

    @Test
    fun `short native profiles retain their existing prebuffer targets`() {
        assertEquals(7, LinkSource.computePrebufferFrames(frameTimeMs = 60))
        assertEquals(45, LinkSource.computePrebufferFrames(frameTimeMs = 10))
    }

    @Test
    fun `native profile reconfiguration refreshes cached prebuffer`() {
        val source = LinkSource(bridge = mockk<PacketRouter>(relaxed = true))
        source.prebufferFrames = 1
        var configuredPrebuffer = -1

        source.reconfigureNativePlayback(frameTimeMs = 60) { prebufferFrames ->
            configuredPrebuffer = prebufferFrames
        }

        assertEquals(7, source.prebufferFrames)
        assertEquals(7, configuredPrebuffer)
        source.shutdown()
    }
}
