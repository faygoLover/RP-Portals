package faygolover.rpportals.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import faygolover.rpportals.PortalBlockEntity;
import faygolover.rpportals.PortalFrame;
import faygolover.rpportals.RpPortals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Та сторона портала. Порталы обычно стоят в коридорах, поэтому весь мир второй раз не рисуется: блоки «коробки»
 * перед порталом-целью (ширина портала + 1,5 по бокам, высота + 1, глубина {@code viewDepth}) один раз собираются в
 * модель, как движущиеся конструкции Create, и пересобираются, только когда в коробке что-то поменялось.
 * <ul>
 *   <li>Живой вид: каждый кадр модель и сущности коробки рисуются в отдельный кадр с переносом «цель → источник»;
 *   поверхность портала берёт этот кадр по экранным координатам.</li>
 *   <li>Снимок: то же самое один раз с неподвижной камеры за выходом портала, вместе с глубиной каждой точки — портал
 *   с видом «снимок» показывает его с настоящим объёмом.</li>
 * </ul>
 */
public final class PortalView {
    private static final RenderType[] LAYERS = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
    private static final String[] LAYER_NAMES = {"solid", "cutout_mipped", "cutout", "translucent"};
    /** Коробки по плоскости цели (у одной цели может быть несколько входов). */
    private static final Map<PortalFrame, Box> BOXES = new HashMap<>();
    private static RenderTarget target;
    /** Замеры и кадры в rpportals_debug — только с -Drpportals.debug=true. */
    static final boolean DEBUG = Boolean.getBoolean("rpportals.debug");
    /** {@code /rpportals debug}: замеры и кадры один раз, без флага запуска. */
    static volatile boolean debugOnce;
    static volatile boolean debugPhoto;
    private static boolean dumped;

    private PortalView() {}

    static void clearAll() {
        BOXES.values().forEach(Box::close);
        BOXES.clear();
    }

    /** Есть ли что показать: цель в этом измерении, чанки у клиента. */
    static boolean available(PortalBlockEntity be, ClientLevel level) {
        if (!be.linked() || !be.linkDim.equals(level.dimension())) return false;
        return loaded(level, be.linkFrame, be.viewDepth);
    }

    static boolean loaded(ClientLevel level, PortalFrame t, double depth) {
        return level.hasChunkAt(BlockPos.containing(t.origin()))
                && level.hasChunkAt(BlockPos.containing(t.origin().add(t.normal().scale(depth))));
    }

    static String whyUnavailable(PortalBlockEntity be, ClientLevel level) {
        if (!be.linked()) return "портал не связан (нет цели по имени)";
        if (!be.linkDim.equals(level.dimension())) return "цель в другом измерении";
        return "чанки цели не загружены у клиента (далеко)";
    }

    private static Box box(ClientLevel level, PortalFrame t, float depth) {
        Box b = BOXES.computeIfAbsent(t, k -> new Box());
        b.update(level, t, depth);
        return b;
    }

    /** Подготовить общий кадр нужного размера, очистить в цвет {@code rgb} и сделать текущим. */
    private static RenderTarget begin(int w, int h, int rgb) {
        if (target == null) target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        else if (target.width != w || target.height != h) target.resize(w, h, Minecraft.ON_OSX);
        // glClear глубины работает, только когда запись глубины включена, — на этом этапе мира её могли выключить.
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        target.setClearColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, 1f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.enableDepthTest();
        return target;
    }

    // ---------------------------------------------------------------- живой вид
    /** Нарисовать вид той стороны в общий кадр; вернуть id текстуры кадра. */
    static int render(PortalBlockEntity be, ClientLevel level, PoseStack view, Matrix4f proj, Vec3 cam, float partial) {
        PortalFrame src = be.frame(), dst = be.linkFrame;
        Box b = box(level, dst, be.viewDepth);
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int rgb = be.rgb();
        begin(main.width, main.height, rgb);

        Matrix4f toSrc = PortalFrame.matrix(dst, src);
        Matrix4f world = new Matrix4f(view.last().pose()).translate((float) -cam.x, (float) -cam.y, (float) -cam.z).mul(toSrc);
        double toPlane = cam.distanceTo(src.origin().add(0, src.height() / 2, 0));
        float fogStart = (float) toPlane + be.viewDepth * (1f - be.viewFog) * 0.9f, fogEnd = (float) toPlane + be.viewDepth;
        int drawn = b.draw(world, proj, rgb, fogStart, fogEnd);
        b.drawEntities(level, view, cam, dst, src, partial);
        if ((DEBUG && !dumped) || debugOnce) {
            dumped = true;
            debugOnce = false;
            dump(target, "live");
            // Куда на экране попадают середина проёма и середина той стороны (после переноса) — должны быть рядом.
            Matrix4f vp = new Matrix4f(proj).mul(new Matrix4f(view.last().pose()).translate((float) -cam.x, (float) -cam.y, (float) -cam.z));
            Vec3 portalMid = src.origin().add(0, src.height() / 2, 0);
            Vec3 boxMid = dst.origin().add(0, dst.height() / 2, 0).add(dst.normal().scale(be.viewDepth / 2));   // world уже переносит
            RpPortals.LOGGER.info("RP Portals: живой вид — слоёв нарисовано {}, экран: проём {}, та сторона {}, камера {}",
                    drawn, ndc(vp, portalMid), ndc(new Matrix4f(proj).mul(world), boxMid), cam);
        }
        main.bindWrite(true);
        return target.getColorTextureId();
    }

    // ---------------------------------------------------------------- снимок с глубиной
    /** Наибольший размер снимка (по длинной стороне), пикселей. */
    static final int PHOTO_MAX = 384;
    /** Камера снимка стоит за плоскостью выхода на таком расстоянии (блоков) и смотрит наружу. */
    static final float PHOTO_DIST = 2.0f;
    /** Запас поля зрения снимка вокруг проёма. */
    static final float PHOTO_MARGIN = 1.25f;
    /** Глубина в альфе снимка: 0…255 → 0…16 блоков за плоскостью (те же числа — в шейдере портала). */
    static final float PHOTO_RANGE = 16f;

    /**
     * Снимок вида из портала {@code exit} (что видно, выходя из него): RGB — цвет, альфа — глубина за плоскостью
     * (0 — у плоскости, 255 — {@link #PHOTO_RANGE} блоков и дальше).
     */
    static NativeImage photo(ClientLevel level, PortalFrame exit, float depth, int fogRgb) {
        Box b = box(level, exit, depth);
        double W = exit.width() * PHOTO_MARGIN, H = exit.height() * PHOTO_MARGIN;
        int w, h;
        if (W >= H) {
            w = PHOTO_MAX;
            h = Math.max(16, (int) Math.round(PHOTO_MAX * H / W));
        } else {
            h = PHOTO_MAX;
            w = Math.max(16, (int) Math.round(PHOTO_MAX * W / H));
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        begin(w, h, fogRgb);
        float near = 0.05f, far = PHOTO_DIST + Math.max(depth, PHOTO_RANGE) + 4f;
        Matrix4f proj = new Matrix4f().perspective((float) (2 * Math.atan(H / 2 / PHOTO_DIST)), (float) (W / H), near, far);
        Vec3 cam = exit.origin().add(0, exit.height() / 2, 0).subtract(exit.normal().scale(PHOTO_DIST));
        PoseStack view = new PoseStack();
        view.mulPose(Axis.YP.rotationDegrees(exit.yaw() + 180f));
        Matrix4f world = new Matrix4f(view.last().pose()).translate((float) -cam.x, (float) -cam.y, (float) -cam.z);
        Matrix4f projSaved = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(proj, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        // Без сущностей: снимок неподвижный, люди и мобы на нём только мешали бы.
        int drawn = b.draw(world, proj, fogRgb, PHOTO_DIST + depth * 0.6f, PHOTO_DIST + depth);
        Vec3 ahead = exit.origin().add(0, exit.height() / 2, 0).add(exit.normal().scale(depth / 2));

        NativeImage img = Screenshot.takeScreenshot(target);
        FloatBuffer zbuf = BufferUtils.createFloatBuffer(w * h);
        target.bindWrite(false);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, zbuf);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = zbuf.get((h - 1 - y) * w + x);
                double ndc = d * 2 - 1;
                double viewZ = 2.0 * near * far / (far + near - ndc * (far - near));
                double behind = Math.max(0, Math.min(PHOTO_RANGE, viewZ - PHOTO_DIST));
                int a = d >= 0.99999f ? 255 : (int) Math.round(behind / PHOTO_RANGE * 255);
                int abgr = img.getPixelRGBA(x, y);
                img.setPixelRGBA(x, y, (abgr & 0x00FFFFFF) | a << 24);
            }
        }
        if (DEBUG || debugPhoto) {
            debugPhoto = false;
            float minD = 1f;
            for (int i = 0; i < w * h; i++) minD = Math.min(minD, zbuf.get(i));
            RpPortals.LOGGER.info("RP Portals: снимок — слоёв нарисовано {}, ближайшая глубина {}, центр той стороны на снимке {}",
                    drawn, minD, ndc(new Matrix4f(proj).mul(world), ahead));
            dump(target, "photo");
        }
        RenderSystem.setProjectionMatrix(projSaved, sorting);
        main.bindWrite(true);
        return img;
    }

    private static String ndc(Matrix4f m, Vec3 p) {
        org.joml.Vector4f v = m.transform(new org.joml.Vector4f((float) p.x, (float) p.y, (float) p.z, 1f));
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f, w=%.2f)", v.x / v.w, v.y / v.w, v.w);
    }

    /** Отладка: сохранить кадр той стороны в {@code rpportals_debug/<что>.png}. */
    static void dump(RenderTarget t, String what) {
        try (NativeImage img = Screenshot.takeScreenshot(t)) {
            java.nio.file.Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("rpportals_debug");
            java.nio.file.Files.createDirectories(dir);
            img.writeToFile(dir.resolve(what + ".png"));
            RpPortals.LOGGER.info("RP Portals: кадр той стороны сохранён в {}", dir.resolve(what + ".png"));
        } catch (Exception ex) {
            RpPortals.LOGGER.warn("RP Portals: кадр той стороны не сохранён", ex);
        }
    }

    // ---------------------------------------------------------------- коробка
    private static final class Box {
        private final Map<RenderType, VertexBuffer> buffers = new IdentityHashMap<>();
        private BlockPos origin = BlockPos.ZERO;
        private AABB region = new AABB(0, 0, 0, 0, 0, 0);
        private long hash = Long.MIN_VALUE;
        private float builtDepth = -1;
        private int nextCheck;
        private boolean logged;

        void update(ClientLevel level, PortalFrame t, float depth) {
            boolean fresh = builtDepth != depth;
            if (!fresh && --nextCheck > 0) return;
            nextCheck = 20;
            List<BlockPos> cells = cells(t, depth);
            long h = 1469598103934665603L;
            for (BlockPos p : cells) h = h * 1099511628211L ^ (net.minecraft.world.level.block.Block.getId(level.getBlockState(p)) * 31L + p.asLong());
            if (!fresh && h == hash) return;
            hash = h;
            builtDepth = depth;
            rebuild(level, cells);
        }

        /** Клетки перед целью (в её системе: |x| ≤ w/2+1.5, −1 ≤ y ≤ h+1, −0.5 ≤ z ≤ depth). */
        private List<BlockPos> cells(PortalFrame t, float depth) {
            double hx = t.width() / 2 + 1.5, y0 = -1, y1 = t.height() + 1, z0 = -0.5, z1 = depth;
            AABB box = null;
            for (double x : new double[]{-hx, hx}) for (double y : new double[]{y0, y1}) for (double z : new double[]{z0, z1}) {
                Vec3 w = t.fromLocal(new Vec3(x, y, z));
                AABB a = new AABB(w, w);
                box = box == null ? a : box.minmax(a);
            }
            region = box.inflate(0.5);
            origin = BlockPos.containing(box.minX, box.minY, box.minZ);
            List<BlockPos> out = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(origin, BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                Vec3 l = t.toLocal(p.getCenter());
                if (Math.abs(l.x) <= hx + 0.87 && l.y >= y0 - 0.87 && l.y <= y1 + 0.87 && l.z >= z0 - 0.87 && l.z <= z1 + 0.87) out.add(p.immutable());
            }
            return out;
        }

        private void rebuild(ClientLevel level, List<BlockPos> cells) {
            close();
            BlockRenderDispatcher brd = Minecraft.getInstance().getBlockRenderer();
            Map<RenderType, BufferBuilder> builders = new IdentityHashMap<>();
            for (RenderType rt : LAYERS) {
                BufferBuilder bb = new BufferBuilder(rt.bufferSize());
                bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                builders.put(rt, bb);
            }
            RandomSource rand = RandomSource.create();
            PoseStack pose = new PoseStack();
            for (BlockPos p : cells) {
                BlockState st = level.getBlockState(p);
                if (st.isAir()) continue;
                if (st.getRenderShape() == RenderShape.MODEL) {
                    BakedModel model = brd.getBlockModel(st);
                    ModelData base = level.getModelDataManager().getAt(p);
                    // null сюда нельзя: модели модов (Athena и др.) вызывают у него derive() и падают.
                    ModelData md = model.getModelData(level, p, st, base == null ? ModelData.EMPTY : base);
                    if (md == null) md = ModelData.EMPTY;
                    long seed = st.getSeed(p);
                    rand.setSeed(seed);
                    for (RenderType rt : model.getRenderTypes(st, rand, md)) {
                        BufferBuilder bb = builders.get(rt);
                        if (bb == null) bb = builders.get(RenderType.cutout());
                        pose.pushPose();
                        pose.translate(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ());
                        rand.setSeed(seed);
                        brd.renderBatched(st, p, level, pose, bb, true, rand, md, rt);
                        pose.popPose();
                    }
                }
                FluidState fs = st.getFluidState();
                if (!fs.isEmpty()) {
                    BufferBuilder bb = builders.get(ItemBlockRenderTypes.getRenderLayer(fs));
                    if (bb == null) bb = builders.get(RenderType.translucent());
                    // Жидкость пишет координаты внутри своей секции 16³ — сдвинуть к началу коробки.
                    double ox = (p.getX() & ~15) - origin.getX(), oy = (p.getY() & ~15) - origin.getY(), oz = (p.getZ() & ~15) - origin.getZ();
                    brd.renderLiquid(p, level, new Offset(bb, ox, oy, oz), st, fs);
                }
            }
            StringBuilder diag = new StringBuilder();
            for (int i = 0; i < LAYERS.length; i++) {
                BufferBuilder.RenderedBuffer rb = builders.get(LAYERS[i]).endOrDiscardIfEmpty();
                if (rb == null) continue;
                diag.append(' ').append(LAYER_NAMES[i]).append('=').append(rb.drawState().vertexCount());
                VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vb.bind();
                vb.upload(rb);
                VertexBuffer.unbind();
                buffers.put(LAYERS[i], vb);
            }
            if ((DEBUG || debugOnce) && !logged) {
                logged = true;
                RpPortals.LOGGER.info("RP Portals: та сторона — клеток {}, начало {}, вершин:{}", cells.size(), origin.toShortString(),
                        diag.length() == 0 ? " нет" : diag.toString());
            }
        }

        /** Модель коробки; {@code world} — мир → вид (с камерой), туман цвета {@code rgb}. Возвращает, сколько слоёв нарисовано. */
        int draw(Matrix4f world, Matrix4f proj, int rgb, float fogStart, float fogEnd) {
            int drawn = 0;
            float fs0 = RenderSystem.getShaderFogStart(), fe0 = RenderSystem.getShaderFogEnd();
            float[] fc0 = RenderSystem.getShaderFogColor().clone();
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, 1f);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            Matrix4f mv = new Matrix4f(world).translate(origin.getX(), origin.getY(), origin.getZ());
            for (RenderType layer : LAYERS) {
                VertexBuffer vb = buffers.get(layer);
                if (vb == null) continue;
                layer.setupRenderState();
                ShaderInstance sh = RenderSystem.getShader();
                if (sh != null) {
                    // Шейдер блоков прибавляет сдвиг секции чанка — обнулить.
                    if (sh.CHUNK_OFFSET != null) sh.CHUNK_OFFSET.set(0f, 0f, 0f);
                    vb.bind();
                    vb.drawWithShader(mv, proj, sh);
                    VertexBuffer.unbind();
                    drawn++;
                }
                layer.clearRenderState();
            }
            RenderSystem.setShaderFogStart(fs0);
            RenderSystem.setShaderFogEnd(fe0);
            RenderSystem.setShaderFogColor(fc0[0], fc0[1], fc0[2], fc0[3]);
            return drawn;
        }

        /** Сущности в коробке; {@code src} — куда переносить (живой вид), null — как есть (снимок). */
        void drawEntities(ClientLevel level, PoseStack view, Vec3 cam, PortalFrame dst, PortalFrame src, float partial) {
            Minecraft mc = Minecraft.getInstance();
            EntityRenderDispatcher erd = mc.getEntityRenderDispatcher();
            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            float dy = src == null ? 0 : PortalFrame.yawDelta(dst, src);
            boolean any = false;
            for (Entity e : level.getEntities((Entity) null, region, e -> true)) {
                if (e == mc.getCameraEntity() && !mc.gameRenderer.getMainCamera().isDetached()) continue;
                Vec3 ep = e.getPosition(partial);
                if (dst.toLocal(ep).z < -0.3) continue;
                Vec3 mp = src == null ? ep : PortalFrame.mapPoint(dst, src, ep);
                PoseStack ps = new PoseStack();
                ps.last().pose().set(view.last().pose());
                ps.last().normal().set(view.last().normal());
                ps.translate(mp.x - cam.x, mp.y - cam.y, mp.z - cam.z);
                ps.mulPose(Axis.YP.rotationDegrees(-dy));
                erd.render(e, 0, 0, 0, e.getYRot(), partial, ps, buf, erd.getPackedLightCoords(e, partial));
                any = true;
            }
            if (any) buf.endBatch();
        }

        void close() {
            buffers.values().forEach(VertexBuffer::close);
            buffers.clear();
        }
    }

    /** Сдвиг координат вершин (жидкости). */
    private record Offset(VertexConsumer to, double ox, double oy, double oz) implements VertexConsumer {
        @Override public VertexConsumer vertex(double x, double y, double z) { to.vertex(x + ox, y + oy, z + oz); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { to.color(r, g, b, a); return this; }
        @Override public VertexConsumer uv(float u, float v) { to.uv(u, v); return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { to.overlayCoords(u, v); return this; }
        @Override public VertexConsumer uv2(int u, int v) { to.uv2(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { to.normal(x, y, z); return this; }
        @Override public void endVertex() { to.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) { to.defaultColor(r, g, b, a); }
        @Override public void unsetDefaultColor() { to.unsetDefaultColor(); }
    }
}
