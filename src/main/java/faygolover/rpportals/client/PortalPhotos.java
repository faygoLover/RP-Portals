package faygolover.rpportals.client;

import com.mojang.blaze3d.platform.NativeImage;
import faygolover.rpportals.PortalBlockEntity;
import faygolover.rpportals.PortalNet;
import faygolover.rpportals.RpPortals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Снимки «той стороны» на клиенте. Снимок делает сам портал: сервер просит клиент игрока, который рядом с порталом,
 * снять вид с его выхода ({@link PortalView#photo} — цвет и глубина), клиент шлёт PNG на сервер. Порталы с видом
 * «снимок» запрашивают снимок своей цели, когда попадают в кадр, и показывают его с объёмом.
 */
public final class PortalPhotos {
    private PortalPhotos() {}

    private record Entry(ResourceLocation id, DynamicTexture tex) {}

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Map<String, Long> REQUESTED = new HashMap<>();
    private static final Deque<Object[]> WANTED = new ArrayDeque<>();
    private static int counter;

    /** Id текстуры снимка портала {@code name} или −1 (ещё нет — запросить). */
    static int texture(String name) {
        if (name.isEmpty()) return -1;
        Entry e = CACHE.get(name);
        if (e != null) return e.tex.getId();
        long now = System.currentTimeMillis();
        Long last = REQUESTED.get(name);
        if (last == null || now - last > 15_000) {
            REQUESTED.put(name, now);
            PortalNet.CHANNEL.sendToServer(new PortalNet.PhotoRequest(name));
        }
        return -1;
    }

    public static void onData(String name, byte[] data) {
        if (data.length == 0) return;
        try {
            NativeImage ni = NativeImage.read(data);
            forget(name);
            DynamicTexture tex = new DynamicTexture(ni);
            tex.setFilter(true, false);
            ResourceLocation id = Minecraft.getInstance().getTextureManager().register(RpPortals.MODID + "_photo_" + (counter++), tex);
            CACHE.put(name, new Entry(id, tex));
        } catch (Exception ex) {
            RpPortals.LOGGER.warn("RP Portals: снимок «{}» не прочитан", name, ex);
        }
    }

    public static void forget(String name) {
        Entry e = CACHE.remove(name);
        REQUESTED.remove(name);
        if (e != null) Minecraft.getInstance().getTextureManager().release(e.id);
    }

    static void clear() {
        for (String n : CACHE.keySet().toArray(new String[0])) forget(n);
        REQUESTED.clear();
        WANTED.clear();
    }

    // ---------------------------------------------------------------- снять по просьбе сервера
    private static final Deque<Object[]> DEBUG = new ArrayDeque<>();

    /** Пробный снимок плоскости {@code frame} — только в rpportals_debug (команда {@code /rpportals debug}). */
    static void debugCapture(faygolover.rpportals.PortalFrame frame, float depth, int rgb) {
        DEBUG.add(new Object[]{frame, depth, rgb});
    }

    public static void onWanted(String name, BlockPos pos) {
        WANTED.add(new Object[]{name, pos});
    }

    /** Снимать в тике клиента — вне отрисовки мира, никакое её состояние не мешает; свой кадр. */
    static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent e) {
        if (e.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!DEBUG.isEmpty()) {
            Object[] d = DEBUG.poll();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                PortalView.debugPhoto = true;
                try (NativeImage img = PortalView.photo(mc.level, (faygolover.rpportals.PortalFrame) d[0], (Float) d[1], (Integer) d[2])) {
                    RpPortals.LOGGER.info("RP Portals: пробный снимок {}×{} готов", img.getWidth(), img.getHeight());
                } catch (Exception ex) {
                    RpPortals.LOGGER.error("RP Portals: пробный снимок не получился", ex);
                }
            }
        }
        if (WANTED.isEmpty()) return;
        Object[] w = WANTED.poll();
        String name = (String) w[0];
        BlockPos pos = (BlockPos) w[1];
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof PortalBlockEntity be) || !name.equals(be.name)) return;
        if (!PortalView.loaded(mc.level, be.frame(), be.viewDepth)) return;
        try (NativeImage img = PortalView.photo(mc.level, be.frame(), be.viewDepth, be.rgb())) {
            send(name, img.asByteArray());
        } catch (Exception ex) {
            RpPortals.LOGGER.error("RP Portals: снимок «{}» не получился", name, ex);
        }
    }

    private static void send(String name, byte[] data) {
        int size = PortalNet.PhotoChunk.SIZE;
        int total = (data.length + size - 1) / size;
        for (int i = 0; i < total; i++) {
            byte[] part = java.util.Arrays.copyOfRange(data, i * size, Math.min(data.length, (i + 1) * size));
            PortalNet.CHANNEL.sendToServer(new PortalNet.PhotoChunk(name, i, total, part));
        }
    }
}
