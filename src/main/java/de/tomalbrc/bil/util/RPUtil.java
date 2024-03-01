package de.tomalbrc.bil.util;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.resources.ResourceLocation;

public class RPUtil {
    private static Object2ObjectOpenHashMap<ResourceLocation, byte[]> data = new Object2ObjectOpenHashMap<>();

    public static void add(ResourceLocation location, byte[] bytes) {
        data.put(location, bytes);
    }

    public static void addAdditional(ResourcePackBuilder resourcePackBuilder) {
        for (var entry: data.entrySet()) {
            resourcePackBuilder.addData(entry.getKey().getPath(), entry.getValue());
        }
    }
}