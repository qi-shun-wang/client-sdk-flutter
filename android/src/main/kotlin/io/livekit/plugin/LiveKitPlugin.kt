/*
 * Copyright 2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.plugin

import android.content.Context
import android.content.Intent
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import com.cloudwebrtc.webrtc.FlutterWebRTCPlugin
import com.cloudwebrtc.webrtc.LocalTrack
import com.cloudwebrtc.webrtc.video.LocalVideoTrack

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.nio.ByteBuffer


/** LiveKitPlugin */

class LiveKitPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var binaryMessenger: BinaryMessenger? = null

    private val flutterWebRTCPlugin = FlutterWebRTCPlugin.sharedSingleton
    private val videoFrameProcessor = VideoFrameProcessor()

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "livekit_client")
        channel.setMethodCallHandler(this)
        binaryMessenger = flutterPluginBinding.binaryMessenger


    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "startRecording" -> {
                val dir = call.argument<String>("outputDir")
                if ( dir.isNullOrEmpty()) {
                    result.error("INVALID_ARGUMENT", "trackId and outputDir are required", null)
                    return
                }
                val intent = Intent(context, HlsRecorderService::class.java).apply {
                    putExtra("outputDir", dir)
                }
                ContextCompat.startForegroundService(context, intent)
                result.success(null)
            }
            "stopRecording" -> {
                context.stopService(Intent(context, HlsRecorderService::class.java))
                result.success(null)
            }

            "video_frame_processor_init" -> {
                val trackId = call.argument<String>("trackId")
                val track = flutterWebRTCPlugin.getLocalTrack(trackId)

                if (track is LocalVideoTrack) {
                    track.addProcessor(videoFrameProcessor)
                    result.success(null)
                } else {
                    result.error("trackNotFound", "Video track not found for ID: $trackId", null)
                }
            }

            else -> result.notImplemented()
        }
    }
}
 