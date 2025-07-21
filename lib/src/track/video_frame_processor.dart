import 'package:flutter/services.dart' show MethodChannel;
import 'package:livekit_client/livekit_client.dart';
import 'package:webrtc_interface/src/media_stream_track.dart';

class VideoFrameProcessor extends TrackProcessor<VideoProcessorOptions> {
  static const MethodChannel methodChannel = MethodChannel('livekit_client');
  final String methodPrefix = 'video_frame_processor';
  ProcessorOptions<TrackType>? currentOptions;

  @override
  Future<void> destroy() async {
    await methodChannel.invokeMethod('${methodPrefix}_destroy');
  }

  @override
  Future<void> init(ProcessorOptions<TrackType> options) async {
    currentOptions = options;
    await methodChannel.invokeMethod('${methodPrefix}_init', {'trackId': options.track.id});
  }

  @override
  String get name => 'LivekiVideoFilter';

  @override
  Future<void> onPublish(Room room) async {
    await methodChannel.invokeMethod('${methodPrefix}_onPublish');
  }

  @override
  Future<void> onUnpublish() async {
    await methodChannel.invokeMethod('${methodPrefix}_onUnpublish');
  }

  @override
  Future<void> restart(ProcessorOptions<TrackType> options) async {
    await methodChannel.invokeMethod('${methodPrefix}_restart', {'trackId': options.track.id});
  }

  @override
  MediaStreamTrack? get processedTrack {
    return currentOptions?.track;
  }
}

class FlutterDashRecorderService {
  static const MethodChannel channel = MethodChannel('livekit_client');

  static Future<void> startRecording({
    required String outputDir,
  }) async {
    await channel.invokeMethod('startRecording', {'outputDir': outputDir});
  }

  static Future<void> stopRecording() async {
    await channel.invokeMethod('stopRecording');
  }
}
