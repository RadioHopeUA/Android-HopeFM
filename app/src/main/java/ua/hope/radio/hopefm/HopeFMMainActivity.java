package ua.hope.radio.hopefm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageButton;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMMainActivity extends AppCompatActivity {
    private static final String TAG = "HopeFMMainActivity";
    private HopeFMService musicSrv;
    private Intent playIntent;
    private boolean musicBound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final ImageButton playPauseButton = (ImageButton) findViewById(R.id.buttonPlayPause);
        playPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (musicBound) {
                    if (musicSrv.isPlaying()) {
                        playPauseButton.setImageResource(R.drawable.pause_button);
                        musicSrv.pause();
                    } else {
                        playPauseButton.setImageResource(R.drawable.play_button);
                        musicSrv.play();
                    }
                }
            }
        });
    }

    //connect to the service
    private ServiceConnection musicConnection = new ServiceConnection(){
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            HopeFMService.MusicBinder binder = (HopeFMService.MusicBinder)service;
            //get service
            musicSrv = binder.getService();
            musicBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            musicBound = false;
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        if(playIntent == null){
            playIntent = new Intent(this, HopeFMService.class);
            bindService(playIntent, musicConnection, Context.BIND_AUTO_CREATE);
            startService(playIntent);
        }
    }

    @Override
    protected void onDestroy() {
        stopService(playIntent);
        musicSrv = null;
        super.onDestroy();
    }
}
