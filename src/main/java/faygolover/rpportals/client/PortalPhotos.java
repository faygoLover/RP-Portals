package faygolover.rpportals.client;

import com.mojang.blaze3d.platform.NativeImage;
import faygolover.rpportals.PortalNet;
import faygolover.rpportals.RpPortals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Снимки «той стороны». Ведущий встаёт у выхода портала, смотрит туда, что должно быть видно входящим, и жмёт
 * в настройках этого портала «Снимок отсюда»: через 3 с кадр мира (без руки и интерфейса) обрезается по пропорциям
 * портала, сжимается в JPEG до 512 px и уходит на сервер. Порталы, которые ведут сюда, с видом «снимок» показывают его
 * с объёмом (сдвигается, когда смотришь под углом). Снимки запрашиваются с сервера, когда портал попадает в кадр.
 */
public final class PortalPhotos {
    private PortalPhotos() {}

    private record Entry(ResourceLocation id, DynamicTexture tex) {}

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Map<String, Long> REQUESTED = new HashMap<>();
    private static String captureName;
    private static float captureAspect = 0.5f;
    private static int captureTicks = -1;
    private static boolean captureNow;
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
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) return;
            NativeImage ni = new NativeImage(img.getWidth(), img.getHeight(), false);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                    ni.setPixelRGBA(x, y, 0xFF000000 | b << 16 | g << 8 | r);   // NativeImage — ABGR
                }
            }
            forget(name);
            DynamicTexture tex = new DynamicTexture(ni);
            tex.setFilter(true, false);
            ResourceLocation id = Minecraft.getInstance().getTextureManager()
                    .register(RpPortals.MODID + "_photo_" + (counter++), tex);
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
        captureTicks = -1;
        captureNow = false;
    }

    // ---------------------------------------------------------------- снимок
    /** Снять вид для портала {@code name} через 3 с (ведущему — закрыть окно и прицелиться). */
    static void schedule(String name, float aspect) {
        Minecraft mc = Minecraft.getInstance();
        if (name.isEmpty()) {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("rpportals.photo.no_name"), true);
            return;
        }
        captureName = name;
        captureAspect = Math.max(0.1f, aspect);
        captureTicks = 60;
        mc.setScreen(null);
    }

    static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || captureTicks < 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (captureTicks % 20 == 0 && captureTicks > 0 && mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("rpportals.photo.countdown", captureTicks / 20), true);
        }
        if (--captureTicks < 0) captureNow = true;
    }

    /** Кадр мира без руки и интерфейса — сразу после мира, раньше подписей ведущего. */
    static void onRenderLevel(RenderLevelStageEvent e) {
        if (!captureNow || e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        captureNow = false;
        Minecraft mc = Minecraft.getInstance();
        try (NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int fw = full.getWidth(), fh = full.getHeight();
            int ch = (int) (fh * 0.9f), cw = (int) (ch * captureAspect);
            if (cw > fw) {
                cw = fw;
                ch = (int) (cw / captureAspect);
            }
            int scale = Math.max(cw, ch);
            int tw = Math.max(16, Math.round(cw * 512f / scale)), th = Math.max(16, Math.round(ch * 512f / scale));
            try (NativeImage out = new NativeImage(tw, th, false)) {
                full.resizeSubRectTo((fw - cw) / 2, (fh - ch) / 2, cw, ch, out);
                send(captureName, jpeg(out));
            }
        } catch (Exception ex) {
            RpPortals.LOGGER.error("RP Portals: снимок не получился", ex);
        }
    }

    private static byte[] jpeg(NativeImage img) throws Exception {
        BufferedImage bi = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int abgr = img.getPixelRGBA(x, y);
                int r = abgr & 255, g = abgr >> 8 & 255, b = abgr >> 16 & 255;
                bi.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.85f);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            w.setOutput(out);
            w.write(null, new IIOImage(bi, null, null), p);
        } finally {
            w.dispose();
        }
        return bytes.toByteArray();
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
