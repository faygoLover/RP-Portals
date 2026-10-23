package faygolover.rpportals.client;

import faygolover.rpportals.PortalBlockEntity;
import faygolover.rpportals.PortalClientSet;
import faygolover.rpportals.PortalFrame;
import faygolover.rpportals.PortalNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

/**
 * Клиент: переход через портал. Бесшовно: в конце тика игрока клиент проверяет, пересёк ли он плоскость, и сразу
 * переносит себя сам — место, взгляд и скорость, и «прошлые» значения для плавной отрисовки, — а серверу сообщает,
 * где пересёк. Сервер ставит игрока туда же и подтверждает без рывка ({@code PortalServer}). В другое измерение —
 * обычный перенос сервером, с затемнением.
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
        if (p.isPassenger() || p.isSpectator()) return;
        Vec3 now = p.position().add(0, 0.1, 0);
        Vec3 before = new Vec3(p.xo, p.yo + 0.1, p.zo);
        for (PortalBlockEntity be : PortalClientSet.snapshot()) {
            if (be.isRemoved() || be.getLevel() != mc.level || !be.linked()) continue;
            PortalFrame f = be.frame();
            double reach = Math.max(be.width, be.height) + 3;
            if (now.distanceToSqr(f.origin()) > reach * reach) continue;
            if (!be.crossed(f, before, now)) continue;
            cross(p, be, f);
            cooldown = 5;
            return;
        }
    }

    private static void cross(LocalPlayer p, PortalBlockEntity be, PortalFrame f) {
        PortalFrame t = be.linkFrame;
        Vec3 now = p.position();
        boolean sameDim = be.linkDim.equals(p.level().dimension());
        PortalNet.CHANNEL.sendToServer(new PortalNet.Cross(be.getBlockPos(), now, p.getYRot(), p.getXRot(), sameDim));
        if (!sameDim) {
            fadeTicks = 12;
            return;
        }
        float dy = PortalFrame.yawDelta(f, t);
        Vec3 dest = PortalFrame.mapPoint(f, t, now);
        Vec3 prev = PortalFrame.mapPoint(f, t, new Vec3(p.xo, p.yo, p.zo));
        Vec3 old = PortalFrame.mapPoint(f, t, new Vec3(p.xOld, p.yOld, p.zOld));
        Vec3 vel = PortalFrame.mapDir(f, t, p.getDeltaMovement());
        p.setPos(dest.x, dest.y, dest.z);
        p.xo = prev.x;
        p.yo = prev.y;
        p.zo = prev.z;
        p.xOld = old.x;
        p.yOld = old.y;
        p.zOld = old.z;
        p.setDeltaMovement(vel);
        p.setYRot(p.getYRot() + dy);
        p.yRotO += dy;
        p.yBodyRot += dy;
        p.yBodyRotO += dy;
        p.yHeadRot += dy;
        p.yHeadRotO += dy;
        p.yBob += dy;
        p.yBobO += dy;
    }

    static void clear() {
        cooldown = 0;
        fadeTicks = 0;
    }
}
