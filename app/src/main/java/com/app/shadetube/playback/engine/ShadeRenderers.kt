package com.app.shadetube.playback.engine

import androidx.annotation.OptIn
import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource

/** "t<tag>": the media id of every item the engine hands to ExoPlayer. */
internal fun tagOfMediaId(mediaId: String?): Long =
    if (mediaId != null && mediaId.startsWith("t")) mediaId.substring(1).toLongOrNull() ?: 0 else 0

internal fun mediaIdOf(tag: Long) = "t$tag"

/**
 * One deck's renderers: audio only, through our DSP ([ShadeProcessorChain] in a DefaultAudioSink); no video, text,
 * metadata-to-UI beyond ExoPlayer's own (ICY titles still arrive through Player.Listener.onMetadata).
 */
@OptIn(UnstableApi::class)
class ShadeRenderersFactory(
    context: Context,
    private val processor: ShadeAudioProcessor,
) : DefaultRenderersFactory(context) {
    private val appContext = context.applicationContext

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(ShadeProcessorChain(processor))
            .setEnableFloatOutput(false)
            .build()

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            ShadeAudioRenderer(
                appContext, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
                audioSink, processor,
            ),
        )
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) = Unit

    override fun buildTextRenderers(
        context: Context,
        output: androidx.media3.exoplayer.text.TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) = Unit

    override fun buildCameraMotionRenderers(context: Context, extensionRendererMode: Int, out: ArrayList<Renderer>) = Unit

    override fun buildImageRenderers(context: Context, out: ArrayList<Renderer>) = Unit
}

/**
 * MediaCodecAudioRenderer that tells our processor which item's audio it is fed: the sink's processors run ahead of
 * the audible position, so the per-item loudness gain must switch exactly where the stream changes in the data, not
 * when the player reports the transition. Streams are identified by their output offset (recorded with their media
 * period in onStreamChanged, looked up when the renderer switches its output stream).
 */
@OptIn(UnstableApi::class)
class ShadeAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    private val processor: ShadeAudioProcessor,
) : MediaCodecAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) {
    private class StreamInfo(val tag: Long, val startMs: Long)

    private val streams = LinkedHashMap<Long, StreamInfo>()
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    override fun onStreamChanged(formats: Array<out Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        val tag = runCatching {
            val tl = timeline
            val idx = tl.getPeriodByUid(mediaPeriodId.periodUid, period).windowIndex
            tagOfMediaId(tl.getWindow(idx, window).mediaItem.mediaId)
        }.getOrDefault(0L)
        streams[offsetUs] = StreamInfo(tag, maxOf(0L, (startPositionUs - offsetUs) / 1000))
        while (streams.size > 8) streams.remove(streams.keys.first())
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun onOutputStreamOffsetUsChanged(outputStreamOffsetUs: Long) {
        super.onOutputStreamOffsetUsChanged(outputStreamOffsetUs)
        streams[outputStreamOffsetUs]?.let { processor.setActiveItem(it.tag, it.startMs) }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        processor.onPositionReset(maxOf(0L, (positionUs - outputStreamOffsetUs) / 1000))
    }
}
