package faygolover.rpportals;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/** Канал {@code rpportals:main}: клиент сообщает о пересечении портала и о «подержал ПКМ». */
public final class PortalNet {
    private PortalNet() {}

    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(RpPortals.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, Cross.class, Cross::encode, Cross::decode, Cross::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, Activate.class, Activate::encode, Activate::decode, Activate::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
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

    /** Игрок подержал ПКМ на портале в режиме «держать». */
    public record Activate(BlockPos pos) {
        static void encode(Activate m, FriendlyByteBuf b) {
            b.writeBlockPos(m.pos);
        }

        static Activate decode(FriendlyByteBuf b) {
            return new Activate(b.readBlockPos());
        }

        static void handle(Activate m, Supplier<NetworkEvent.Context> ctx) {
            var sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (sp != null) PortalServer.onActivate(sp, m.pos);
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
