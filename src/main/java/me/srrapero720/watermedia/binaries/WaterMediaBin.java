package me.srrapero720.watermedia.binaries;


import me.srrapero720.watermedia.WaterMedia;

public class WaterMediaBin {
    public static final String ID = "watermedia_binaries";

    public static void prepare() {
        WaterMedia.attachClassLoader(WaterMediaBin.class, WaterMediaBin.class.getClassLoader());
    }
}