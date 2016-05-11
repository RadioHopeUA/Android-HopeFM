package ua.hope.radio.hopefm;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecUtil;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.audio.AudioCapabilitiesReceiver;
import com.google.android.exoplayer.drm.UnsupportedDrmException;
import com.google.android.exoplayer.metadata.id3.GeobFrame;
import com.google.android.exoplayer.metadata.id3.Id3Frame;
import com.google.android.exoplayer.metadata.id3.PrivFrame;
import com.google.android.exoplayer.metadata.id3.TxxxFrame;
import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.exoplayer.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by Vitalii Cherniak on 25.02.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMService extends Service implements HopeFMPlayer.Listener, HopeFMPlayer.Id3MetadataListener,
        AudioCapabilitiesReceiver.Listener, IHopeFMService {
    private static final String TAG = "HopeFMService";
    private final IBinder musicBind = new MusicBinder();
    private ScheduledFuture mScheduledTask;
    private UpdateTrackRunnable updateTrackRunnable;
    private ScheduledThreadPoolExecutor exec;
    private Handler mCurrentTrackHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            String resp = msg.obj.toString();
            String[] splitted = resp.split(" - ");
            if (splitted.length == 2) {
                if (callback != null) {
                    callback.updateSongInfo(splitted[0], splitted[1]);
                }
            }
            return true;
        }
    });

    private HopeFMPlayer player;
    private boolean playerNeedsPrepare;
    private EventLogger eventLogger;

    private AudioCapabilitiesReceiver audioCapabilitiesReceiver;

    private ArrayList<String> tracks = new ArrayList<>();
    private IHopeFMServiceCallback callback;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return musicBind;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioCapabilitiesReceiver = new AudioCapabilitiesReceiver(this, this);
        audioCapabilitiesReceiver.register();
        updateTrackRunnable = new UpdateTrackRunnable(mCurrentTrackHandler, getString(R.string.radio_info_url));
        exec = new ScheduledThreadPoolExecutor(1);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        audioCapabilitiesReceiver.unregister();
        stop();
    }

    private void startTrackInfoScheduler() {
        mScheduledTask = exec.scheduleAtFixedRate(updateTrackRunnable, 0, 5, TimeUnit.SECONDS);
    }

    private void stopTrackInfoScheduler() {
        mScheduledTask.cancel(true);
        exec.remove(updateTrackRunnable);
        exec.purge();
    }

    @Override
    public void play() {
        preparePlayer(true);
        startTrackInfoScheduler();
    }

    @Override
    public void stop() {
        tracks.clear();
        releasePlayer();
        stopTrackInfoScheduler();
    }

    @Override
    public boolean isPlaying() {
        return player != null && player.getPlayerControl().isPlaying();
    }

    @Override
    public void setSelectedTrack(int id) {
        if (player != null) {
            player.setSelectedTrack(HopeFMPlayer.TYPE_AUDIO, id);
        }
    }

    @Override
    public int getSelectedTrack() {
        if (player == null) {
            return 0;
        } else {
            return player.getSelectedTrack(HopeFMPlayer.TYPE_AUDIO);
        }
    }

    public int getTrackCount() {
        if (player ==null) {
            return 0;
        } else {
            return player.getTrackCount(HopeFMPlayer.TYPE_AUDIO);
        }
    }

    public MediaFormat getTrackFormat(int id) {
        if (player == null) {
            return null;
        } else {
            return player.getTrackFormat(HopeFMPlayer.TYPE_AUDIO, id);
        }
    }

    @Override
    public void registerCallback(IHopeFMServiceCallback callback) {
        this.callback = callback;
        //update UI
        if (callback != null) {
            callback.updateTracks(tracks, getSelectedTrack());
        }
    }

    // AudioCapabilitiesReceiver.Listener methods

    @Override
    public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
        if (player == null) {
            return;
        }
        boolean playWhenReady = player.getPlayWhenReady();
        releasePlayer();
        preparePlayer(playWhenReady);
    }

    // Internal methods

    private HopeFMPlayer.RendererBuilder getRendererBuilder() {
        String userAgent = Util.getUserAgent(this, TAG);
        return new HlsRendererBuilder(this, userAgent, getString(R.string.radio_stream_url));

    }

    private void preparePlayer(boolean playWhenReady) {
        if (player == null) {
            player = new HopeFMPlayer(getRendererBuilder());
            player.addListener(this);
            player.setMetadataListener(this);
            playerNeedsPrepare = true;
            eventLogger = new EventLogger();
            eventLogger.startSession();
            player.addListener(eventLogger);
            player.setInfoListener(eventLogger);
            player.setInternalErrorListener(eventLogger);
        }
        if (playerNeedsPrepare) {
            player.prepare();
            playerNeedsPrepare = false;
        }
        player.setPlayWhenReady(playWhenReady);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
            eventLogger.endSession();
            eventLogger = null;
        }
    }

    // HopeFMPlayer.Listener implementation
    @Override
    public void onStateChanged(boolean playWhenReady, int playbackState) {
        String text = "";
        switch (playbackState) {
            case ExoPlayer.STATE_BUFFERING:
                text = "buffering";
                break;
            case ExoPlayer.STATE_PREPARING:
                text = "preparing";
                break;
            case ExoPlayer.STATE_READY:
                tracks.clear();
                for (int i=0; i<getTrackCount(); i++) {
                    tracks.add(buildTrackName(getTrackFormat(i)));
                }
                if (callback != null) {
                    callback.updateTracks(tracks, getSelectedTrack());
                }
                break;
            default:
                text = "";
                break;
        }
        if (callback != null) {
            callback.updateStatus(text);
        }
    }

    @Override
    public void onError(Exception e) {
        String errorString = null;
        if (e instanceof UnsupportedDrmException) {
            // Special case DRM failures.
            UnsupportedDrmException unsupportedDrmException = (UnsupportedDrmException) e;
            errorString = getString(Util.SDK_INT < 18 ? R.string.error_drm_not_supported
                    : unsupportedDrmException.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME
                    ? R.string.error_drm_unsupported_scheme : R.string.error_drm_unknown);
        } else if (e instanceof ExoPlaybackException
                && e.getCause() instanceof MediaCodecTrackRenderer.DecoderInitializationException) {
            // Special case for decoder initialization failures.
            MediaCodecTrackRenderer.DecoderInitializationException decoderInitializationException =
                    (MediaCodecTrackRenderer.DecoderInitializationException) e.getCause();
            if (decoderInitializationException.decoderName == null) {
                if (decoderInitializationException.getCause() instanceof MediaCodecUtil.DecoderQueryException) {
                    errorString = getString(R.string.error_querying_decoders);
                } else if (decoderInitializationException.secureDecoderRequired) {
                    errorString = getString(R.string.error_no_secure_decoder,
                            decoderInitializationException.mimeType);
                } else {
                    errorString = getString(R.string.error_no_decoder,
                            decoderInitializationException.mimeType);
                }
            } else {
                errorString = getString(R.string.error_instantiating_decoder,
                        decoderInitializationException.decoderName);
            }
        }
        if (errorString != null) {
            Toast.makeText(getApplicationContext(), errorString, Toast.LENGTH_LONG).show();
        }
        playerNeedsPrepare = true;
        if (callback != null) {
            callback.updateStatus("error");
        }
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                   float pixelWidthAspectRatio) {
    }

    // HopeFMPlayer.MetadataListener implementation
    @Override
    public void onId3Metadata(List<Id3Frame> id3Frames) {
        for (Id3Frame id3Frame : id3Frames) {
            if (id3Frame instanceof TxxxFrame) {
                TxxxFrame txxxFrame = (TxxxFrame) id3Frame;
                Log.i(TAG, String.format("ID3 TimedMetadata %s: description=%s, value=%s", txxxFrame.id,
                        txxxFrame.description, txxxFrame.value));
            } else if (id3Frame instanceof PrivFrame) {
                PrivFrame privFrame = (PrivFrame) id3Frame;
                Log.i(TAG, String.format("ID3 TimedMetadata %s: owner=%s", privFrame.id, privFrame.owner));
            } else if (id3Frame instanceof GeobFrame) {
                GeobFrame geobFrame = (GeobFrame) id3Frame;
                Log.i(TAG, String.format("ID3 TimedMetadata %s: mimeType=%s, filename=%s, description=%s",
                        geobFrame.id, geobFrame.mimeType, geobFrame.filename, geobFrame.description));
            } else {
                Log.i(TAG, String.format("ID3 TimedMetadata %s", id3Frame.id));
            }
        }
    }

    private static String buildTrackName(MediaFormat format) {
        if (format.adaptive) {
            return "auto";
        }
        String trackName;
        if (MimeTypes.isVideo(format.mimeType)) {
            trackName = joinWithSeparator(joinWithSeparator(buildResolutionString(format),
                    buildBitrateString(format)), buildTrackIdString(format));
        } else if (MimeTypes.isAudio(format.mimeType)) {
            trackName = joinWithSeparator(joinWithSeparator(joinWithSeparator(buildLanguageString(format),
                    buildAudioPropertyString(format)), buildBitrateString(format)),
                    buildTrackIdString(format));
        } else {
            trackName = joinWithSeparator(joinWithSeparator(buildLanguageString(format),
                    buildBitrateString(format)), buildTrackIdString(format));
        }
        return trackName.length() == 0 ? "unknown" : trackName;
    }

    private static String buildResolutionString(MediaFormat format) {
        return format.width == MediaFormat.NO_VALUE || format.height == MediaFormat.NO_VALUE
                ? "" : format.width + "x" + format.height;
    }

    private static String buildAudioPropertyString(MediaFormat format) {
        return format.channelCount == MediaFormat.NO_VALUE || format.sampleRate == MediaFormat.NO_VALUE
                ? "" : format.channelCount + "ch, " + format.sampleRate + "Hz";
    }

    private static String buildLanguageString(MediaFormat format) {
        return TextUtils.isEmpty(format.language) || "und".equals(format.language) ? ""
                : format.language;
    }

    private static String buildBitrateString(MediaFormat format) {
        return format.bitrate == MediaFormat.NO_VALUE ? ""
                : String.format(Locale.US, "%.2fMbit", format.bitrate / 1000000f);
    }

    private static String joinWithSeparator(String first, String second) {
        return first.length() == 0 ? second : (second.length() == 0 ? first : first + ", " + second);
    }

    private static String buildTrackIdString(MediaFormat format) {
        return format.trackId == null ? "" : " (" + format.trackId + ")";
    }

    public class MusicBinder extends Binder {
        HopeFMService getService() {
            return HopeFMService.this;
        }
    }
}
