package me.srrapero720.watermedia.binaries.loaders;

import me.srrapero720.watermedia.WaterMedia;
import me.srrapero720.watermedia.binaries.WaterMediaBin;
import net.minecraftforge.fml.common.Mod;

@Mod(modid = WaterMediaBin.ID, acceptableRemoteVersions = "*", clientSideOnly = true, value = WaterMediaBin.ID, dependencies="before:watermedia")
public class ForgeLoader {
    public ForgeLoader() {
        WaterMedia.attachClassLoader(WaterMediaBin.class, WaterMediaBin.class.getClassLoader());
    }
}
