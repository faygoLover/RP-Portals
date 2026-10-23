package faygolover.rpportals;

import faygolover.rpcore.gm.GmTools;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Снимки «той стороны» (вид «снимок»): ведущий снимает вид с выхода портала, клиент присылает JPEG кусками, сервер
 * хранит его в мире ({@code rpportals_photos/<хэш имени>.jpg}) и раздаёт игрокам по запросу. Порталы, которые ведут в
 * этот портал, показывают его снимок.
 */
public final class PortalPhotoServer {
    private PortalPhotoServer() {}

    static final int MAX_BYTES = 900_000;
    private static final Map<UUID, ByteArrayOutputStream> UPLOADS = new HashMap<>();
    private static final Map<UUID, String> UPLOAD_NAME = new HashMap<>();

    private static Path file(MinecraftServer server, String name) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(name.getBytes(StandardCharsets.UTF_8));
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("rpportals_photos");
        Files.createDirectories(dir);
        return dir.resolve(HexFormat.of().formatHex(h) + ".jpg");
    }

    static void onChunk(ServerPlayer sp, PortalNet.PhotoChunk m) {
        if (!GmTools.isGm(sp) || m.name().isEmpty() || m.name().length() > 32) return;
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
        byte[] jpg = out.toByteArray();
        if (jpg.length < 4 || (jpg[0] & 0xFF) != 0xFF || (jpg[1] & 0xFF) != 0xD8) return;   // только JPEG
        try {
            Files.write(file(sp.server, m.name()), jpg);
        } catch (Exception ex) {
            RpPortals.LOGGER.error("RP Portals: снимок «{}» не записан", m.name(), ex);
            return;
        }
        PortalNet.CHANNEL.send(PacketDistributor.ALL.noArg(), new PortalNet.PhotoChanged(m.name()));
        sp.displayClientMessage(Component.translatable("rpportals.photo.saved", m.name()), true);
    }

    static void onRequest(ServerPlayer sp, String name) {
        byte[] data = new byte[0];
        try {
            Path f = file(sp.server, name);
            if (Files.exists(f)) data = Files.readAllBytes(f);
        } catch (Exception ex) {
            RpPortals.LOGGER.warn("RP Portals: снимок «{}» не прочитан", name, ex);
        }
        PortalNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new PortalNet.PhotoData(name, data));
    }

    static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UPLOADS.remove(e.getEntity().getUUID());
        UPLOAD_NAME.remove(e.getEntity().getUUID());
    }
}
