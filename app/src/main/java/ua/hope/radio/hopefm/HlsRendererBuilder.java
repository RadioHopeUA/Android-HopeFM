package ua.hope.radio.hopefm;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.os.Handler;

import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecSelector;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.hls.DefaultHlsTrackSelector;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsMasterPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylistParser;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.hls.PtsTimestampAdjusterProvider;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer;
import com.google.android.exoplayer.metadata.id3.Id3Frame;
import com.google.android.exoplayer.metadata.id3.Id3Parser;
import com.google.android.exoplayer.text.TextTrackRenderer;
import com.google.android.exoplayer.text.eia608.Eia608TrackRenderer;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.ManifestFetcher;

import java.io.IOException;
import java.util.List;

/**
 * Created by Vitalii on 28.03.2016.
 */
public class HlsRendererBuilder implements HopeFMPlayer.RendererBuilder {

    private static final int BUFFER_SEGMENT_SIZE = 64 * 1024;
    private static final int MAIN_BUFFER_SEGMENTS = 256;
    private static final int TEXT_BUFFER_SEGMENTS = 2;

    private final Context context;
    private final String userAgent;
    private final String url;

    private AsyncRendererBuilder currentAsyncBuilder;

    public HlsRendererBuilder(Context context, String userAgent, String url) {
        this.context = context;
        this.userAgent = userAgent;
        this.url = url;
    }

    @Override
    public void buildRenderers(HopeFMPlayer player) {
        currentAsyncBuilder = new AsyncRendererBuilder(context, userAgent, url, player);
        currentAsyncBuilder.init();
    }

    @Override
    public void cancel() {
        if (currentAsyncBuilder != null) {
            currentAsyncBuilder.cancel();
            currentAsyncBuilder = null;
        }
    }

    private static final class AsyncRendererBuilder implements ManifestFetcher.ManifestCallback<HlsPlaylist> {

        private final Context context;
        private final String userAgent;
        private final String url;
        private final HopeFMPlayer player;
        private final ManifestFetcher<HlsPlaylist> playlistFetcher;

        private boolean canceled;

        public AsyncRendererBuilder(Context context, String userAgent, String url, HopeFMPlayer player) {
            this.context = context;
            this.userAgent = userAgent;
            this.url = url;
            this.player = player;
            HlsPlaylistParser parser = new HlsPlaylistParser();
            playlistFetcher = new ManifestFetcher<>(url, new DefaultUriDataSource(context, userAgent),
                    parser);
        }

        public void init() {
            playlistFetcher.singleLoad(player.getMainHandler().getLooper(), this);
        }

        public void cancel() {
            canceled = true;
        }

        @Override
        public void onSingleManifestError(IOException e) {
            if (canceled) {
                return;
            }

            player.onRenderersError(e);
        }

        @Override
        public void onSingleManifest(HlsPlaylist manifest) {
            if (canceled) {
                return;
            }

            Handler mainHandler = player.getMainHandler();
            LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
            DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
            PtsTimestampAdjusterProvider timestampAdjusterProvider = new PtsTimestampAdjusterProvider();

            // Build the video/audio/metadata renderers.
            DataSource dataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
            HlsChunkSource chunkSource = new HlsChunkSource(true /* isMaster */, dataSource, url,
                    manifest, DefaultHlsTrackSelector.newDefaultInstance(context), bandwidthMeter,
                    timestampAdjusterProvider, HlsChunkSource.ADAPTIVE_MODE_SPLICE);
            HlsSampleSource sampleSource = new HlsSampleSource(chunkSource, loadControl,
                    MAIN_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, mainHandler, player, HopeFMPlayer.TYPE_AUDIO);
            MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(context,
                    sampleSource, MediaCodecSelector.DEFAULT, MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
                    5000, mainHandler, player, 50);
            MediaCodecAudioTrackRenderer audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource,
                    MediaCodecSelector.DEFAULT, null, true, player.getMainHandler(), player,
                    AudioCapabilities.getCapabilities(context), AudioManager.STREAM_MUSIC);
            MetadataTrackRenderer<List<Id3Frame>> id3Renderer = new MetadataTrackRenderer<>(
                    sampleSource, new Id3Parser(), player, mainHandler.getLooper());

            // Build the text renderer, preferring Webvtt where available.
            boolean preferWebvtt = false;
            if (manifest instanceof HlsMasterPlaylist) {
                preferWebvtt = !((HlsMasterPlaylist) manifest).subtitles.isEmpty();
            }
            TrackRenderer textRenderer;
            if (preferWebvtt) {
                DataSource textDataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
                HlsChunkSource textChunkSource = new HlsChunkSource(false /* isMaster */, textDataSource,
                        url, manifest, DefaultHlsTrackSelector.newVttInstance(), bandwidthMeter,
                        timestampAdjusterProvider, HlsChunkSource.ADAPTIVE_MODE_SPLICE);
                HlsSampleSource textSampleSource = new HlsSampleSource(textChunkSource, loadControl,
                        TEXT_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE, mainHandler, player, HopeFMPlayer.TYPE_TEXT);
                textRenderer = new TextTrackRenderer(textSampleSource, player, mainHandler.getLooper());
            } else {
                textRenderer = new Eia608TrackRenderer(sampleSource, player, mainHandler.getLooper());
            }

            TrackRenderer[] renderers = new TrackRenderer[HopeFMPlayer.RENDERER_COUNT];
            renderers[HopeFMPlayer.TYPE_VIDEO] = videoRenderer;
            renderers[HopeFMPlayer.TYPE_AUDIO] = audioRenderer;
            renderers[HopeFMPlayer.TYPE_METADATA] = id3Renderer;
            renderers[HopeFMPlayer.TYPE_TEXT] = textRenderer;
            player.onRenderers(renderers, bandwidthMeter);
        }

    }

}
