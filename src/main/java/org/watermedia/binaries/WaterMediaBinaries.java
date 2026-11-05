package org.watermedia.binaries;

import org.apache.logging.log4j.Logger;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Provisions native files when WaterMedia loads and keeps their locations for the life of the process. */
public final class WaterMediaBinaries extends WaterMedia.Module {
    public static final String ID = "watermedia_binaries";
    public static final String NAME = "WaterMedia: Binaries";
    public static final String FFMPEG_ID = "ffmpeg";
    public static final String YTDLP_ID = "yt-dlp";
    public static final String BOTGUARD_ID = "botguard";
    public static final Logger LOGGER = WaterMedia.logger(ID);
    private static final AtomicBoolean LOADED = new AtomicBoolean(); // FLIPS ON THE FIRST CONSTRUCTION, WHEN WATERMEDIA STARTS
    private static volatile Map<String, Path> paths = Map.of();

    /** Creates the Binaries module; {@link WaterMedia#start} calls it once per process and a second call throws. */
    public WaterMediaBinaries() {
        if (!LOADED.compareAndSet(false, true)) throw new IllegalStateException(NAME + " is already loaded");
    }

    /** Returns a registered cache or provisioned native directory. */
    public static Path pathOf(final String id) {
        return paths.get(id);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override
    protected void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) throws IOException {
        // A DEDICATED SERVER PLAYS NOTHING, SO IT NEVER PROVISIONS THE NATIVES
        if (!clientSide) return;
        paths = Map.of(YTDLP_ID, tmp.resolve(YTDLP_ID), BOTGUARD_ID, tmp.resolve(BOTGUARD_ID));
        if (WaterMediaConfig.codecs.ffmpeg.disable) return;
        final Path installed = FFmpegBinaries.start(tmp.resolve(FFMPEG_ID));
        paths = Map.of(FFMPEG_ID, installed, YTDLP_ID, tmp.resolve(YTDLP_ID), BOTGUARD_ID, tmp.resolve(BOTGUARD_ID));
    }

    static Path binaryDir(final String id) throws IOException {
        final Path result = paths.get(id);
        if (result == null) throw new IOException("WaterMedia Binaries is not initialized: " + id);
        return result;
    }
}
