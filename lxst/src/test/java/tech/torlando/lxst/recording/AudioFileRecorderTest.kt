package tech.torlando.lxst.recording

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioFileRecorderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stop publishes only a finalized non-empty recording`() = runTest {
        val output = temporaryFolder.newFile("voice.ogg").also { it.delete() }
        lateinit var partial: java.io.File
        val backend = FakeRecorderBackend(onStop = { partial.writeBytes(byteArrayOf(1, 2, 3)) })
        val recorder =
            AudioFileRecorder(
                backendFactory = RecorderBackendFactory { file, _ -> partial = file; backend },
                sdkInt = { 35 },
                elapsedRealtimeMillis = sequenceOf(100L, 350L).iterator()::next,
            )

        recorder.start(output)
        val result = recorder.stop()

        assertEquals(output, result.file)
        assertEquals(3L, result.sizeBytes)
        assertEquals(250L, result.durationMillis)
        assertEquals(AudioFileFormat.OGG_OPUS, result.format)
        assertEquals(RecorderState.Completed(result), recorder.state.value)
        assertTrue(output.exists())
        assertFalse(partial.exists())
        assertEquals(1, backend.stopCalls)
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun `cancel releases once and removes the partial file`() {
        val output = temporaryFolder.newFile("cancelled.ogg").also { it.delete() }
        lateinit var partial: java.io.File
        val backend = FakeRecorderBackend(onStart = { partial.writeBytes(byteArrayOf(1)) })
        val recorder = recorderWith(backend) { partial = it }

        recorder.start(output)
        recorder.cancel()
        recorder.cancel()

        assertEquals(1, backend.releaseCalls)
        assertFalse(partial.exists())
        assertFalse(output.exists())
        assertEquals(RecorderState.Idle, recorder.state.value)
    }

    @Test
    fun `stop failure releases once deletes partial and reports failure`() {
        val output = temporaryFolder.newFile("failed.ogg").also { it.delete() }
        lateinit var partial: java.io.File
        val failure = IllegalStateException("too short")
        val backend =
            FakeRecorderBackend(
                onStart = { partial.writeBytes(byteArrayOf(1)) },
                onStop = { throw failure },
            )
        val recorder = recorderWith(backend) { partial = it }

        recorder.start(output)
        val thrown = runCatching { recorder.stop() }.exceptionOrNull()

        assertTrue(thrown is AudioRecordingException)
        assertEquals(failure, thrown?.cause)
        assertEquals(1, backend.releaseCalls)
        assertFalse(partial.exists())
        assertFalse(output.exists())
        assertTrue(recorder.state.value is RecorderState.Failed)
    }

    @Test
    fun `empty finalized output is rejected and backend is released once`() {
        val output = temporaryFolder.newFile("empty.ogg").also { it.delete() }
        val backend = FakeRecorderBackend()
        val recorder = recorderWith(backend)

        recorder.start(output)
        val thrown = runCatching { recorder.stop() }.exceptionOrNull()

        assertTrue(thrown is AudioRecordingException)
        assertEquals(1, backend.releaseCalls)
        assertFalse(output.exists())
    }

    @Test
    fun `unsupported platform fails before creating backend`() {
        val output = temporaryFolder.newFile("unsupported.ogg").also { it.delete() }
        var factoryCalls = 0
        val recorder =
            AudioFileRecorder(
                backendFactory = RecorderBackendFactory { _, _ ->
                    factoryCalls++
                    FakeRecorderBackend()
                },
                sdkInt = { 28 },
                elapsedRealtimeMillis = { 0L },
            )

        val thrown = runCatching { recorder.start(output) }.exceptionOrNull()

        assertTrue(thrown is UnsupportedAudioRecordingException)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `non-directory output parent fails before creating backend`() {
        val parentFile = temporaryFolder.newFile("not-a-directory")
        val output = java.io.File(parentFile, "voice.ogg")
        var factoryCalls = 0
        val recorder =
            AudioFileRecorder(
                backendFactory = RecorderBackendFactory { _, _ ->
                    factoryCalls++
                    FakeRecorderBackend()
                },
                sdkInt = { 35 },
                elapsedRealtimeMillis = { 0L },
            )

        val thrown = runCatching { recorder.start(output) }.exceptionOrNull()

        assertTrue(thrown is AudioRecordingException)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `duplicate start and stop before start are rejected`() {
        val first = temporaryFolder.newFile("first.ogg").also { it.delete() }
        val second = temporaryFolder.newFile("second.ogg").also { it.delete() }
        val backend = FakeRecorderBackend()
        val recorder = recorderWith(backend)

        assertTrue(runCatching { recorder.stop() }.exceptionOrNull() is IllegalStateException)
        recorder.start(first)
        assertTrue(runCatching { recorder.start(second) }.exceptionOrNull() is IllegalStateException)
        recorder.cancel()
    }

    @Test
    fun `concurrent recorders never share partial ownership or replace finalized output`() {
        val output = temporaryFolder.newFile("shared.ogg").also { it.delete() }
        lateinit var firstPartial: java.io.File
        lateinit var secondPartial: java.io.File
        val bothStopped = CountDownLatch(2)
        val firstBackend =
            FakeRecorderBackend(
                onStop = {
                    firstPartial.writeBytes(byteArrayOf(1))
                    bothStopped.countDown()
                    check(bothStopped.await(5, TimeUnit.SECONDS))
                },
            )
        val secondBackend =
            FakeRecorderBackend(
                onStop = {
                    secondPartial.writeBytes(byteArrayOf(2))
                    bothStopped.countDown()
                    check(bothStopped.await(5, TimeUnit.SECONDS))
                },
            )
        val firstRecorder = recorderWith(firstBackend) { firstPartial = it }
        val secondRecorder = recorderWith(secondBackend) { secondPartial = it }

        firstRecorder.start(output)
        secondRecorder.start(output)

        assertTrue(firstPartial != secondPartial)
        assertTrue(firstPartial.exists())
        assertTrue(secondPartial.exists())

        val executor = Executors.newFixedThreadPool(2)
        val results =
            try {
                listOf(
                    executor.submit<Result<RecordedAudio>> { runCatching { firstRecorder.stop() } },
                    executor.submit<Result<RecordedAudio>> { runCatching { secondRecorder.stop() } },
                ).map { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is AudioRecordingException })
        assertTrue(
            output.readBytes().let { it.contentEquals(byteArrayOf(1)) || it.contentEquals(byteArrayOf(2)) },
        )
        assertFalse(firstPartial.exists())
        assertFalse(secondPartial.exists())
    }

    private fun recorderWith(
        backend: RecorderBackend,
        capturePartial: (java.io.File) -> Unit = {},
    ) =
        AudioFileRecorder(
            backendFactory = RecorderBackendFactory { file, _ -> capturePartial(file); backend },
            sdkInt = { 35 },
            elapsedRealtimeMillis = { 100L },
        )

    private class FakeRecorderBackend(
        private val onStart: () -> Unit = {},
        private val onStop: () -> Unit = {},
    ) : RecorderBackend {
        var startCalls = 0
        var stopCalls = 0
        var releaseCalls = 0

        override fun start() {
            startCalls++
            onStart()
        }

        override fun stop() {
            stopCalls++
            onStop()
        }

        override fun release() {
            releaseCalls++
        }
    }
}
