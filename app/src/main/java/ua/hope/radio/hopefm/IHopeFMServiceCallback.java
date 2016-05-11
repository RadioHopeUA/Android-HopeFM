package ua.hope.radio.hopefm;

import java.util.ArrayList;

public interface IHopeFMServiceCallback {
    void updateSongInfo(String artist, String title);
    void updateStatus(String status);
    void updateTracks(ArrayList<String> tracks, int selected);
}
