package app.pimobile.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.wav.WavExtractor

/**
 * Extractors for a `wav_stream` TTS response, whose length is unknown until it ends. A
 * streaming WAV header declares the largest data size (0xFFFFFFFF bytes: 24.8 hours at
 * 24 kHz), which [WavExtractor] turns into the stream's duration. ExoPlayer ends a period
 * only once the position reaches that duration, so after the real audio the player kept
 * "playing" silence, its service in the foreground and its playback thread busy. An unknown
 * duration ends playback when the audio does.
 */
@OptIn(UnstableApi::class)
object StreamedWavExtractors : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(
        object : ForwardingExtractor(WavExtractor()) {
            override fun init(output: ExtractorOutput) = super.init(
                object : ForwardingExtractorOutput(output) {
                    override fun seekMap(seekMap: SeekMap) = super.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
                },
            )
        },
    )
}
