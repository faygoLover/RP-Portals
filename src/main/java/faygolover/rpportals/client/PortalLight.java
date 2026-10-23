package faygolover.rpportals.client;

import faygolover.flrender.render.WorldLight;
import faygolover.rpportals.PortalBlockEntity;
import faygolover.rpportals.PortalClientSet;
import faygolover.rpportals.PortalFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Свет светящихся порталов: окружающие поверхности подкрашиваются цветом портала с затуханием на {@code lightRange}
 * блоков (за лицом — слабее). Рисует общий проход цветного света FL Render ({@link WorldLight}) вместе со светом
 * аномалий: портал — прямоугольный источник размером с проём.
 */
final class PortalLight {
    private PortalLight() {}

    /** Сила в общем проходе: у прежнего своего прохода добавка цвета была вдвое сильнее, без учёта поворота поверхности. */
    private static final float STRENGTH = 2.5f;

    static void collect(float partial, Vec3 cam, WorldLight.Sink sink) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (PortalBlockEntity be : PortalClientSet.snapshot()) {
            if (be.isRemoved() || be.getLevel() != mc.level || !be.glow || be.lightPower <= 0f || be.lightRange <= 0f) continue;
            if ("invisible".equals(be.look) && be.fog <= 0f) continue;
            PortalFrame f = be.frame();
            int rgb = be.rgb();
            sink.rect(f.origin(), f.normal(), (float) f.width(), (float) f.height(), "both".equals(be.sides), be.lightRange,
                    (rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, be.lightPower * STRENGTH);
        }
    }
}
