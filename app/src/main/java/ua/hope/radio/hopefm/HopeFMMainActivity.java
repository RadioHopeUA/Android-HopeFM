package ua.hope.radio.hopefm;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import java.io.IOException;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMMainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MediaPlayer mPlayer = new MediaPlayer();
        Uri myUri1 = Uri.parse("http://stream.hope.ua:1935/hopefm/ngrp:live/playlist.m3u8");
        mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

        try {
            mPlayer.setDataSource(getApplicationContext(), myUri1);
            mPlayer.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mPlayer.start();
    }
}
