package faygolover.rpportals;

import faygolover.rpcore.gm.GmTeleport;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Сервер: подтвердить переход игрока.
 * <p>
 * Бесшовно (то же измерение): клиент уже перенёс себя сам. Сервер ставит игрока туда же у себя ({@code moveTo}, без
 * пакета) и шлёт телепорт туда же с относительными флагами — сдвиг для клиента нулевой, он ничего не двигает, но сервер принимает его
 * новые координаты. Если сервер не согласен (нет портала, далеко, закрыто) — возвращает игрока на своё место.
 * <p>
 * Вместе с игроком идут его попутчики (RP Medicine), всегда разом и в любое измерение: каталка или кресло, которое
 * он везёт, — с пассажиром, и тело, которое он тащит. Опора — везущий: каталка встаёт перед ним на длине рук. Если
 * плоскость первым пересекло тащимое тело, ведущим считается несущий. Кресло, которым правит сам сидящий, переходит
 * целиком: клиент сидящего переносит его сам, как игрока ({@code PortalNet.Cross#vehicle}).
 */
public final class PortalServer {
    private PortalServer() {}

    private static final EnumSet<RelativeMovement> RELATIVE = EnumSet.allOf(RelativeMovement.class);

    static void onCross(ServerPlayer sp, PortalNet.Cross m) {
        ServerLevel level = sp.serverLevel();
        BlockPos pos = m.pos();
        Entity mover = m.vehicle() ? selfDriven(sp) : sp;
        if (mover == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof PortalBlockEntity be) || !be.linked()) {
            resync(sp);
            return;
        }
        PortalFrame f = be.frame();
        double reach = Math.max(be.width, be.height) + 4;
        Vec3 at = m.before().distanceToSqr(mover.position()) <= 16 ? m.before() : mover.position();
        if (at.distanceToSqr(f.origin().add(0, be.height / 2, 0)) > reach * reach) {
            resync(sp);
            return;
        }
        ServerLevel to = level.getServer().getLevel(be.linkDim);
        if (to == null) {
            resync(sp);
            return;
        }
        cross(sp, to, f, be.linkFrame, at, m.yaw(), m.pitch(), m.predicted(), be.cooldown);
    }

    /**
     * Перенести {@code sp} и его попутчиков: {@code sp} пересёк {@code f} в точке {@code at}, смотря {@code yaw}/{@code pitch}.
     * Если {@code sp} сам правит креслом, {@code at} — место кресла, и переходит кресло с сидящими.
     */
    public static void cross(ServerPlayer sp, ServerLevel to, PortalFrame f, PortalFrame t, Vec3 at, float yaw0, float pitch,
                             boolean predicted, int cooldown) {
        Entity drive = selfDriven(sp);
        if (drive != null) {
            crossDriven(sp, drive, to, f, t, at, predicted, cooldown);
            return;
        }
        ServerLevel level = sp.serverLevel();
        // Попутчики, кто какую каталку везёт и как держит — до переноса, пока все стоят у входа (после перехода в
        // другое измерение каталка своего везущего уже не видит).
        List<Entity> group = group(sp);
        List<CartHold> carts = new ArrayList<>();
        for (Entity e : group) {
            ServerPlayer p = MedicineCompat.isCart(e) ? MedicineCompat.pusherOf(e) : null;
            if (p != null) carts.add(new CartHold(e, p, MedicineCompat.heldFromFront(e, p)));
        }
        Vec3 dest = PortalFrame.mapPoint(f, t, at);
        float yaw = yaw0 + PortalFrame.yawDelta(f, t);
        if (to == level && predicted) arrivePredicted(sp, f, t, dest, yaw, pitch, cooldown);
        else if (GmTeleport.teleport(sp, to, dest, yaw, pitch, false, cooldown) && to == level) settle(sp);
        if (to == level) jump(sp);
        // Сначала игроки и тела, потом каталки — их место считается от уже перенесённого везущего.
        for (Entity e : group) if (!MedicineCompat.isCart(e)) carry(e, level, to, f, t, cooldown);
        for (CartHold h : carts) cartToPusher(h.cart, h.pusher, level, h.fromFront, cooldown);
    }

    private record CartHold(Entity cart, ServerPlayer pusher, boolean fromFront) {}

    /**
     * Клиент уже перенёс себя сам — сервер ставит игрока в {@code dest}. Сначала у себя, потом «телепорт» в ту же
     * точку с относительными флагами: сервер считает сдвиг от текущего места — он нулевой, клиент не двигается.
     * Если точка прибытия в ковре или плите — подъём на их верх: относительный сдвиг (0, подъём, 0) уходит и клиенту.
     */
    static void arrivePredicted(ServerPlayer sp, PortalFrame f, PortalFrame t, Vec3 dest, float yaw, float pitch, int cooldown) {
        Vec3 safe = GmTeleport.safeSpot(sp, sp.serverLevel(), dest);
        sp.moveTo(dest.x, dest.y, dest.z, yaw, pitch);
        sp.setDeltaMovement(PortalFrame.mapDir(f, t, sp.getDeltaMovement()));
        sp.connection.teleport(safe.x, safe.y, safe.z, yaw, pitch, RELATIVE);
        settle(sp);
        sp.resetFallDistance();
        GmTeleport.setCooldown(sp, cooldown);
    }

    /**
     * Проверка скорости сервера считает сдвиг от точки начала тика (firstGood), а {@code teleport} её не трогает:
     * подтверждение и шаг клиента в том же окне между тиками давали «moved too quickly» на всё расстояние между
     * порталами и откат. Точка отсчёта — новое место.
     */
    static void settle(ServerPlayer sp) {
        sp.connection.resetPosition();
    }

    /** Кто идёт вместе с {@code sp}: его попутчики, а если его самого несут — несущий и остальные попутчики несущего. */
    static List<Entity> group(ServerPlayer sp) {
        List<Entity> out = new ArrayList<>(MedicineCompat.companions(sp));
        ServerPlayer carrier = MedicineCompat.carrierOf(sp);
        if (carrier != null) {
            out.add(carrier);
            for (Entity e : MedicineCompat.companions(carrier)) if (e != sp && !out.contains(e)) out.add(e);
        }
        return out;
    }

    /** Кресло (каталка), которым правит сам {@code sp}: он сидит, никто не везёт. */
    @Nullable
    static Entity selfDriven(ServerPlayer sp) {
        Entity v = sp.getRootVehicle();
        return v != sp && MedicineCompat.isCart(v) && v.getControllingPassenger() == sp ? v : null;
    }

    /**
     * Перенести игрока или тело через портал {@code f → t} на то же место относительно портала. Игрок в своём
     * измерении — относительным сдвигом (его клиент не дёргается). Тело-сущность в другое измерение — копией там,
     * и несущий снова берёт его волоком.
     */
    static void carry(Entity e, ServerLevel from, ServerLevel to, PortalFrame f, PortalFrame t, int cooldown) {
        if (e.isRemoved() || e.level() != from) return;
        float yaw = e.getYRot() + PortalFrame.yawDelta(f, t);
        Vec3 dest = PortalFrame.mapPoint(f, t, e.position());
        Vec3 vel = PortalFrame.mapDir(f, t, e.getDeltaMovement());
        if (e instanceof ServerPlayer p) {
            if (to != from) {
                GmTeleport.teleport(p, to, dest, yaw, p.getXRot(), false, cooldown);
                return;
            }
            Vec3 safe = GmTeleport.safeSpot(p, to, dest);
            p.connection.teleport(safe.x, safe.y, safe.z, yaw, p.getXRot(), RELATIVE);
            settle(p);
            p.resetFallDistance();
            GmTeleport.setCooldown(p, cooldown);
            jump(p);
            return;
        }
        Vec3 safe = GmTeleport.safeSpot(e, to, dest);
        if (to != from) {
            ServerPlayer carrier = MedicineCompat.carrierOf(e);
            Entity moved = transfer(e, to, safe, yaw);
            if (moved != null) {
                GmTeleport.setCooldown(moved, cooldown);
                if (carrier != null && carrier.level() == to) MedicineCompat.pickUp(carrier, moved);
            }
            return;
        }
        e.teleportTo(safe.x, safe.y, safe.z);   // пассажиры — следом (teleportPassengers)
        e.setYRot(yaw);
        e.setDeltaMovement(vel);
        e.resetFallDistance();
        GmTeleport.setCooldown(e, cooldown);
        jump(e);
    }

    /**
     * Каталку — к уже перенесённому везущему: перед ним на длине рук, по его взгляду (спереди держит — лицом к нему),
     * с пассажирами. В другое измерение: отпустить, снять пассажиров, перенести всех, усадить и взяться снова.
     */
    static void cartToPusher(Entity cart, ServerPlayer pusher, ServerLevel from, boolean fromFront, int cooldown) {
        if (cart.isRemoved() || cart.level() != from) return;
        ServerLevel to = pusher.serverLevel();
        float yaw = pusher.getYRot() + (fromFront ? 180f : 0f);
        Vec3 safe = GmTeleport.safeSpot(cart, to, MedicineCompat.cartSpot(cart, pusher.position(), pusher.getYRot()));
        if (to == from) {
            cart.teleportTo(to, safe.x, safe.y, safe.z, Set.of(), yaw, cart.getXRot());   // пассажиры — следом
            cart.setDeltaMovement(Vec3.ZERO);
            cart.resetFallDistance();
            arrived(cart, cooldown);
            return;
        }
        List<Entity> riders = new ArrayList<>(cart.getPassengers());
        MedicineCompat.release(cart);
        Entity moved = moveWithRiders(cart, riders, to, safe, yaw, cooldown);
        if (moved != null) MedicineCompat.grab(moved, pusher, fromFront);
    }

    /**
     * Кресло, которым правит сам сидящий {@code sp}, пересекло портал в точке {@code at}. В своём измерении клиент
     * сидящего уже перенёс кресло сам ({@code predicted}) — сервер ставит туда же и сбрасывает свою точку отсчёта
     * для проверки скорости транспорта; в другое — переносит кресло и сидящих и усаживает снова.
     */
    static void crossDriven(ServerPlayer sp, Entity cart, ServerLevel to, PortalFrame f, PortalFrame t, Vec3 at,
                            boolean predicted, int cooldown) {
        ServerLevel level = sp.serverLevel();
        float yaw = cart.getYRot() + PortalFrame.yawDelta(f, t);
        Vec3 dest = PortalFrame.mapPoint(f, t, at);
        Vec3 safe = GmTeleport.safeSpot(cart, to, dest);
        if (to == level) {
            cart.teleportTo(to, safe.x, safe.y, safe.z, Set.of(), yaw, cart.getXRot());
            cart.setDeltaMovement(PortalFrame.mapDir(f, t, cart.getDeltaMovement()));
            cart.resetFallDistance();
            settleVehicle(sp, cart);
            // Клиент сидящего кресло ведёт сам и пакеты сервера о нём не слушает — подъём из ковра или переход без
            // предсказания довести до него явно.
            if (!predicted || safe.distanceToSqr(dest) > 1e-6) sp.connection.send(new ClientboundMoveVehiclePacket(cart));
            arrived(cart, cooldown);
            return;
        }
        moveWithRiders(cart, new ArrayList<>(cart.getPassengers()), to, safe, yaw, cooldown);
    }

    /** В другое измерение: снять пассажиров, перенести их и каталку в {@code pos}, усадить снова. */
    @Nullable
    private static Entity moveWithRiders(Entity cart, List<Entity> riders, ServerLevel to, Vec3 pos, float yaw, int cooldown) {
        for (Entity r : riders) MedicineCompat.unseat(r);
        List<Entity> movedRiders = new ArrayList<>();
        for (Entity r : riders) {
            Entity m = r instanceof ServerPlayer rp
                    ? (GmTeleport.teleport(rp, to, pos, rp.getYRot(), rp.getXRot(), false, cooldown) ? rp : null)
                    : transfer(r, to, pos, r.getYRot());
            if (m != null) movedRiders.add(m);
        }
        Entity moved = transfer(cart, to, pos, yaw);
        if (moved == null) return null;
        GmTeleport.setCooldown(moved, cooldown);
        for (Entity r : movedRiders) {
            r.startRiding(moved, true);
            GmTeleport.setCooldown(r, cooldown);
        }
        return moved;
    }

    /**
     * Сущность (не игрок) — в другое измерение: копия там (тот же UUID и данные), эта убирается. Как ванильный
     * {@code Entity.teleportTo(ServerLevel, ...)}, но с копией на руках — её надо усадить и взять снова.
     */
    @Nullable
    private static Entity transfer(Entity e, ServerLevel to, Vec3 pos, float yaw) {
        to.getChunkAt(BlockPos.containing(pos));   // точка прибытия загружена — копия там сразу живая
        Entity copy = e.getType().create(to);
        if (copy == null) return null;
        copy.restoreFrom(e);
        copy.moveTo(pos.x, pos.y, pos.z, yaw, e.getXRot());
        copy.setYHeadRot(yaw);
        copy.setDeltaMovement(Vec3.ZERO);
        e.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
        to.addDuringTeleport(copy);
        return copy;
    }

    /** Каталка встала на новое место: передышка от порталов и пластин, видящим — прыжок, игрокам-пассажирам — точка отсчёта. */
    private static void arrived(Entity cart, int cooldown) {
        GmTeleport.setCooldown(cart, cooldown);
        jump(cart);
        for (Entity r : cart.getIndirectPassengers()) {
            GmTeleport.setCooldown(r, cooldown);
            if (r instanceof ServerPlayer rp) settle(rp);
        }
    }

    private static final String[] VEHICLE_GOOD = {"f_9760_", "f_9761_", "f_9762_", "f_9763_", "f_9764_", "f_9765_"};
    @Nullable private static Field[] vehicleGood;

    /**
     * То же для транспорта: проверка скорости кресла считает от vehicleFirstGood (ставится в начале тика), и шаг
     * клиента сразу после перехода ушёл бы в «(vehicle of …) moved too quickly». Поля приватные — по SRG-именам.
     */
    static void settleVehicle(ServerPlayer sp, Entity v) {
        try {
            if (vehicleGood == null) {
                Field[] fs = new Field[VEHICLE_GOOD.length];
                for (int i = 0; i < fs.length; i++)
                    fs[i] = ObfuscationReflectionHelper.findField(ServerGamePacketListenerImpl.class, VEHICLE_GOOD[i]);
                vehicleGood = fs;
            }
            double[] xyz = {v.getX(), v.getY(), v.getZ()};
            for (int i = 0; i < vehicleGood.length; i++) vehicleGood[i].setDouble(sp.connection, xyz[i % 3]);
        } catch (RuntimeException | IllegalAccessException ex) {
            RpPortals.LOGGER.debug("rpportals: vehicle reference point not reset", ex);
        }
        settle(sp);
    }

    /**
     * Видящим сущность (кроме неё самой) — поставить её на новое место сразу, без трёх тиков скольжения через полкарты. Везомая
     * каталка у клиентов пакеты движения не слушает (едет за везущим) — без этого она ползла бы к нему по 0,7 блока.
     */
    static void jump(Entity e) {
        PortalNet.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> e), new PortalNet.Jump(e.getId(), e.getX(), e.getY(), e.getZ(), e.getYRot()));
    }

    /** Вернуть клиенту место, где его видит сервер (и кресло, если он им правит). */
    private static void resync(ServerPlayer sp) {
        Entity v = selfDriven(sp);
        if (v != null) sp.connection.send(new ClientboundMoveVehiclePacket(v));
        else sp.connection.teleport(sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot());
    }
}
