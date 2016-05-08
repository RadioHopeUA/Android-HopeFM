package ua.hope.radio.hopefm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.util.MimeTypes;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.Locale;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMMainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "HopeFMMainActivity";
    private static final int MENU_GROUP_TRACKS = 1;

    private Button audioButton;
    private ImageButton playButton;
    private TextView statusText;
    private TextView songNameText;
    private TextView artistNameText;

    private HopeFMService mHopeFMService;
    boolean mBound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        audioButton = (Button) findViewById(R.id.audio_controls);
        playButton = (ImageButton) findViewById(R.id.buttonPlayPause);
        playButton.setOnClickListener(this);
        statusText = (TextView) findViewById(R.id.textStatus);
        songNameText = (TextView) findViewById(R.id.textSongName);
        songNameText.setText("");
        artistNameText = (TextView) findViewById(R.id.textArtistName);
        artistNameText.setText("");
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onTrackInfoReceived(TrackInfoEvent event) {
        if (songNameText != null) {
            songNameText.setText(event.title);
        }
        if (artistNameText != null) {
            artistNameText.setText(event.artist);
        }
    }

    @Subscribe
    @SuppressWarnings("unused")
    public void onStatusInfoReceived(StatusInfoEvent event) {
        if (statusText != null) {
            statusText.setText(event.status);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        Intent intent = new Intent(this, HopeFMService.class);
        startService(intent);
        if (mHopeFMService == null) {
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void showAudioPopup(View v) {
        PopupMenu popup = new PopupMenu(this, v);

        PopupMenu.OnMenuItemClickListener clickListener = new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return false;
            }
        };
        configurePopupWithTracks(popup, clickListener);
        popup.show();
    }

    private void configurePopupWithTracks(PopupMenu popup, final PopupMenu.OnMenuItemClickListener customActionClickListener) {
        int trackCount = mHopeFMService.getTrackCount();
        if (trackCount == 0) {
            return;
        }
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return (customActionClickListener != null
                        && customActionClickListener.onMenuItemClick(item))
                        || onTrackItemClick(item);
            }
        });
        Menu menu = popup.getMenu();
        for (int i = 0; i < trackCount; i++) {
            menu.add(MENU_GROUP_TRACKS, i, Menu.NONE,
                    buildTrackName(mHopeFMService.getTrackFormat(i)));
        }
        menu.setGroupCheckable(MENU_GROUP_TRACKS, true, true);
        menu.findItem(mHopeFMService.getSelectedTrack()).setChecked(true);
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

    private boolean onTrackItemClick(MenuItem item) {
        if (mBound || item.getGroupId() != MENU_GROUP_TRACKS) {
            return false;
        }
        mHopeFMService.setSelectedTrack(item.getItemId());
        return true;
    }

    @Override
    public void onClick(View view) {
        if (view == playButton && mBound) {
            if (mHopeFMService.isPlaying()) {
                mHopeFMService.stop();
            } else {
                mHopeFMService.play();
            }
            updateAudioButtonState();
        }
    }

    private void updateAudioButtonState() {
        if (playButton != null && audioButton != null) {
            if (mHopeFMService.isPlaying()) {
                playButton.setImageResource(R.drawable.pause_button);
                audioButton.setVisibility(View.VISIBLE);
            } else {
                playButton.setImageResource(R.drawable.play_button);
                audioButton.setVisibility(View.INVISIBLE);
            }
        }
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            HopeFMService.MusicBinder binder = (HopeFMService.MusicBinder) service;
            mHopeFMService = binder.getService();
            mBound = true;
            updateAudioButtonState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mHopeFMService = null;
        }
    };
}
