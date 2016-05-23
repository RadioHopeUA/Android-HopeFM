package ua.hope.radio.hopefm;

import android.os.Handler;
import android.util.Log;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by Vitalii Cherniak on on 04.04.2016.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public class UpdateTrackRunnable implements Runnable {
    private static final String TAG = "UpdateTrackRunnable";
    private OkHttpClient client;
    private Request request;
    private Handler handler;

    public UpdateTrackRunnable(Handler handler, String url) {
        this.handler = handler;
        client = new OkHttpClient();
        request = new Request.Builder()
                .url(url)
                .build();
    }

    @Override
    public void run() {
        if (handler != null) {
            try {
                Log.d(TAG, "Get track info");
                Response response = client.newCall(request).execute();
                handler.obtainMessage(0, response.body().string()).sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }
}
