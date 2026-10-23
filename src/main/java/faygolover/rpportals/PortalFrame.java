package faygolover.rpportals;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Плоскость портала: {@code origin} — середина нижнего края, {@code normal} — «лицо» (горизонтально, к тем, кто
 * подходит), {@code right} — вправо, если смотреть на лицо, вверх — мировая ось Y. Размер {@code width}×{@code height}.
 * <p>
 * Переход: вошёл в лицо источника — выходишь из лица цели, как из двери. Локальные координаты (x вправо, y вверх,
 * z от лица) переходят в (−x, y, −z) цели — поворот на 180° вокруг вертикали, поэтому «левое остаётся левым».
 */
public record PortalFrame(Vec3 origin, float yaw, double width, double height) {

    /** Нормаль лица: yaw как у сущностей (0 — на юг, +Z). */
    public Vec3 normal() {
        float r = yaw * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(r), 0, Mth.cos(r));
    }

    /** Вправо для того, кто стоит перед лицом и смотрит на портал. */
    public Vec3 right() {
        Vec3 n = normal();
        return new Vec3(n.z, 0, -n.x);
    }

    /** Мировая точка → (x, y, z) в системе портала. */
    public Vec3 toLocal(Vec3 p) {
        Vec3 d = p.subtract(origin);
        Vec3 n = normal(), r = right();
        return new Vec3(d.dot(r), d.y, d.dot(n));
    }

    public Vec3 fromLocal(Vec3 l) {
        Vec3 n = normal(), r = right();
        return origin.add(r.scale(l.x)).add(0, l.y, 0).add(n.scale(l.z));
    }

    public Vec3 dirToLocal(Vec3 d) {
        Vec3 n = normal(), r = right();
        return new Vec3(d.dot(r), d.y, d.dot(n));
    }

    public Vec3 dirFromLocal(Vec3 l) {
        Vec3 n = normal(), r = right();
        return r.scale(l.x).add(0, l.y, 0).add(n.scale(l.z));
    }

    /** Точка в прямоугольнике портала (с запасом {@code pad} по краям). */
    public boolean inside(Vec3 local, double pad) {
        return Math.abs(local.x) <= width / 2 + pad && local.y >= -pad && local.y <= height + pad;
    }

    // ---------------------------------------------------------------- перенос источник → цель
    public static Vec3 mapPoint(PortalFrame from, PortalFrame to, Vec3 p) {
        Vec3 l = from.toLocal(p);
        return to.fromLocal(new Vec3(-l.x, l.y, -l.z));
    }

    public static Vec3 mapDir(PortalFrame from, PortalFrame to, Vec3 d) {
        Vec3 l = from.dirToLocal(d);
        return to.dirFromLocal(new Vec3(-l.x, l.y, -l.z));
    }

    /** Насколько поворачивается взгляд при переходе, градусы. */
    public static float yawDelta(PortalFrame from, PortalFrame to) {
        return Mth.wrapDegrees(to.yaw - from.yaw + 180f);
    }

    /** Матрица мирового переноса {@code from} → {@code to} (для отрисовки той стороны). */
    public static Matrix4f matrix(PortalFrame from, PortalFrame to) {
        Matrix4f m = new Matrix4f();
        m.translate((float) to.origin.x, (float) to.origin.y, (float) to.origin.z);
        m.rotateY(-yawDelta(from, to) * Mth.DEG_TO_RAD);
        m.translate((float) -from.origin.x, (float) -from.origin.y, (float) -from.origin.z);
        return m;
    }
}
