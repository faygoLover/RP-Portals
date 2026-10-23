package faygolover.rpportals;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/** Канал {@code rpportals:main}: переход через портал, снимки той стороны. */
public final class PortalNet {
    private PortalNet() {}

    private static final String VERSION = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(RpPortals.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, Cross.class, Cross::encode, Cross::decode, Cross::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PhotoChunk.class, PhotoChunk::encode, PhotoChunk::decode, PhotoChunk::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PhotoRequest.class, PhotoRequest::encode, PhotoRequest::decode, PhotoRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PhotoData.class, PhotoData::encode, PhotoData::decode, PhotoData::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, PhotoChanged.class, PhotoChanged::encode, PhotoChanged::decode, PhotoChanged::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /**
     * Игрок пересёк портал в {@code pos}; {@code before}, {@code yaw}, {@code pitch} — где он был и куда смотрел в момент
     * пересечения, до переноса (по его клиенту). {@code predicted} —
     * клиент уже перенёс себя сам (то же измерение): сервер лишь подтверждает, не дёргая камеру.
     */
    public record Cross(BlockPos pos, Vec3 before, float yaw, float pitch, boolean predicted) {
        static void encode(Cross m, FriendlyByteBuf b) {
            b.writeBlockPos(m.pos);
            b.writeDouble(m.before.x);
            b.writeDouble(m.before.y);
            b.writeDouble(m.before.z);
            b.writeFloat(m.yaw);
            b.writeFloat(m.pitch);
            b.writeBoolean(m.predicted);
        }

        static Cross decode(FriendlyByteBuf b) {
            return new Cross(b.readBlockPos(), new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readFloat(), b.readFloat(), b.readBoolean());
        }

        static void handle(Cross m, Supplier<NetworkEvent.Context> ctx) {
            var sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (sp != null) PortalServer.onCross(sp, m);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Ведущий → сервер: кусок снимка (JPEG) вида с выхода портала {@code name}. */
    public record PhotoChunk(String name, int index, int total, byte[] data) {
        public static final int SIZE = 28_000;

        static void encode(PhotoChunk m, FriendlyByteBuf b) {
            b.writeUtf(m.name, 64);
            b.writeVarInt(m.index);
            b.writeVarInt(m.total);
            b.writeByteArray(m.data);
        }

        static PhotoChunk decode(FriendlyByteBuf b) {
            return new PhotoChunk(b.readUtf(64), b.readVarInt(), b.readVarInt(), b.readByteArray(SIZE + 64));
        }

        static void handle(PhotoChunk m, Supplier<NetworkEvent.Context> ctx) {
            var sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (sp != null) PortalPhotoServer.onChunk(sp, m);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Клиент → сервер: прислать снимок портала {@code name}. */
    public record PhotoRequest(String name) {
        static void encode(PhotoRequest m, FriendlyByteBuf b) {
            b.writeUtf(m.name, 64);
        }

        static PhotoRequest decode(FriendlyByteBuf b) {
            return new PhotoRequest(b.readUtf(64));
        }

        static void handle(PhotoRequest m, Supplier<NetworkEvent.Context> ctx) {
            var sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (sp != null) PortalPhotoServer.onRequest(sp, m.name);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Сервер → клиенту: снимок (пустой — снимка нет). */
    public record PhotoData(String name, byte[] data) {
        static void encode(PhotoData m, FriendlyByteBuf b) {
            b.writeUtf(m.name, 64);
            b.writeByteArray(m.data);
        }

        static PhotoData decode(FriendlyByteBuf b) {
            return new PhotoData(b.readUtf(64), b.readByteArray(PortalPhotoServer.MAX_BYTES));
        }

        static void handle(PhotoData m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> faygolover.rpportals.client.PortalPhotos.onData(m.name, m.data)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Сервер → всем: снимок портала {@code name} сменился. */
    public record PhotoChanged(String name) {
        static void encode(PhotoChanged m, FriendlyByteBuf b) {
            b.writeUtf(m.name, 64);
        }

        static PhotoChanged decode(FriendlyByteBuf b) {
            return new PhotoChanged(b.readUtf(64));
        }

        static void handle(PhotoChanged m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> faygolover.rpportals.client.PortalPhotos.forget(m.name)));
            ctx.get().setPacketHandled(true);
        }
    }
}
