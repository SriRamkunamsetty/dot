package com.dj.dot.playback

import androidx.media3.common.C
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Beat detection, tempo and band levels on synthetic audio, with a clock that follows the samples. */
class SpectrumTest {
    private val rate = 44100
    private var now = 0L
    private fun spectrum() = Spectrum(clock = { now }).apply {
        acquire(needsWave = false)
        flush(rate, 2, C.ENCODING_PCM_16BIT)
    }

    /** Feeds [seconds] of [sample] in 1024-frame chunks, advancing the clock with the audio. */
    private fun feed(s: Spectrum, seconds: Double, sample: (Double) -> Double) {
        val chunk = 1024
        var frame = 0
        val total = (seconds * rate).toInt()
        while (frame < total) {
            val buf = ByteBuffer.allocateDirect(chunk * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until chunk) {
                val v = (sample((frame + i).toDouble() / rate).coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
                buf.putShort(v); buf.putShort(v)
            }
            buf.flip()
            frame += chunk
            now += chunk * 1_000_000_000L / rate
            s.handleBuffer(buf)
        }
    }

    /** A kick drum every [period] seconds over a quiet hiss. */
    private fun kicks(period: Double): (Double) -> Double {
        val noise = java.util.Random(1)
        return { t ->
            val since = t % period
            0.9 * exp(-since * 28) * sin(2 * PI * (55 + 90 * exp(-since * 40)) * since) + 0.02 * (noise.nextDouble() - 0.5)
        }
    }

    @Test fun `finds 120 bpm in a steady kick pattern`() {
        val s = spectrum()
        feed(s, 14.0, kicks(0.5))
        assertTrue("bpm was ${s.bpm}", s.bpm in 116..124)
    }

    @Test fun `finds 90 bpm too`() {
        val s = spectrum()
        feed(s, 16.0, kicks(60.0 / 90))
        assertTrue("bpm was ${s.bpm}", s.bpm in 87..93)
    }

    @Test fun `reports beats in the window they happened`() {
        val s = spectrum()
        feed(s, 6.0, kicks(0.5))
        val since = now - 2_000_000_000L
        assertTrue(s.beatBetween(since, now) > 0f)
    }

    @Test fun `a low tone lights the low bands, not the high ones`() {
        val s = spectrum()
        feed(s, 1.0) { t -> 0.6 * sin(2 * PI * 80 * t) }
        val levels = s.levelsAt(now, FloatArray(s.bands))!!
        assertTrue(levels.take(4).max() > levels.takeLast(4).max() + 0.3f)
    }

    @Test fun `does no work while nobody listens`() {
        val s = Spectrum(clock = { now }).apply { flush(rate, 2, C.ENCODING_PCM_16BIT) }
        feed(s, 2.0, kicks(0.5))
        assertTrue(s.levelsAt(now, FloatArray(s.bands)) == null && s.bpm == 0)
    }

    private fun feedAtRate(s: Spectrum, sampleRate: Int, seconds: Double, sample: (Double) -> Double) {
        val chunk = 1024
        var frame = 0
        val total = (seconds * sampleRate).toInt()
        while (frame < total) {
            val buf = ByteBuffer.allocateDirect(chunk * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until chunk) {
                val v = (sample((frame + i).toDouble() / sampleRate).coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
                buf.putShort(v); buf.putShort(v)
            }
            buf.flip()
            frame += chunk
            now += chunk * 1_000_000_000L / sampleRate
            s.handleBuffer(buf)
        }
    }

    @Test fun `bpm and visualizer behave consistently across 44_1, 48, 96, and 192 kHz`() {
        listOf(44100, 48000, 96000, 192000).forEach { testRate ->
            now = 0L
            val s = Spectrum(clock = { now }).apply {
                acquire(needsWave = false)
                flush(testRate, 2, C.ENCODING_PCM_16BIT)
            }
            feedAtRate(s, testRate, 14.0, kicks(0.5))
            assertTrue("bpm at $testRate Hz was ${s.bpm}", s.bpm in 116..124)

            feedAtRate(s, testRate, 1.0) { t -> 0.6 * sin(2 * PI * 80 * t) }
            val levels = s.levelsAt(now, FloatArray(s.bands))!!
            assertTrue("low bands light at $testRate Hz", levels.take(4).max() > levels.takeLast(4).max() + 0.3f)
        }
    }

    @Test fun `modifying destination buffer does not mutate internal spectrum state`() {
        val s = Spectrum(clock = { now }).apply {
            acquire(needsWave = true)
            flush(rate, 2, C.ENCODING_PCM_16BIT)
        }
        feed(s, 1.0) { t -> 0.5 * sin(2 * PI * 100 * t) }
        val levels1 = FloatArray(s.bands)
        s.levelsAt(now, levels1)
        val originalVal = levels1[0]
        levels1[0] = 999f
        val levels2 = FloatArray(s.bands)
        s.levelsAt(now, levels2)
        assertTrue("Modifying destination array must not mutate internal buffer", levels2[0] == originalVal)

        val wave1 = FloatArray(s.wavePoints)
        s.waveAt(now, wave1)
        val originalWaveVal = wave1[0]
        wave1[0] = 999f
        val wave2 = FloatArray(s.wavePoints)
        s.waveAt(now, wave2)
        assertTrue("Modifying destination wave must not mutate internal buffer", wave2[0] == originalWaveVal)
    }

    @Test fun `supports caller provided destination buffer`() {
        val s = Spectrum(clock = { now }).apply {
            acquire(needsWave = true)
            flush(rate, 2, C.ENCODING_PCM_16BIT)
        }
        feed(s, 1.0) { t -> 0.5 * sin(2 * PI * 100 * t) }
        val dest = FloatArray(s.bands)
        val result = s.levelsAt(now, dest)
        assertTrue(result === dest)
        assertTrue(dest.any { it > 0f })

        val waveDest = FloatArray(s.wavePoints)
        val waveResult = s.waveAt(now, waveDest)
        assertTrue(waveResult === waveDest)
    }

    @Test fun `concurrent reads and writes do not deadlock`() {
        val s = Spectrum(clock = { System.nanoTime() }).apply {
            acquire(needsWave = true)
            flush(rate, 2, C.ENCODING_PCM_16BIT)
        }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        val readers = (0 until 4).map {
            Thread {
                val out = FloatArray(s.bands)
                val waveOut = FloatArray(s.wavePoints)
                while (!stop.get()) {
                    try {
                        val t = System.nanoTime()
                        s.levelsAt(t, out)
                        s.waveAt(t, waveOut)
                        s.beatBetween(t - 100_000_000L, t)
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
        }
        readers.forEach { it.start() }

        val chunk = 1024
        val buf = ByteBuffer.allocateDirect(chunk * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until chunk) {
            buf.putShort(1000.toShort()); buf.putShort(1000.toShort())
        }
        repeat(50) {
            buf.rewind()
            s.handleBuffer(buf)
        }

        stop.set(true)
        readers.forEach { it.join(1000) }
        assertTrue("No exceptions during concurrent reads/writes", errors.isEmpty())
    }
}
