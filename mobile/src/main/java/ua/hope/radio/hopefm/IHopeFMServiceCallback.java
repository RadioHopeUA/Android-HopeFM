package ua.hope.radio.hopefm;

import java.util.ArrayList;

/**
 * Created by Vitalii Cherniak on on 25.02.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public interface IHopeFMServiceCallback {
    void updateSongInfo(String artist, String title);
    void updateStatus(String status);
    void updateTracks(ArrayList<String> tracks, int selected);
}
