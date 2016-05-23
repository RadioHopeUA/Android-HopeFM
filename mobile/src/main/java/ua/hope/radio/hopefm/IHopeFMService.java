package ua.hope.radio.hopefm;

/**
 * Created by Vitalii Cherniak on on 25.02.16.
 * Copyright © 2016 Hope Media Group Ukraine. All rights reserved.
 */
public interface IHopeFMService {
    void play();
    void stop();
    boolean isPlaying();
    int getSelectedTrack();
    void setSelectedTrack(int id);
    void registerCallback(IHopeFMServiceCallback callback);
}
