package faygolover.rpportals;

import faygolover.rpcore.gm.GmTools;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Снимки «той стороны» делаются сами. У каждого портала с именем должен быть снимок вида с его выхода; если снимка нет
 * или ведущий попросил обновить, сервер просит ближайшего игрока у этого портала (его клиенту видна та сторона) снять
 * её. Клиент присылает PNG кусками (цвет + глубина в альфе), сервер хранит его в мире ({@code rpportals_photos/<хэш
 * имени>.png}) и раздаёт по запросу. Порталы с видом «снимок», которые ведут в этот портал, показывают его.
 */
public final class PortalPhotoServer {
    private PortalPhotoServer() {}

    static final int MAX_BYTES = 900_000;
    /** Сторона снимка: как {@code PortalView.PHOTO_MAX} на клиенте. */
    private static final int MAX_SIDE = 384;
    /** Лимит раздачи на игрока за окно; отброшенный запрос клиент повторит сам через 15 с. */
    private static final long REQUEST_WINDOW_MS = 10_000L;
    private static final int MAX_REQUESTS = 50;
    private static final long MAX_SENT_BYTES = 8_000_000L;
    /** Окно раздачи: начало, запросов, отправлено байт. */
    private static final Map<UUID, long[]> REQUESTS = new HashMap<>();
    private static final Map<UUID, ByteArrayOutputStream> UPLOADS = new HashMap<>();
    private static final Map<UUID, String> UPLOAD_NAME = new HashMap<>();
    /** Есть ли снимок (кэш проверки файла). */
    private static final Map<String, Boolean> EXISTS = new HashMap<>();
    /** Ведущий попросил обновить. */
    private static final Set<String> STALE = new HashSet<>();
    /** Кого и когда просили снять. */
    private static final Map<String, UUID> ASKED = new HashMap<>();
    private static final Map<String, Long> ASKED_AT = new HashMap<>();

    private static Path file(MinecraftServer server, String name) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(name.getBytes(StandardCharsets.UTF_8));
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("rpportals_photos");
        Files.createDirectories(dir);
        return dir.resolve(HexFormat.of().formatHex(h) + ".png");
    }

    static boolean needs(MinecraftServer server, String name) {
        if (STALE.contains(name)) return true;
        return !EXISTS.computeIfAbsent(name, n -> {
            try {
                return Files.exists(file(server, n));
            } catch (Exception e) {
                return true;
            }
        });
    }

    static void markStale(String name) {
        if (!name.isEmpty()) STALE.add(name);
    }

    /** Портал {@code name} хочет снимок: попросить ближайшего игрока в 40 блоках (не чаще раза в 30 с). */
    static void maybeAsk(ServerLevel level, String name, BlockPos pos, Vec3 at) {
        long now = level.getGameTime();
        Long last = ASKED_AT.get(name);
        if (last != null && now - last < 600) return;
        ServerPlayer best = null;
        double bestD = 40 * 40;
        for (ServerPlayer p : level.players()) {
            double d = p.position().distanceToSqr(at);
            if (d < bestD && !p.isSpectator()) {
                best = p;
                bestD = d;
            }
        }
        if (best == null) return;
        ASKED.put(name, best.getUUID());
        ASKED_AT.put(name, now);
        ServerPlayer target = best;
        PortalNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> target), new PortalNet.PhotoWanted(name, pos));
    }

    static void onChunk(ServerPlayer sp, PortalNet.PhotoChunk m) {
        if (m.name().isEmpty() || m.name().length() > 32) return;
        if (!sp.getUUID().equals(ASKED.get(m.name())) && !GmTools.isGm(sp)) return;
        UUID id = sp.getUUID();
        if (m.index() == 0) {
            UPLOADS.put(id, new ByteArrayOutputStream());
            UPLOAD_NAME.put(id, m.name());
        }
        ByteArrayOutputStream out = UPLOADS.get(id);
        if (out == null || !m.name().equals(UPLOAD_NAME.get(id))) return;
        out.writeBytes(m.data());
        if (out.size() > MAX_BYTES) {
            UPLOADS.remove(id);
            return;
        }
        if (m.index() < m.total() - 1) return;
        UPLOADS.remove(id);
        UPLOAD_NAME.remove(id);
        byte[] png = out.toByteArray();
        if (!validPng(png)) return;
        try {
            Files.write(file(sp.server, m.name()), png);
        } catch (Exception ex) {
            RpPortals.LOGGER.error("RP Portals: снимок «{}» не записан", m.name(), ex);
            return;
        }
        EXISTS.put(m.name(), true);
        STALE.remove(m.name());
        ASKED.remove(m.name());
        PortalNet.CHANNEL.send(PacketDistributor.ALL.noArg(), new PortalNet.PhotoChanged(m.name()));
        RpPortals.LOGGER.debug("RP Portals: снимок вида из портала «{}» обновлён ({} КБ, снял {})", m.name(), png.length / 1024, sp.getGameProfile().getName());
    }

    /** Только PNG: подпись, заголовок IHDR и стороны снимка, какие снимает клиент. */
    private static boolean validPng(byte[] png) {
        if (png.length < 24) return false;
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < sig.length; i++) if (png[i] != sig[i]) return false;
        if (png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return false;
        var head = java.nio.ByteBuffer.wrap(png);
        int w = head.getInt(16), h = head.getInt(20);
        return w > 0 && h > 0 && w <= MAX_SIDE && h <= MAX_SIDE;
    }

    static void onRequest(ServerPlayer sp, String name) {
        long now = System.currentTimeMillis();
        long[] window = REQUESTS.computeIfAbsent(sp.getUUID(), k -> new long[]{now, 0, 0});
        if (now - window[0] >= REQUEST_WINDOW_MS) {
            window[0] = now;
            window[1] = 0;
            window[2] = 0;
        }
        if (++window[1] > MAX_REQUESTS || window[2] >= MAX_SENT_BYTES) return;
        byte[] data = new byte[0];
        try {
            Path f = file(sp.server, name);
            if (Files.exists(f)) data = Files.readAllBytes(f);
        } catch (Exception ex) {
            RpPortals.LOGGER.warn("RP Portals: снимок «{}» не прочитан", name, ex);
        }
        window[2] += data.length;
        PortalNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new PortalNet.PhotoData(name, data));
    }

    static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UPLOADS.remove(e.getEntity().getUUID());
        UPLOAD_NAME.remove(e.getEntity().getUUID());
        REQUESTS.remove(e.getEntity().getUUID());
    }

    static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent e) {
        UPLOADS.clear();
        UPLOAD_NAME.clear();
        REQUESTS.clear();
        EXISTS.clear();
        STALE.clear();
        ASKED.clear();
        ASKED_AT.clear();
    }
}
