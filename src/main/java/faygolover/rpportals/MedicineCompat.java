package faygolover.rpportals;

import faygolover.rpmedicine.entity.MedCartEntity;
import faygolover.rpmedicine.server.CarryService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * RP Medicine (мягкая зависимость): кто идёт через портал вместе с игроком — тело, которое он несёт (тащит), и
 * каталка или кресло, которое он везёт (с пассажиром), — и как их переставить. Без Medicine — никого. Классы Medicine трогает только
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

    /** Каталка или кресло RP Medicine. */
    public static boolean isCart(Entity e) {
        return LOADED && Impl.isCart(e);
    }

    /** Кто везёт каталку (сервер), или null. */
    @Nullable
    public static ServerPlayer pusherOf(Entity cart) {
        return LOADED ? Impl.pusherOf(cart) : null;
    }

    /** Везущий держит каталку спереди (идёт к ней лицом), а не сзади. Смотреть до переноса. */
    public static boolean heldFromFront(Entity cart, Player pusher) {
        return Math.abs(Mth.wrapDegrees(cart.getYRot() - pusher.getYRot())) > 90;
    }

    /**
     * Где стоит каталка у везущего: перед ним по взгляду, на длине рук (кресло 1, каталка 1,5 блока) — как её ставит
     * сам Medicine, пока везут.
     */
    public static Vec3 cartSpot(Entity cart, Vec3 pusherPos, float pusherYaw) {
        double reach = LOADED && Impl.lying(cart) ? 1.5 : 1.0;
        return pusherPos.add(Vec3.directionFromRotation(0, pusherYaw).scale(reach));
    }

    /** Отпустить ручки (сервер). */
    public static void release(Entity cart) {
        if (LOADED) Impl.release(cart);
    }

    /** Взяться за ручки (сервер). */
    public static boolean grab(Entity cart, ServerPlayer sp, boolean fromFront) {
        return LOADED && Impl.grab(cart, sp, fromFront);
    }

    /** Снять с каталки по воле мода — и лежачего (сам он не слезает). */
    public static void unseat(Entity rider) {
        if (LOADED) Impl.unseat(rider);
        else rider.stopRiding();
    }

    /** Снова взять тело волоком (сервер), отпустив прежнее. */
    public static boolean pickUp(ServerPlayer carrier, Entity body) {
        return LOADED && body instanceof LivingEntity le && Impl.pickUp(carrier, le);
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

        static boolean isCart(Entity e) {
            return e instanceof MedCartEntity;
        }

        static boolean lying(Entity e) {
            return e instanceof MedCartEntity c && c.lying();
        }

        @Nullable
        static ServerPlayer pusherOf(Entity cart) {
            return cart instanceof MedCartEntity c && c.pusher() instanceof ServerPlayer sp ? sp : null;
        }

        static void release(Entity cart) {
            if (cart instanceof MedCartEntity c) c.release();
        }

        static boolean grab(Entity cart, ServerPlayer sp, boolean fromFront) {
            return cart instanceof MedCartEntity c && c.grab(sp, fromFront);
        }

        static void unseat(Entity rider) {
            MedCartEntity.unseat(rider);
        }

        static boolean pickUp(ServerPlayer carrier, LivingEntity body) {
            // Несущий ещё держит прежнее тело (его копия ушла в другое измерение) — отпустить.
            if (CarryService.isCarrying(carrier)) CarryService.dropCarried(carrier);
            return CarryService.pickUp(carrier, body);
        }

        @Nullable
        static Entity pushedCart(Player p) {
            for (MedCartEntity c : p.level().getEntitiesOfClass(MedCartEntity.class, p.getBoundingBox().inflate(4)))
                if (c.pusher() == p) return c;
            return null;
        }
    }
}
