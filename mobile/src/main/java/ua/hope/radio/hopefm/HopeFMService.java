package ua.hope.radio.hopefm;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import android.widget.RemoteViews;
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
        AudioCapabilitiesReceiver.Listener, IHopeFMService, AudioManager.OnAudioFocusChangeListener {
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
                mServiceNotification.contentView.setTextViewText(R.id.status_bar_artist_name, splitted[0]);
                mServiceNotification.contentView.setTextViewText(R.id.status_bar_track_name, splitted[1]);
                mNotifyManager.notify(NOTIFICATION_ID, mServiceNotification);
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
    private AudioManager am;

    private ArrayList<String> tracks = new ArrayList<>();
    private IHopeFMServiceCallback callback;
    private NotificationManagerCompat mNotifyManager;
    private static final int NOTIFICATION_ID = 1;
    private Notification mServiceNotification;

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
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioCapabilitiesReceiver = new AudioCapabilitiesReceiver(this, this);
        audioCapabilitiesReceiver.register();
        updateTrackRunnable = new UpdateTrackRunnable(mCurrentTrackHandler, getString(R.string.radio_info_url));
        exec = new ScheduledThreadPoolExecutor(1);

        mNotifyManager = NotificationManagerCompat.from(this);
        Intent notificationIntent = new Intent(this, HopeFMMainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0,
                new Intent(this, HopeFMService.class).setAction("stop"), 0);
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.status_bar);
        views.setOnClickPendingIntent(R.id.status_bar_stop, stopPendingIntent);
        mServiceNotification = new NotificationCompat.Builder(this)
                .setContent(views)
                .setSmallIcon(R.drawable.ic_stat_av_play_circle_outline)
                .setContentIntent(pendingIntent).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stop();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        audioCapabilitiesReceiver.unregister();
        stop();
        am.abandonAudioFocus(this);
    }

    private void startTrackInfoScheduler() {
        mScheduledTask = exec.scheduleAtFixedRate(updateTrackRunnable, 0, 5, TimeUnit.SECONDS);
    }

    private void stopTrackInfoScheduler() {
        if (mScheduledTask != null) {
            mScheduledTask.cancel(true);
        }
        exec.remove(updateTrackRunnable);
        exec.purge();
    }

    @Override
    public void play() {
        am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        preparePlayer(true);
        startTrackInfoScheduler();
        startForeground(NOTIFICATION_ID, mServiceNotification);
        if (callback != null) {
            callback.updateStatus("playing");
        }
    }

    @Override
    public void stop() {
        tracks.clear();
        releasePlayer();
        stopTrackInfoScheduler();
        stopForeground(true);
        if (callback != null) {
            callback.updateStatus("stopped");
        }
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
        if (player == null) {
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
                for (int i = 0; i < getTrackCount(); i++) {
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

    private String buildTrackName(MediaFormat format) {
        if (format.adaptive) {
            return getString(R.string.audio_auto);
        }
        String trackName = buildBitrateString(format);

        return trackName.length() == 0 ? getString(R.string.audio_unknown) : trackName;
    }

    private String buildBitrateString(MediaFormat format) {
        return format.bitrate == MediaFormat.NO_VALUE ? ""
                : String.format(Locale.US, getString(R.string.audio_kbits), format.bitrate / 1000f);
    }

    private boolean stoppedByAudioFocus = false;
    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (isPlaying()) {
                stoppedByAudioFocus = true;
                stop();
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (stoppedByAudioFocus) {
                play();
                stoppedByAudioFocus = false;
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            stop();
            am.abandonAudioFocus(this);
        }
    }

    public class MusicBinder extends Binder {
        HopeFMService getService() {
            return HopeFMService.this;
        }
    }
}
