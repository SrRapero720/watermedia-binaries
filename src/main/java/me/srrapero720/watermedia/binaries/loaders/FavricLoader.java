package me.srrapero720.watermedia.binaries.loaders;

import me.srrapero720.watermedia.WaterMedia;
import me.srrapero720.watermedia.binaries.WaterMediaBin;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class FavricLoader implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        WaterMedia.attachClassLoader(WaterMediaBin.class, WaterMediaBin.class.getClassLoader());
    }
}
