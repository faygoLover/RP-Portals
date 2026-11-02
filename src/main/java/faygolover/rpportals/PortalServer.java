package faygolover.rpportals;

import faygolover.rpcore.gm.GmTeleport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Сервер: подтвердить переход игрока.
 * <p>
 * Бесшовно (то же измерение): клиент уже перенёс себя сам. Сервер ставит игрока туда же у себя ({@code moveTo}, без
 * пакета) и шлёт телепорт туда же с относительными флагами — сдвиг для клиента нулевой, он ничего не двигает, но сервер принимает его
 * новые координаты. Если сервер не согласен (нет портала, далеко, закрыто) — возвращает игрока на своё место.
 * <p>
 * Вместе с игроком идут его попутчики (RP Medicine): тело, которое он несёт, и каталка, которую он везёт, — с
 * пассажиром. Если плоскость первым пересекло несомое тело, ведущим считается несущий. Попутчики ставятся так же
 * относительно портала, как стояли у входа.
 */
public final class PortalServer {
    private PortalServer() {}

    private static final EnumSet<RelativeMovement> RELATIVE = EnumSet.allOf(RelativeMovement.class);

    static void onCross(ServerPlayer sp, PortalNet.Cross m) {
        ServerLevel level = sp.serverLevel();
        BlockPos pos = m.pos();
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof PortalBlockEntity be) || !be.linked()) {
            resync(sp);
            return;
        }
        PortalFrame f = be.frame();
        double reach = Math.max(be.width, be.height) + 4;
        Vec3 at = m.before().distanceToSqr(sp.position()) <= 16 ? m.before() : sp.position();
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

    /** Перенести {@code sp} (пересёк {@code f} в точке {@code at}, смотря {@code yaw}/{@code pitch}) и его попутчиков. */
    public static void cross(ServerPlayer sp, ServerLevel to, PortalFrame f, PortalFrame t, Vec3 at, float yaw0, float pitch,
                      boolean predicted, int cooldown) {
        ServerLevel level = sp.serverLevel();
        // Попутчики снимаются до переноса: пока все стоят у входа.
        List<Entity> group = group(sp);
        Vec3 dest = PortalFrame.mapPoint(f, t, at);
        float yaw = yaw0 + PortalFrame.yawDelta(f, t);
        if (to == level && predicted) arrivePredicted(sp, f, t, dest, yaw, pitch, cooldown);
        else if (GmTeleport.teleport(sp, to, dest, yaw, pitch, false, cooldown) && to == level) settle(sp);
        for (Entity e : group) carry(e, level, to, f, t, cooldown);
        if (to == level) jump(sp);
    }

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

    /**
     * Перенести попутчика через портал {@code f → t} на то же место относительно портала. Игрок — и в другое
     * измерение (относительным сдвигом в своём: его клиент не дёргается); сущности (каталка с пассажирами, тело) —
     * только в своём измерении, иначе остаются (Medicine сам отпустит).
     */
    static boolean carry(Entity e, ServerLevel from, ServerLevel to, PortalFrame f, PortalFrame t, int cooldown) {
        if (e.isRemoved() || e.level() != from) return false;
        float yaw = e.getYRot() + PortalFrame.yawDelta(f, t);
        Vec3 dest = PortalFrame.mapPoint(f, t, e.position());
        Vec3 vel = PortalFrame.mapDir(f, t, e.getDeltaMovement());
        if (e instanceof ServerPlayer p) {
            if (to != from) return GmTeleport.teleport(p, to, dest, yaw, p.getXRot(), false, cooldown);
            Vec3 safe = GmTeleport.safeSpot(p, to, dest);
            p.connection.teleport(safe.x, safe.y, safe.z, yaw, p.getXRot(), RELATIVE);
            settle(p);
            p.resetFallDistance();
            GmTeleport.setCooldown(p, cooldown);
            jump(p);
            return true;
        }
        if (to != from) return false;
        Vec3 safe = GmTeleport.safeSpot(e, to, dest);
        e.teleportTo(safe.x, safe.y, safe.z);   // пассажиры — следом (teleportPassengers)
        e.setYRot(yaw);
        e.setDeltaMovement(vel);
        e.resetFallDistance();
        GmTeleport.setCooldown(e, cooldown);
        jump(e);
        for (Entity r : e.getIndirectPassengers()) {
            GmTeleport.setCooldown(r, cooldown);
            if (r instanceof ServerPlayer rp) settle(rp);
        }
        return true;
    }

    /**
     * Видящим сущность (кроме неё самой) — поставить её на новое место сразу, без трёх тиков скольжения через полкарты. Везомая
     * каталка у клиентов пакеты движения не слушает (едет за везущим) — без этого она ползла бы к нему по 0,7 блока.
     */
    static void jump(Entity e) {
        PortalNet.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> e), new PortalNet.Jump(e.getId(), e.getX(), e.getY(), e.getZ(), e.getYRot()));
    }

    /** Вернуть клиенту место, где его видит сервер. */
    private static void resync(ServerPlayer sp) {
        sp.connection.teleport(sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot());
    }
}
