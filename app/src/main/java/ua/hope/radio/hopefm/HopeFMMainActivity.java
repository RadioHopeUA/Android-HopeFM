package ua.hope.radio.hopefm;

import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
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

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import android.os.Handler;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMMainActivity extends AppCompatActivity implements View.OnClickListener,
        HopeFMPlayer.Listener, HopeFMPlayer.Id3MetadataListener,
        AudioCapabilitiesReceiver.Listener {
    private static final String TAG = "HopeFMMainActivity";

    private static final int MENU_GROUP_TRACKS = 1;

    private static final CookieManager defaultCookieManager;

    static {
        defaultCookieManager = new CookieManager();
        defaultCookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private EventLogger eventLogger;
    private Button audioButton;
    private ImageButton playButton;
    private TextView statusText;
    private TextView songNameText;
    private TextView artistNameText;

    private HopeFMPlayer player;
    private boolean playerNeedsPrepare;

    private long playerPosition;

    private AudioCapabilitiesReceiver audioCapabilitiesReceiver;

    private Handler mCurrentTrackHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            String resp = msg.obj.toString();
            String[] splitted = resp.split(" - ");
            if (splitted.length == 2) {
                artistNameText.setText(splitted[0]);
                songNameText.setText(splitted[1]);
            }
            return true;
        }
    });

    private ScheduledFuture mScheduledTask;
    private UpdateTrackRunnable updateTrackRunnable;
    private ScheduledThreadPoolExecutor exec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        audioButton = (Button) findViewById(R.id.audio_controls);
        playButton = (ImageButton) findViewById(R.id.buttonPlayPause);
        playButton.setOnClickListener(this);
        statusText = (TextView) findViewById(R.id.textStatus);
        songNameText = (TextView) findViewById(R.id.textSongName);
        artistNameText = (TextView) findViewById(R.id.textArtistName);

        CookieHandler currentHandler = CookieHandler.getDefault();
        if (currentHandler != defaultCookieManager) {
            CookieHandler.setDefault(defaultCookieManager);
        }

        audioCapabilitiesReceiver = new AudioCapabilitiesReceiver(this, this);
        audioCapabilitiesReceiver.register();
        updateTrackRunnable = new UpdateTrackRunnable(mCurrentTrackHandler, getString(R.string.radio_info_url));
        exec = new ScheduledThreadPoolExecutor(1);
        startTrackInfoScheduler();
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
    public void onNewIntent(Intent intent) {
        releasePlayer();
        playerPosition = 0;
        setIntent(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (player == null) {
            preparePlayer(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        audioCapabilitiesReceiver.unregister();
        releasePlayer();
        stopTrackInfoScheduler();
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
        String userAgent = Util.getUserAgent(this, "ExoPlayerDemo");
        return new HlsRendererBuilder(this, userAgent, getString(R.string.radio_stream_url));

    }

    private void preparePlayer(boolean playWhenReady) {
        if (player == null) {
            player = new HopeFMPlayer(getRendererBuilder());
            player.addListener(this);
            player.setMetadataListener(this);
            player.seekTo(playerPosition);
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
            updateButtonVisibilities();
        }
        player.setPlayWhenReady(playWhenReady);
    }

    private void releasePlayer() {
        if (player != null) {
            playerPosition = player.getCurrentPosition();
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
        statusText.setText(text);
        updateButtonVisibilities();
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
        updateButtonVisibilities();
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                   float pixelWidthAspectRatio) {
    }

    // User controls

    private void updateButtonVisibilities() {
        audioButton.setVisibility(haveTracks(HopeFMPlayer.TYPE_AUDIO) ? View.VISIBLE : View.GONE);
    }

    private boolean haveTracks(int type) {
        return player != null && player.getTrackCount(type) > 0;
    }

    public void showAudioPopup(View v) {
        PopupMenu popup = new PopupMenu(this, v);

        PopupMenu.OnMenuItemClickListener clickListener = new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return false;
            }
        };
        configurePopupWithTracks(popup, clickListener, HopeFMPlayer.TYPE_AUDIO);
        popup.show();
    }

    private void configurePopupWithTracks(PopupMenu popup,
                                          final PopupMenu.OnMenuItemClickListener customActionClickListener,
                                          final int trackType) {
        if (player == null) {
            return;
        }
        int trackCount = player.getTrackCount(trackType);
        if (trackCount == 0) {
            return;
        }
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return (customActionClickListener != null
                        && customActionClickListener.onMenuItemClick(item))
                        || onTrackItemClick(item, trackType);
            }
        });
        Menu menu = popup.getMenu();
        for (int i = 0; i < trackCount; i++) {
            menu.add(MENU_GROUP_TRACKS, i, Menu.NONE,
                    buildTrackName(player.getTrackFormat(trackType, i)));
        }
        menu.setGroupCheckable(MENU_GROUP_TRACKS, true, true);
        menu.findItem(player.getSelectedTrack(trackType)).setChecked(true);
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

    private boolean onTrackItemClick(MenuItem item, int type) {
        if (player == null || item.getGroupId() != MENU_GROUP_TRACKS) {
            return false;
        }
        player.setSelectedTrack(type, item.getItemId());
        return true;
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

    @Override
    public void onClick(View view) {
        if (view == playButton) {
            if (player.getPlayerControl().isPlaying()) {
                player.getPlayerControl().pause();
                playButton.setImageResource(R.drawable.play_button);
                stopTrackInfoScheduler();
            } else {
                preparePlayer(true);
                playButton.setImageResource(R.drawable.pause_button);
                startTrackInfoScheduler();
            }
        }
    }
}
