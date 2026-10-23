package faygolover.rpportals.client;

import com.mojang.blaze3d.systems.RenderSystem;
import faygolover.rpportals.PortalBlockEntity;
import faygolover.rpportals.PortalClientSet;
import faygolover.rpportals.PortalFrame;
import faygolover.rpportals.RpPortals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Свет светящихся порталов: окружающие поверхности подкрашиваются цветом портала с затуханием на {@code lightRange}
 * блоков (за лицом — слабее). Цветного света в Minecraft нет, поэтому это проход по готовому кадру: из глубины каждой
 * точки — её место, расстояние до проёма, добавка цвета. После мира (и после кадра шейдерпака), до руки и интерфейса.
 * До четырёх ближайших порталов.
 */
final class PortalLight {
    private PortalLight() {}

    private static final ResourceLocation CHAIN = new ResourceLocation(RpPortals.MODID, "shaders/post/portal_light.json");
    private static PostChain chain;
    private static int w = -1, h = -1;
    private static boolean failed;

    static void onRenderLevel(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || failed) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = e.getCamera().getPosition();
        List<PortalBlockEntity> lit = new ArrayList<>();
        for (PortalBlockEntity be : PortalClientSet.snapshot()) {
            if (be.isRemoved() || be.getLevel() != mc.level || !be.glow || be.lightPower <= 0f || be.lightRange <= 0f) continue;
            if ("invisible".equals(be.look) && be.fog <= 0f) continue;
            PortalFrame f = be.frame();
            if (f.origin().distanceTo(cam) > be.lightRange + Math.max(be.width, be.height) + 32) continue;
            lit.add(be);
        }
        if (lit.isEmpty()) return;
        lit.sort(Comparator.comparingDouble(b -> b.frame().origin().distanceToSqr(cam)));
        int n = Math.min(4, lit.size());
        Matrix4f pos = new Matrix4f().zero(), dir = new Matrix4f().zero(), col = new Matrix4f().zero(), ext = new Matrix4f().zero();
        for (int i = 0; i < n; i++) {
            PortalBlockEntity be = lit.get(i);
            PortalFrame f = be.frame();
            Vec3 o = f.origin().subtract(cam), nrm = f.normal();
            int rgb = be.rgb();
            pos.setColumn(i, new Vector4f((float) o.x, (float) o.y, (float) o.z, be.lightPower));
            dir.setColumn(i, new Vector4f((float) nrm.x, (float) nrm.y, (float) nrm.z, (float) f.width()));
            col.setColumn(i, new Vector4f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, (float) f.height()));
            ext.setColumn(i, new Vector4f(be.lightRange, "both".equals(be.sides) ? 1f : 0f, 0f, 0f));
        }
        var main = mc.getMainRenderTarget();
        try {
            if (chain == null || main.width != w || main.height != h) {
                if (chain != null) chain.close();
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN);
                chain.resize(main.width, main.height);
                w = main.width;
                h = main.height;
            }
            List<PostPass> passes = ObfuscationReflectionHelper.getPrivateValue(PostChain.class, chain, "f_110009_");
            if (passes == null || passes.isEmpty()) return;
            var fx = passes.get(0).getEffect();
            fx.safeGetUniform("InvProjMat").set(new Matrix4f(RenderSystem.getProjectionMatrix()).invert());
            fx.safeGetUniform("InvViewMat").set(new Matrix4f().set(RenderSystem.getInverseViewRotationMatrix()));
            fx.safeGetUniform("LightPos").set(pos);
            fx.safeGetUniform("LightDir").set(dir);
            fx.safeGetUniform("LightCol").set(col);
            fx.safeGetUniform("LightExt").set(ext);
            fx.safeGetUniform("LightCount").set((float) n);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(e.getPartialTick());
            main.bindWrite(false);
            RenderSystem.enableDepthTest();
        } catch (Exception ex) {
            failed = true;
            RpPortals.LOGGER.error("RP Portals: проход света порталов не загрузился", ex);
        }
    }
}
