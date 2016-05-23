package ua.hope.radio.hopefm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.text.Html;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMMainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "HopeFMMainActivity";
    private static final int MENU_GROUP_TRACKS = 1;

    private Button audioButton;
    private Button wwwButton;
    private ImageButton playButton;
    private TextView statusText;
    private TextView songNameText;
    private TextView artistNameText;

    private IHopeFMService mHopeFMService;
    boolean mBound = false;
    private PopupMenu mPopupMenu;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        audioButton = (Button) findViewById(R.id.audio_controls);
        wwwButton = (Button) findViewById(R.id.www);
        wwwButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Creating the instance of PopupMenu
                PopupMenu popup = new PopupMenu(HopeFMMainActivity.this, wwwButton);
                //Inflating the Popup using xml file
                popup.getMenuInflater()
                        .inflate(R.menu.www_popup, popup.getMenu());

                //registering popup with OnMenuItemClickListener
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        String url = null;
                        switch (item.getItemId()) {
                            case R.id.website:
                                url = getString(R.string.website_url);
                                break;
                            case R.id.fb:
                                url = getString(R.string.fb_url);
                                break;
                            case R.id.vk:
                                url = getString(R.string.vk_url);
                                break;
                            case R.id.twitter:
                                url = getString(R.string.twitter_url);
                                break;
                            case R.id.write_us:
                                url = getString(R.string.mailto_url);
                                break;
                            default:
                                break;
                        }
                        if (url != null) {
                            Intent intent = new Intent(android.content.Intent.ACTION_VIEW);
                            intent.setData(Uri.parse(url));
                            if (item.getItemId() == R.id.write_us) {
                                intent.putExtra("android.intent.extra.SUBJECT", "\"" + getString(R.string.app_name) + "\" Android");
                            }
                            if (intent.resolveActivity(getPackageManager()) != null) {
                                startActivity(intent);
                            }
                        }

                        return true;
                    }
                });
                popup.show(); //showing popup menu
            }
        });
        playButton = (ImageButton) findViewById(R.id.buttonPlayPause);
        playButton.setOnClickListener(this);
        statusText = (TextView) findViewById(R.id.textStatus);
        songNameText = (TextView) findViewById(R.id.textSongName);
        songNameText.setText("");
        artistNameText = (TextView) findViewById(R.id.textArtistName);
        artistNameText.setText("");
        TextView hopeFMText = (TextView) findViewById(R.id.hopeFMText);
        hopeFMText.setText(Html.fromHtml(getString(R.string.hopefm)));
        mPopupMenu = new PopupMenu(this, audioButton);
        mPopupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (mBound && item.getGroupId() == MENU_GROUP_TRACKS) {
                    mHopeFMService.setSelectedTrack(item.getItemId());
                    return true;
                }
                return false;
            }
        });

    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, HopeFMService.class);
        startService(intent);
        if (mHopeFMService == null) {
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
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
        mPopupMenu.show();
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
                setOnPlayButtons();
            } else {
                setOnPauseButtons();
            }
        }
    }

    private void setOnPauseButtons() {
        playButton.setImageResource(R.drawable.play_button);
        audioButton.setVisibility(View.INVISIBLE);
    }

    private void setOnPlayButtons() {
        playButton.setImageResource(R.drawable.pause_button);
        audioButton.setVisibility(View.VISIBLE);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            HopeFMService.MusicBinder binder = (HopeFMService.MusicBinder) service;
            mHopeFMService = binder.getService();
            mHopeFMService.registerCallback(mCallback);
            mBound = true;
            updateAudioButtonState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mHopeFMService = null;
        }
    };

    private static final int SONG_INFO_MSG = 1;
    private static final int STATUS_MSG = 2;
    private static final int TRACKS_MSG = 3;
    private IHopeFMServiceCallback mCallback = new IHopeFMServiceCallback() {
        @Override
        public void updateSongInfo(String artist, String title) {
            Bundle bundle = new Bundle();
            bundle.putString("title", title);
            bundle.putString("artist", artist);
            Message msg = mHandler.obtainMessage(SONG_INFO_MSG);
            msg.setData(bundle);
            mHandler.sendMessage(msg);
        }

        @Override
        public void updateStatus(String status) {
            Bundle bundle = new Bundle();
            bundle.putString("status", status);
            Message msg = mHandler.obtainMessage(STATUS_MSG);
            msg.setData(bundle);
            mHandler.sendMessage(msg);
        }

        @Override
        public void updateTracks(ArrayList<String> tracks, int selected) {
            Bundle bundle = new Bundle();
            bundle.putStringArrayList("tracks", tracks);
            bundle.putInt("selected", selected);
            Message msg = mHandler.obtainMessage(TRACKS_MSG);
            msg.setData(bundle);
            mHandler.sendMessage(msg);
        }
    };

    private Handler mHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case SONG_INFO_MSG:
                    songNameText.setText(msg.getData().getString("title"));
                    artistNameText.setText(msg.getData().getString("artist"));
                    break;
                case STATUS_MSG:
                    String status = msg.getData().getString("status");
                    String localizedStatus = "";
                    if ("error".equals(status)) {
                        mHopeFMService.stop();
                        setOnPauseButtons();
                        localizedStatus = getString(R.string.status_error);
                    }
                    if ("stopped".equals(status)) {
                        setOnPauseButtons();
                        break;
                    }
                    if ("playing".equals(status)) {
                        setOnPlayButtons();
                        break;
                    }
                    if ("buffering".equals(status)) {
                        localizedStatus = getString(R.string.status_buffering);
                    }
                    if ("preparing".equals(status)) {
                        localizedStatus = getString(R.string.status_preparing);
                    }
                    statusText.setText(localizedStatus);
                    break;
                case TRACKS_MSG:
                    ArrayList<String> tracks = msg.getData().getStringArrayList("tracks");
                    int selected = msg.getData().getInt("selected");
                    Menu menu = mPopupMenu.getMenu();
                    menu.clear();
                    for (int i = 0; i < tracks.size(); i++) {
                        menu.add(MENU_GROUP_TRACKS, i, Menu.NONE, tracks.get(i));
                    }
                    if (tracks.size() > 0) {
                        menu.setGroupCheckable(MENU_GROUP_TRACKS, true, true);
                        menu.findItem(selected).setChecked(true);
                    }
                    break;
                default:
                    break;
            }
            return true;
        }
    });
}
