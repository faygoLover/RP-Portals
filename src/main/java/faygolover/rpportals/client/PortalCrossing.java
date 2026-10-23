package faygolover.rpportals.client;

import faygolover.rpportals.PortalBlockEntity;
import faygolover.rpportals.PortalClientSet;
import faygolover.rpportals.PortalFrame;
import faygolover.rpportals.PortalNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Клиент: переход через портал и режим «держать ПКМ».
 * <p>
 * Переход бесшовный: в конце тика игрока клиент проверяет, пересёк ли он плоскость, и сразу переносит себя сам —
 * место, взгляд и скорость, и «прошлые» значения для плавной отрисовки, — а серверу сообщает, где пересёк. Сервер
 * ставит игрока туда же и подтверждает без рывка ({@code PortalServer}). В другое измерение — обычный перенос
 * сервером, с затемнением.
 */
public final class PortalCrossing {
    private PortalCrossing() {}

    private static int cooldown;
    /** Портал → до какого тика клиента проход открыт (подержал ПКМ). */
    private static final Map<BlockPos, Long> OPEN = new HashMap<>();
    private static long clientTicks;
    static float holdProgress;
    @Nullable static PortalBlockEntity holdTarget;
    static int fadeTicks;

    static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        clientTicks++;
        if (fadeTicks > 0) fadeTicks--;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            OPEN.clear();
            holdTarget = null;
            holdProgress = 0;
            return;
        }
        hold(mc, p);
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
            if ("hold".equals(be.activation) && OPEN.getOrDefault(be.getBlockPos(), -1L) < clientTicks) continue;
            cross(mc, p, be, f);
            cooldown = 5;
            return;
        }
    }

    private static void cross(Minecraft mc, LocalPlayer p, PortalBlockEntity be, PortalFrame f) {
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

    // ---------------------------------------------------------------- «держать ПКМ»
    private static void hold(Minecraft mc, LocalPlayer p) {
        PortalBlockEntity aimed = aimedHoldPortal(mc, p);
        if (aimed == null || OPEN.getOrDefault(aimed.getBlockPos(), -1L) >= clientTicks || !mc.options.keyUse.isDown()) {
            holdTarget = aimed;
            holdProgress = Math.max(0, holdProgress - 0.15f);
            if (aimed == null) holdProgress = 0;
            return;
        }
        if (holdTarget != aimed) holdProgress = 0;
        holdTarget = aimed;
        holdProgress += 1f / Math.max(1f, aimed.holdSeconds * 20f);
        if (holdProgress >= 1f) {
            holdProgress = 0;
            OPEN.put(aimed.getBlockPos(), clientTicks + (long) (aimed.openSeconds * 20));
            PortalNet.CHANNEL.sendToServer(new PortalNet.Activate(aimed.getBlockPos()));
        }
    }

    /** Портал с «держать ПКМ», на который смотрит игрок (до 4,5 блоков; дверь в проёме не мешает). */
    @Nullable
    static PortalBlockEntity aimedHoldPortal(Minecraft mc, LocalPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        double blockDist = mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK
                ? bh.getLocation().distanceTo(eye) : 99;
        PortalBlockEntity best = null;
        double bestT = 4.5;
        for (PortalBlockEntity be : PortalClientSet.snapshot()) {
            if (be.isRemoved() || be.getLevel() != mc.level || !"hold".equals(be.activation)) continue;
            PortalFrame f = be.frame();
            Vec3 e = f.toLocal(eye), d = f.dirToLocal(look);
            if (Math.abs(d.z) < 1e-4) continue;
            double t = -e.z / d.z;
            if (t <= 0 || t > bestT || t > blockDist + 1.5) continue;
            Vec3 hit = e.add(d.scale(t));
            if (!f.inside(hit, 0.1)) continue;
            best = be;
            bestT = t;
        }
        return best;
    }

    /** ПКМ по порталу «держать» не открывает дверь сам по себе и не машет рукой: дверь откроет портал. */
    static void onInteract(InputEvent.InteractionKeyMappingTriggered e) {
        if (!e.isUseItem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && aimedHoldPortal(mc, mc.player) != null) {
            e.setCanceled(true);
            e.setSwingHand(false);
        }
    }

    static void clear() {
        OPEN.clear();
        holdTarget = null;
        holdProgress = 0;
        cooldown = 0;
    }
}
