package ua.hope.radio.hopefm;

public interface IHopeFMService {
    void play();
    void stop();
    boolean isPlaying();
    int getSelectedTrack();
    void setSelectedTrack(int id);
    void registerCallback(IHopeFMServiceCallback callback);
}
