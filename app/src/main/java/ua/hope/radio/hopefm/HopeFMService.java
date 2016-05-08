package ua.hope.radio.hopefm;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
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
import com.google.android.exoplayer.util.Util;

import org.greenrobot.eventbus.EventBus;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by Vitalii Cherniak on 25.02.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMService extends Service implements HopeFMPlayer.Listener, HopeFMPlayer.Id3MetadataListener,
        AudioCapabilitiesReceiver.Listener {
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
                EventBus.getDefault().post(new TrackInfoEvent(splitted[1], splitted[0]));
            }
            return true;
        }
    });

    private HopeFMPlayer player;
    private boolean playerNeedsPrepare;
    private EventLogger eventLogger;

    private AudioCapabilitiesReceiver audioCapabilitiesReceiver;

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
        releasePlayer();
        stopTrackInfoScheduler();
    }

    private void startTrackInfoScheduler() {
        mScheduledTask = exec.scheduleAtFixedRate(updateTrackRunnable, 0, 5, TimeUnit.SECONDS);
    }

    private void stopTrackInfoScheduler() {
        mScheduledTask.cancel(true);
        exec.remove(updateTrackRunnable);
        exec.purge();
    }

    public void play() {
        preparePlayer(true);
        startTrackInfoScheduler();
    }

    public void stop() {
        releasePlayer();
        stopTrackInfoScheduler();
    }

    public boolean isPlaying() {
        return player != null && player.getPlayerControl().isPlaying();
    }

    public boolean haveTracks() {
        return player != null && player.getTrackCount(HopeFMPlayer.TYPE_AUDIO) > 0;
    }

    public void setSelectedTrack(int id) {
        if (player != null) {
            player.setSelectedTrack(HopeFMPlayer.TYPE_AUDIO, id);
        }
    }

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
//            updateButtonVisibilities();
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
        String text;
        switch (playbackState) {
            case ExoPlayer.STATE_BUFFERING:
                text = "buffering";
                break;
            case ExoPlayer.STATE_PREPARING:
                text = "preparing";
                break;
            default:
                text = "";
                break;
        }
        EventBus.getDefault().post(new StatusInfoEvent(text));
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
        EventBus.getDefault().post(new StatusInfoEvent("error"));
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

    public class MusicBinder extends Binder {
        HopeFMService getService() {
            return HopeFMService.this;
        }
    }
}
