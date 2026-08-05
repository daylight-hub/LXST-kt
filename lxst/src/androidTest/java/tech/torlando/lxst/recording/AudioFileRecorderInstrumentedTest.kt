package tech.torlando.lxst.recording

import android.Manifest
import android.media.MediaExtractor
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioFileRecorderInstrumentedTest {
    @get:Rule
    val microphonePermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun recordsAPlatformDecodableOggOpusFile() {
        assumeTrue("Ogg Opus requires API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keepArtifact =
            InstrumentationRegistry.getArguments().getString("keepArtifact")?.toBoolean() == true
        val outputDirectory =
            if (keepArtifact) checkNotNull(context.getExternalFilesDir(null)) else context.cacheDir
        val output = File(outputDirectory, "lxst-recorder-artifact.ogg")
        output.delete()
        val recorder = AudioFileRecorder(context)

        try {
            recorder.start(output)
            Thread.sleep(1_500)
            val result = recorder.stop()

            assertTrue(result.file.isFile)
            assertTrue(result.sizeBytes > 0L)
            assertEquals(AudioFileFormat.OGG_OPUS, result.format)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(result.file.absolutePath)
                assertTrue("Expected at least one media track", extractor.trackCount > 0)
                val mimeTypes =
                    (0 until extractor.trackCount).mapNotNull { index ->
                        extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                    }
                assertTrue("Expected an Opus track, found $mimeTypes", "audio/opus" in mimeTypes)
            } finally {
                extractor.release()
            }
        } finally {
            recorder.cancel()
            if (!keepArtifact) {
                output.delete()
            }
        }
    }
}
