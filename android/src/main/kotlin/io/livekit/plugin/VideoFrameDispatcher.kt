package io.livekit.plugin

import org.webrtc.VideoFrame
import java.util.concurrent.CopyOnWriteArrayList

object VideoFrameDispatcher {
    interface Sink {
        fun onFrame(frame: VideoFrame)
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    fun addSink(sink: Sink) {
        sinks.add(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    fun dispatch(frame: VideoFrame) {
        for (sink in sinks) {
            sink.onFrame(frame)
        }
    }
}