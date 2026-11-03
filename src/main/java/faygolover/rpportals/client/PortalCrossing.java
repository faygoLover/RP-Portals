package faygolover.rpportals.client;

import faygolover.rpportals.PortalBlockEntity;
import faygolover.rpportals.PortalClientSet;
import faygolover.rpportals.PortalFrame;
import faygolover.rpportals.MedicineCompat;
import faygolover.rpportals.PortalNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

/**
 * Клиент: переход через портал. Бесшовно: в конце тика игрока клиент проверяет, пересёк ли он плоскость, и сразу
 * переносит себя сам — место, взгляд и скорость, и «прошлые» значения для плавной отрисовки, — а серверу сообщает,
 * где пересёк. Сервер ставит игрока туда же и подтверждает без рывка ({@code PortalServer}). В другое измерение —
 * обычный перенос сервером, с затемнением. Каталку, которую игрок везёт (RP Medicine), клиент ставит перед собой;
 * кресло, которым игрок правит сам, переносит, как себя.
 */
public final class PortalCrossing {
    private PortalCrossing() {}

    private static int cooldown;
    static int fadeTicks;

    static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (fadeTicks > 0) fadeTicks--;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (p.isSpectator()) return;
        // Сидит — переходит только кресло, которым он правит сам (его ведёт этот клиент); остальной транспорт — нет.
        Entity mover = p;
        if (p.isPassenger()) {
            Entity v = p.getRootVehicle();
            if (!MedicineCompat.isCart(v) || !v.isControlledByLocalInstance()) return;
            mover = v;
        }
        Vec3 now = mover.position().add(0, 0.1, 0);
        Vec3 before = new Vec3(mover.xo, mover.yo + 0.1, mover.zo);
        for (PortalBlockEntity be : PortalClientSet.snapshot()) {
            if (be.isRemoved() || be.getLevel() != mc.level || !be.linked()) continue;
            PortalFrame f = be.frame();
            double reach = Math.max(be.width, be.height) + 3;
            if (now.distanceToSqr(f.origin()) > reach * reach) continue;
            if (!be.crossed(f, before, now)) continue;
            cross(p, mover, be, f);
            cooldown = 5;
            return;
        }
    }

    private static void cross(LocalPlayer p, Entity mover, PortalBlockEntity be, PortalFrame f) {
        PortalFrame t = be.linkFrame;
        boolean vehicle = mover != p;
        boolean sameDim = be.linkDim.equals(p.level().dimension());
        PortalNet.CHANNEL.sendToServer(new PortalNet.Cross(be.getBlockPos(), mover.position(), p.getYRot(), p.getXRot(), sameDim, vehicle));
        if (!sameDim) {
            fadeTicks = 12;
            return;
        }
        float dy = PortalFrame.yawDelta(f, t);
        Entity cart = vehicle ? null : MedicineCompat.pushedCart(p);
        boolean front = cart != null && MedicineCompat.heldFromFront(cart, p);
        if (vehicle) shift(mover, f, t, dy);
        shift(p, f, t, dy);
        p.yBob += dy;
        p.yBobO += dy;
        // Везомая каталка едет за везущим сама (пакеты движения не слушает) — поставить её перед ним, как ставит
        // сервер; сервер поправит.
        if (cart != null) {
            Vec3 c = MedicineCompat.cartSpot(cart, p.position(), p.getYRot());
            place(cart, c.x, c.y, c.z, p.getYRot() + (front ? 180f : 0f));
        }
    }

    /** Перенести сущность через портал {@code f → t}: место, «прошлые» места (плавный кадр), скорость, взгляд. */
    private static void shift(Entity e, PortalFrame f, PortalFrame t, float dy) {
        Vec3 dest = PortalFrame.mapPoint(f, t, e.position());
        Vec3 prev = PortalFrame.mapPoint(f, t, new Vec3(e.xo, e.yo, e.zo));
        Vec3 old = PortalFrame.mapPoint(f, t, new Vec3(e.xOld, e.yOld, e.zOld));
        e.setDeltaMovement(PortalFrame.mapDir(f, t, e.getDeltaMovement()));
        e.setPos(dest.x, dest.y, dest.z);
        e.xo = prev.x;
        e.yo = prev.y;
        e.zo = prev.z;
        e.xOld = old.x;
        e.yOld = old.y;
        e.zOld = old.z;
        e.setYRot(e.getYRot() + dy);
        e.yRotO += dy;
        if (e instanceof LivingEntity le) {
            le.yBodyRot += dy;
            le.yBodyRotO += dy;
            le.yHeadRot += dy;
            le.yHeadRotO += dy;
        }
    }

    /** Сервер: сущность прошла портал — сразу туда, без скольжения и без интерполяции через полкарты. */
    public static void onJump(PortalNet.Jump m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(m.id());
        // Себя и кресло, которым правит сам, клиент уже перенёс.
        if (e == null || e == mc.player || (e == mc.player.getRootVehicle() && e.isControlledByLocalInstance())) return;
        place(e, m.x(), m.y(), m.z(), m.yaw());
        // Ожидающая плавная подводка (пакеты до переноса) — в ту же точку.
        if (e instanceof LivingEntity le) le.lerpTo(m.x(), m.y(), m.z(), m.yaw(), le.getXRot(), 1, true);
    }

    private static void place(Entity e, double x, double y, double z, float yaw) {
        e.syncPacketPositionCodec(x, y, z);
        e.setPos(x, y, z);
        e.xo = e.xOld = x;
        e.yo = e.yOld = y;
        e.zo = e.zOld = z;
        float dy = yaw - e.getYRot();
        e.setYRot(yaw);
        e.yRotO += dy;
        if (e instanceof LivingEntity le) {
            le.yBodyRot += dy;
            le.yBodyRotO += dy;
            le.yHeadRot += dy;
            le.yHeadRotO += dy;
        }
    }

    static void clear() {
        cooldown = 0;
        fadeTicks = 0;
    }
}
