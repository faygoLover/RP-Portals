package faygolover.rpportals;

import faygolover.rpmedicine.entity.MedCartEntity;
import faygolover.rpmedicine.server.CarryService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * RP Medicine (мягкая зависимость): кто идёт через портал вместе с игроком — тело, которое он несёт (тащит), и
 * каталка или кресло, которое он везёт (с пассажиром). Без Medicine — никого. Классы Medicine трогает только
 * {@link Impl}, он грузится, лишь когда мод стоит.
 */
public final class MedicineCompat {
    private MedicineCompat() {}

    private static final boolean LOADED = ModList.get().isLoaded("rpmedicine");

    /** Тело и каталка игрока (сервер). */
    public static List<Entity> companions(ServerPlayer sp) {
        return LOADED ? Impl.companions(sp) : List.of();
    }

    /** Кто несёт это тело (сервер), или null. */
    @Nullable
    public static ServerPlayer carrierOf(Entity e) {
        return LOADED ? Impl.carrierOf(e) : null;
    }

    /** Сущность идёт только со своим ведущим: везомая каталка, несомое тело. */
    public static boolean attached(Entity e) {
        return LOADED && Impl.attached(e);
    }

    /** Каталка, которую везёт игрок (клиент и сервер), или null. */
    @Nullable
    public static Entity pushedCart(Player p) {
        return LOADED ? Impl.pushedCart(p) : null;
    }

    private static final class Impl {
        static List<Entity> companions(ServerPlayer sp) {
            List<Entity> out = new ArrayList<>(2);
            Entity body = CarryService.carried(sp);
            if (body != null && !body.isRemoved()) out.add(body);
            MedCartEntity cart = MedCartEntity.pushedBy(sp);
            if (cart != null && !cart.isRemoved()) out.add(cart);
            return out;
        }

        @Nullable
        static ServerPlayer carrierOf(Entity e) {
            if (e.getServer() == null || !CarryService.isCarried(e)) return null;
            for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) if (CarryService.carried(p) == e) return p;
            return null;
        }

        static boolean attached(Entity e) {
            if (e instanceof MedCartEntity c) return c.pusher() != null;
            return !e.level().isClientSide && CarryService.isCarried(e);
        }

        @Nullable
        static Entity pushedCart(Player p) {
            for (MedCartEntity c : p.level().getEntitiesOfClass(MedCartEntity.class, p.getBoundingBox().inflate(4)))
                if (c.pusher() == p) return c;
            return null;
        }
    }
}
