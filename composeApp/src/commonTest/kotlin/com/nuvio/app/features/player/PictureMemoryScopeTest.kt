package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_zoom_help
import nuvio.composeapp.generated.resources.player_zoom_help_channel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P2 (W2 device pass): the live zoom panel said "Remembered for this series" — live pictures are
 * kept per CHANNEL (F28/B123), so the help line has to say "this channel" there.
 */
class PictureMemoryScopeTest {

    @Test
    fun livePictureIsRememberedPerChannel() {
        assertEquals(PictureMemoryScope.Channel, PlayerPreferencePolicy.pictureMemoryScope(isLive = true))
    }

    @Test
    fun vodPictureIsRememberedPerSeries() {
        assertEquals(PictureMemoryScope.Series, PlayerPreferencePolicy.pictureMemoryScope(isLive = false))
    }

    @Test
    fun zoomHelpNamesTheChannelOnLive() {
        assertEquals(Res.string.player_zoom_help_channel, videoZoomHelpText(PictureMemoryScope.Channel))
        assertEquals(Res.string.player_zoom_help, videoZoomHelpText(PictureMemoryScope.Series))
    }
}
