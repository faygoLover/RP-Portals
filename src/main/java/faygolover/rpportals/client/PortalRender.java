package faygolover.rpportals.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import faygolover.rpcore.gm.GmTools;
import faygolover.rpportals.PortalBlockEntity;
import faygolover.rpportals.PortalClientSet;
import faygolover.rpportals.PortalFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Поверхность порталов. Без шейдерпака — после полупрозрачных блоков (в общей глубине мира); с шейдерпаком (Oculus) —
 * после его финального прохода, поверх готового кадра (иначе Oculus подменяет наши шейдеры). Свой шейдер
 * {@code rpportals:portal}: форма, цвет или живой вид, туман, рябь, обод. Ведущему с меткой — контур плоскости и
 * стрелка «лица».
 */
public final class PortalRender {
    private PortalRender() {}

    static ShaderInstance shader;

    static void onRenderLevel(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS && GmTools.holdsMarker(mc.player)) gmOutlines(e, level);
        // Без шейдерпака — после блок-сущностей: этот этап вызывает сам Minecraft (не внутри отрисовки чанков
        // Embeddium с чужим состоянием), и он раньше полупрозрачных блоков — их невидимые части (двери SCPO) не
        // обрезают портал.
        RenderLevelStageEvent.Stage want = faygolover.flrender.api.FlRender.shaderPack() ? RenderLevelStageEvent.Stage.AFTER_LEVEL
                : RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (e.getStage() != want || shader == null) return;
        Vec3 cam = e.getCamera().getPosition();
        float partial = e.getPartialTick();
        // На AFTER_LEVEL Forge отдаёт стек с проекцией, а не с поворотом камеры — поворот строим сами.
        PoseStack pose = want == RenderLevelStageEvent.Stage.AFTER_LEVEL ? faygolover.flrender.render.CameraPose.of(e.getCamera())
                : e.getPoseStack();
        for (PortalBlockEntity be : PortalClientSet.snapshot()) {
            if (be.isRemoved() || be.getLevel() != level) continue;
            boolean plain = "invisible".equals(be.look) && be.fog <= 0 && "none".equals(be.rim);
            if (plain) continue;
            PortalFrame f = be.frame();
            if (f.origin().distanceToSqr(cam) > 128 * 128) continue;
            if (e.getFrustum() != null && !e.getFrustum().isVisible(PortalBlockEntity.planeBox(f))) continue;
            boolean front = f.toLocal(cam).z > 0;
            int view = -1, photo = -1;
            // Живой вид и снимок — техники FL Render; выключены (слабый ПК) — портал сплошного цвета.
            if ("live".equals(be.look) && front && PortalTechniques.LIVE_VIEW.on() && f.origin().distanceToSqr(cam) < 64 * 64) {
                if (PortalView.available(be, level)) view = PortalView.render(be, level, pose, e.getProjectionMatrix(), cam, partial);
            } else if ("photo".equals(be.look) && front && PortalTechniques.PHOTO.on()) {
                photo = PortalPhotos.texture(be.link);
            }
            draw(be, f, level, pose, cam, partial, view, photo);
        }
    }

    private static void draw(PortalBlockEntity be, PortalFrame f, ClientLevel level, PoseStack pose, Vec3 cam, float partial, int view, int photo) {
        Minecraft mc = Minecraft.getInstance();
        int rgb = be.rgb(), rim = be.rimRgb();
        boolean invisibleBase = "invisible".equals(be.look);
        float alpha = invisibleBase ? 0f : be.alpha;
        set("Color", (rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, alpha);
        set("RimColor", (rim >> 16 & 255) / 255f, (rim >> 8 & 255) / 255f, (rim & 255) / 255f, 1f);
        set("Rim", switch (be.rim) { case "thin" -> 0.04f; case "thick" -> 0.12f; default -> 0f; });
        set("Shape", switch (be.shape) { case "rounded" -> 1f; case "oval" -> 2f; default -> 0f; });
        set("Corner", be.corner);
        set("Size", (float) f.width(), (float) f.height());
        set("Time", ((level.getGameTime() % 24000L) + partial) / 20f);
        set("Ripple", be.ripple);
        set("Fog", be.fog);
        set("Light", be.glow ? 1f : light(level, f));
        set("ScreenSize", mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);
        set("HasView", view >= 0 ? 1f : 0f);
        set("Photo", photo >= 0 ? 1f : 0f);
        set("PhotoDepth", PortalView.PHOTO_RANGE);
        Vec3 cl = f.toLocal(cam);
        set("CamLocal", (float) cl.x, (float) (cl.y - f.height() / 2), (float) cl.z);

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, view >= 0 ? view : photo >= 0 ? photo : MissingTextureAtlasSprite.getTexture().getId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        boolean opaque = view >= 0 || photo >= 0 || (alpha >= 0.999f && be.fog <= 0f);
        RenderSystem.depthMask(opaque);
        RenderSystem.disableCull();

        Matrix4f m = pose.last().pose();
        Vec3 o = f.origin().subtract(cam);
        Vec3 r = f.right().scale(f.width() / 2);
        double h = f.height();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.vertex(m, (float) (o.x - r.x), (float) o.y, (float) (o.z - r.z)).uv(0, 0).endVertex();
        bb.vertex(m, (float) (o.x + r.x), (float) o.y, (float) (o.z + r.z)).uv(1, 0).endVertex();
        bb.vertex(m, (float) (o.x + r.x), (float) (o.y + h), (float) (o.z + r.z)).uv(1, 1).endVertex();
        bb.vertex(m, (float) (o.x - r.x), (float) (o.y + h), (float) (o.z - r.z)).uv(0, 1).endVertex();
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void set(String name, float... v) {
        var u = shader.safeGetUniform(name);
        switch (v.length) {
            case 1 -> u.set(v[0]);
            case 2 -> u.set(v[0], v[1]);
            case 3 -> u.set(v[0], v[1], v[2]);
            case 4 -> u.set(v[0], v[1], v[2], v[3]);
            default -> {}
        }
    }

    /** Без свечения портал темнеет вместе с местом, где стоит. */
    private static float light(ClientLevel level, PortalFrame f) {
        BlockPos p = BlockPos.containing(f.origin().add(0, Math.min(1.0, f.height() / 2), 0));
        int block = level.getBrightness(LightLayer.BLOCK, p);
        int sky = level.getBrightness(LightLayer.SKY, p) - level.getSkyDarken();
        return 0.12f + 0.88f * Math.max(block, Math.max(0, sky)) / 15f;
    }

    /** Ведущему: рамка плоскости и стрелка из середины по «лицу». */
    private static void gmOutlines(RenderLevelStageEvent e, ClientLevel level) {
        Vec3 cam = e.getCamera().getPosition();
        MultiBufferSource.BufferSource buf = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buf.getBuffer(RenderType.lines());
        PoseStack pose = e.getPoseStack();
        boolean any = false;
        for (PortalBlockEntity be : PortalClientSet.snapshot()) {
            if (be.isRemoved() || be.getLevel() != level) continue;
            PortalFrame f = be.frame();
            if (f.origin().distanceToSqr(cam) > 64 * 64) continue;
            Vec3 o = f.origin().subtract(cam), r = f.right().scale(f.width() / 2), up = new Vec3(0, f.height(), 0);
            Vec3 a = o.subtract(r), b = o.add(r), c = b.add(up), d = a.add(up);
            int col = be.linked() ? 0xFF60D0 : 0xFF3030;
            line(lines, pose, a, b, col);
            line(lines, pose, b, c, col);
            line(lines, pose, c, d, col);
            line(lines, pose, d, a, col);
            Vec3 mid = o.add(0, f.height() / 2, 0);
            line(lines, pose, mid, mid.add(f.normal().scale(0.75)), 0xFFFFFF);
            any = true;
        }
        if (any) buf.endBatch(RenderType.lines());
    }

    private static void line(VertexConsumer vc, PoseStack pose, Vec3 a, Vec3 b, int rgb) {
        Vec3 n = b.subtract(a).normalize();
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, bl = rgb & 255;
        var last = pose.last();
        vc.vertex(last.pose(), (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, 230).normal(last.normal(), (float) n.x, (float) n.y, (float) n.z).endVertex();
        vc.vertex(last.pose(), (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, 230).normal(last.normal(), (float) n.x, (float) n.y, (float) n.z).endVertex();
    }
}
