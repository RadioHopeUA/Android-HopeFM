package ua.hope.radio.hopefm;

import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;

/**
 * Created by Vitalii Cherniak on 25.02.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMService extends Service implements MediaPlayer.OnBufferingUpdateListener,
        MediaPlayer.OnInfoListener, MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener {
    private static final String TAG = "HopeFMService";
    private final IBinder musicBind = new MusicBinder();
    private MediaPlayer mPlayer;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return musicBind;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mPlayer.stop();
        mPlayer.release();
        mPlayer = null;
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        init();
    }

    public void init() {
        if (mPlayer == null) {
            mPlayer = new MediaPlayer();
            mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mPlayer.setOnBufferingUpdateListener(this);
            mPlayer.setOnInfoListener(this);
            mPlayer.setOnErrorListener(this);
            mPlayer.setOnPreparedListener(this);

            try {
                mPlayer.setDataSource(getString(R.string.radio_stream_url));
                mPlayer.prepareAsync();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            mPlayer.reset();
        }
    }

    public void play() {
        mPlayer.start();
    }

    public void pause() {
        mPlayer.pause();
    }

    public boolean isPlaying() {
        return mPlayer.isPlaying();
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        Log.i(TAG, "Buffering " + percent);
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        Log.i(TAG, "INFO " + what + " " + extra);
        return true;
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.i(TAG, "ERROR " + what + " " + extra);
        return true;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mp.start();
    }

    public class MusicBinder extends Binder {
        HopeFMService getService() {
            return HopeFMService.this;
        }
    }
}
