/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package tech.torlando.lxst.audio

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
