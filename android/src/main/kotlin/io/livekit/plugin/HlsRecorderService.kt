package io.livekit.plugin


import android.app.*
import android.content.Intent
import android.media.*
import android.media.AudioFormat
import android.os.*
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import io.flutter.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoFrame.I420Buffer
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.BlockingQueue
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.io.File
import com.arthenica.ffmpegkit.SessionState


class HlsRecorderService : Service(), VideoFrameDispatcher.Sink {

    private var ffmpegSession: Session? = null
    private var videoPipeOutput: OutputStream? = null
    private var audioPipeOutput: OutputStream? = null

    private var videoPipeInputFd: ParcelFileDescriptor? = null
    private var audioPipeInputFd: ParcelFileDescriptor? = null

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)

    private lateinit var videoHandler: Handler
    private lateinit var audioHandler: Handler

    private lateinit var videoThread: HandlerThread
    private lateinit var audioThread: HandlerThread

    private var outputDir: String = ""

    private var videoWidth = -1
    private var videoHeight = -1
    private var initialized = false

    private var videoFps = 6
    private var videoBitrate = 1_000_000 // 1 Mbps

    // Pool of reusable YUV buffers for frame copying (fixed size)
    private val yuvPool: BlockingQueue<TimestampedYuvFrame> = LinkedBlockingQueue()

    // Bounded queue for frames ready to write to FFmpeg pipe (fixed capacity)
    private val writeQueue: LinkedBlockingQueue<TimestampedYuvFrame> = LinkedBlockingQueue(30)

    override fun onCreate() {
        super.onCreate()
        startForeground(101, buildNotification())

        videoThread = HandlerThread("VideoWriteThread", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        audioThread = HandlerThread("AudioWriteThread", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        videoHandler = Handler(videoThread.looper)
        audioHandler = Handler(audioThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        outputDir = intent.getStringExtra("outputDir") ?: return START_NOT_STICKY

        // Start video sink early to catch frames ASAP
        VideoFrameDispatcher.addSink(this)

        // Audio recording will start after FFmpeg launch

        return START_STICKY
    }

    override fun onDestroy() {
        VideoFrameDispatcher.removeSink(this)
        cleanupResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val chanId = "hls_recorder_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, "HLS Recorder", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
        return NotificationCompat.Builder(this, chanId)
            .setContentTitle("Recording LiveKit Track")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun launchFFmpeg() {
        try {
            val videoPipe = ParcelFileDescriptor.createPipe()
            val audioPipe = ParcelFileDescriptor.createPipe()

            videoPipeInputFd = videoPipe[0]
            videoPipeOutput = ParcelFileDescriptor.AutoCloseOutputStream(videoPipe[1])

            audioPipeInputFd = audioPipe[0]
            audioPipeOutput = ParcelFileDescriptor.AutoCloseOutputStream(audioPipe[1])

            val sessionDir = "$outputDir/session_${System.currentTimeMillis()}"
            File(sessionDir).mkdirs()
            val outputPath = "$sessionDir/manifest.mpd"

            val cmd = listOf(
                "-thread_queue_size", "1024",
                "-f", "rawvideo",
                "-pixel_format", "yuv420p",
                "-video_size", "${videoWidth}x$videoHeight",
                "-framerate", "$videoFps",
                "-i", "pipe:${videoPipeInputFd!!.fd}",

                "-thread_queue_size", "1024",
                "-f", "s16le",
                "-ar", "48000",
                "-ac", "1",
                "-i", "pipe:${audioPipeInputFd!!.fd}",

                "-c:v", "libvpx-vp9",
                "-b:v", "$videoBitrate",
                "-deadline", "realtime",
                "-cpu-used", "4",
                "-tile-columns", "2",
                "-frame-parallel", "1",

                "-c:a", "libopus",
                "-b:a", "128k",

                "-fps_mode", "vfr",

                "-f", "dash",
                "-use_template", "1",
                "-use_timeline", "1",
                "-seg_duration", "4",
                outputPath
            )

            ffmpegSession = FFmpegKit.executeAsync(cmd.joinToString(" ")) { session ->
                val returnCode = session.returnCode
                val logs = session.allLogsAsString
                Log.i("FFMPEG", "FFmpeg finished with code: $returnCode")
                Log.i("FFMPEG", "FFmpeg logs:\n$logs")
            }

            // Start audio recording after FFmpeg launched
            startAudioRecording()

            // Start video writing task to consume from writeQueue
            startVideoWriting()

        } catch (e: Exception) {
            Log.e("FFMPEG", "Failed to launch FFmpeg: ${e.message}")
        }
    }

    private fun startAudioRecording() {
        val sampleRate = 48000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e("AUDIO", "Invalid buffer size returned from getMinBufferSize")
            return
        }

        val bufferSize = minBufferSize * 4

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AUDIO", "AudioRecord initialization failed")
            return
        }

        isRecording.set(true)
        audioRecord?.startRecording()
        Log.i("AUDIO", "Audio recording started")

        audioHandler.post(object : Runnable {
            val buffer = ByteArray(bufferSize)
            override fun run() {
                if (!isRecording.get()) return
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    try {
                        audioPipeOutput?.write(buffer, 0, read)
                    } catch (e: Exception) {
                        Log.e("AUDIO", "Audio write error: ${e.message}")
                    }
                }
                if (!audioThread.isInterrupted && isRecording.get()) {
                    audioHandler.post(this)
                }
            }
        })
    }

    override fun onFrame(frame: VideoFrame) {
        val timestampNs = frame.timestampNs
        val buffer = frame.buffer.toI420()
        val i420Buffer = buffer as? I420Buffer ?: return

        val width = i420Buffer.width
        val height = i420Buffer.height

        if (!initialized) {
            // Determine scale/resolution for FFmpeg
            val maxWidth = 1280
            val maxHeight = 720
            val aspectRatio = width.toFloat() / height

            if (width > maxWidth || height > maxHeight) {
                if (aspectRatio >= 1) {
                    videoWidth = maxWidth
                    videoHeight = (maxWidth / aspectRatio).toInt()
                } else {
                    videoHeight = maxHeight
                    videoWidth = (maxHeight * aspectRatio).toInt()
                }
            } else {
                videoWidth = width
                videoHeight = height
            }

            // Allocate buffer pool
            repeat(60) {
                yuvPool.offer(TimestampedYuvFrame.alloc(videoWidth, videoHeight))
            }

            launchFFmpeg()  // now that video size is known, launch FFmpeg + start audio recording

            initialized = true
        } else if (width != videoWidth || height != videoHeight) {
            Log.w("VIDEO", "Resolution changed from ${videoWidth}x$videoHeight to ${width}x$height, restarting FFmpeg")

            cleanupResources()

            videoWidth = -1
            videoHeight = -1

            // Restart with updated resolution - reuse existing threads
            onStartCommand(null, 0, 0)
            return
        }

        val frameData = yuvPool.poll() ?: run {
            Log.w("VIDEO", "No YUV buffer available, dropping frame")
            buffer.release()
            return
        }

        // Copy frame planes into reusable buffers
        copyPlaneInto(i420Buffer.dataY, i420Buffer.strideY, videoWidth, videoHeight, frameData.y)
        copyPlaneInto(i420Buffer.dataU, i420Buffer.strideU, videoWidth / 2, videoHeight / 2, frameData.u)
        copyPlaneInto(i420Buffer.dataV, i420Buffer.strideV, videoWidth / 2, videoHeight / 2, frameData.v)

        frameData.timestampNs = timestampNs

        // Backpressure: offer frameData to bounded writeQueue, drop oldest if full
        var offered = writeQueue.offer(frameData)
        if (!offered) {
            // Drop oldest frame to make room
            val dropped = writeQueue.poll()
            if (dropped != null) {
                yuvPool.offer(dropped)
            }
            offered = writeQueue.offer(frameData)
            if (!offered) {
                Log.w("VIDEO", "Write queue full, dropping new frame")
                yuvPool.offer(frameData)
            }
        }

        buffer.release()
    }

    private fun copyPlaneInto(src: ByteBuffer, srcStride: Int, width: Int, height: Int, dest: ByteArray) {
        for (row in 0 until height) {
            src.position(row * srcStride)
            src.get(dest, row * width, width)
        }
    }

    private fun startVideoWriting() {
        videoHandler.post(object : Runnable {
            override fun run() {
                if (!initialized) return
                try {
                    val frame = writeQueue.poll()
                    if (frame != null) {
                        videoPipeOutput?.write(frame.y)
                        videoPipeOutput?.write(frame.u)
                        videoPipeOutput?.write(frame.v)
                        yuvPool.offer(frame)
                    }
                } catch (e: Exception) {
                    Log.e("VIDEO", "Error writing video frame: ${e.message}")
                }
                if (!videoThread.isInterrupted) {
                    videoHandler.postDelayed(this, (1000L / videoFps))
                }
            }
        })
    }

    private fun cleanupResources() {
        try {
            Log.i("CLEANUP", "Starting cleanup...")

            isRecording.set(false)

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Cancel FFmpeg session if active
            ffmpegSession?.let {
                Log.i("CLEANUP", "Cancelling FFmpeg session...")
                FFmpegKit.cancel(it.sessionId)

                // Wait for FFmpeg session to complete (max 5 seconds)
                val timeoutMs = 5000L
                val pollInterval = 100L
                var waited = 0L
                while (
                    it.state == SessionState.CREATED || it.state == SessionState.RUNNING
                ) {
                    if (waited >= timeoutMs) {
                        Log.w("CLEANUP", "FFmpeg session did not complete in time")
                        break
                    }
                    Thread.sleep(pollInterval)
                    waited += pollInterval
                }
                val returnCode = it.returnCode
                if (ReturnCode.isSuccess(returnCode)) {
                    Log.i("CLEANUP", "FFmpeg exited successfully.")
                } else {
                    Log.w("CLEANUP", "FFmpeg exited with failure or was cancelled.")
                }

                ffmpegSession = null
            }

            videoPipeOutput?.close()
            videoPipeOutput = null
            videoPipeInputFd?.close()
            videoPipeInputFd = null

            audioPipeOutput?.close()
            audioPipeOutput = null
            audioPipeInputFd?.close()
            audioPipeInputFd = null

            yuvPool.clear()
            writeQueue.clear()
            initialized = false

            Log.i("CLEANUP", "Cleanup completed")
        } catch (e: Exception) {
            Log.e("CLEANUP", "Error during cleanup: ${e.message}")
        }
    }

    private data class TimestampedYuvFrame(
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
        var timestampNs: Long
    ) {
        companion object {
            fun alloc(width: Int, height: Int): TimestampedYuvFrame {
                return TimestampedYuvFrame(
                    ByteArray(width * height),
                    ByteArray(width * height / 4),
                    ByteArray(width * height / 4),
                    0L
                )
            }
        }
    }
}
