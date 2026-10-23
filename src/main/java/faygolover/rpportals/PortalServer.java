package faygolover.rpportals;

import faygolover.rpcore.gm.GmTeleport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Сервер: подтвердить переход игрока.
 * <p>
 * Бесшовно (то же измерение): клиент уже перенёс себя сам. Сервер ставит игрока туда же у себя ({@code moveTo}, без
 * пакета) и шлёт телепорт туда же с относительными флагами — сдвиг для клиента нулевой, он ничего не двигает, но сервер принимает его
 * новые координаты. Если сервер не согласен (нет портала, далеко, закрыто) — возвращает игрока на своё место.
 */
public final class PortalServer {
    private PortalServer() {}

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
        PortalFrame t = be.linkFrame;
        Vec3 dest = PortalFrame.mapPoint(f, t, at);
        float yaw = m.yaw() + PortalFrame.yawDelta(f, t);
        if (to == level && m.predicted()) {
            // Сначала поставить у себя, потом «телепорт» в ту же точку с относительными флагами: сервер считает сдвиг
            // от текущего места — он нулевой, клиент (уже перенёсший себя сам) не двигается.
            sp.moveTo(dest.x, dest.y, dest.z, yaw, m.pitch());
            sp.setDeltaMovement(PortalFrame.mapDir(f, t, sp.getDeltaMovement()));
            sp.connection.teleport(dest.x, dest.y, dest.z, yaw, m.pitch(), EnumSet.allOf(RelativeMovement.class));
            sp.resetFallDistance();
            GmTeleport.setCooldown(sp, be.cooldown);
        } else {
            GmTeleport.teleport(sp, to, dest, yaw, m.pitch(), false, be.cooldown);
        }
    }

    /** Вернуть клиенту место, где его видит сервер. */
    private static void resync(ServerPlayer sp) {
        sp.connection.teleport(sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot());
    }
}
