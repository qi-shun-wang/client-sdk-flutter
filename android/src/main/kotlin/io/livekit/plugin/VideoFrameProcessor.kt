package io.livekit.plugin

import com.cloudwebrtc.webrtc.video.LocalVideoTrack
import io.flutter.Log
import org.webrtc.VideoFrame

class VideoFrameProcessor : LocalVideoTrack.ExternalVideoFrameProcessing { 
    override fun onFrame(frame: VideoFrame?): VideoFrame? {
        frame ?: return null
        // Forward frame to native HLS recorder
        VideoFrameDispatcher.dispatch(frame)

        return frame // Must return the frame to continue rendering
    }
}