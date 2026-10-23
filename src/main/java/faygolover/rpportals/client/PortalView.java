package faygolover.rpportals.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Живой вид той стороны портала. Порталы обычно стоят в коридорах, поэтому весь мир второй раз не рисуется: блоки
 * «коробки» перед порталом-целью (ширина портала + 1,5 по бокам, высота + 1, глубина {@code viewDepth}) один раз
 * собираются в модель, как движущиеся конструкции Create, и пересобираются, только когда в коробке что-то поменялось.
 * Каждый кадр модель и сущности внутри коробки рисуются в отдельный кадр с переносом «цель → источник» (за плоскость
 * источника), с туманом цвета портала; поверхность портала берёт этот кадр по экранным координатам.
 * Работает, когда цель в том же измерении и её чанки есть у клиента (в пределах прорисовки).
 */
public final class PortalView {
    private static final RenderType[] LAYERS = {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
    private static final Map<PortalBlockEntity, PortalView> VIEWS = new WeakHashMap<>();
    private static RenderTarget target;

    private final Map<RenderType, VertexBuffer> buffers = new IdentityHashMap<>();
    private BlockPos origin = BlockPos.ZERO;
    private AABB region = new AABB(0, 0, 0, 0, 0, 0);
    private long hash = Long.MIN_VALUE;
    private PortalFrame builtFor;
    private int nextCheck;

    private PortalView() {}

    static void clearAll() {
        VIEWS.values().forEach(PortalView::close);
        VIEWS.clear();
    }

    /** Есть ли что показать: цель в этом измерении, чанки у клиента. */
    static boolean available(PortalBlockEntity be, ClientLevel level) {
        if (!be.linked() || !be.linkDim.equals(level.dimension())) return false;
        PortalFrame t = be.linkFrame;
        BlockPos o = BlockPos.containing(t.origin());
        Vec3 far = t.origin().add(t.normal().scale(be.viewDepth));
        return level.hasChunkAt(o) && level.hasChunkAt(BlockPos.containing(far));
    }

    /** Нарисовать вид той стороны в общий кадр; вернуть id текстуры кадра. */
    static int render(PortalBlockEntity be, ClientLevel level, PoseStack eventPose, Matrix4f proj, Vec3 cam, float partial) {
        PortalView v = VIEWS.computeIfAbsent(be, k -> new PortalView());
        v.update(be, level);
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) target = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        else if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height, Minecraft.ON_OSX);

        int rgb = be.rgb();
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        target.setClearColor(r, g, b, 1f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        PortalFrame src = be.frame(), dst = be.linkFrame;
        Matrix4f toSrc = PortalFrame.matrix(dst, src);
        Matrix4f mv = new Matrix4f(eventPose.last().pose())
                .translate((float) -cam.x, (float) -cam.y, (float) -cam.z)
                .mul(toSrc)
                .translate(v.origin.getX(), v.origin.getY(), v.origin.getZ());

        // Туман цвета портала: от камеры до плоскости — прозрачно, дальше за viewDepth уходит в цвет.
        float fogStart0 = RenderSystem.getShaderFogStart(), fogEnd0 = RenderSystem.getShaderFogEnd();
        float[] fogColor0 = RenderSystem.getShaderFogColor().clone();
        double toPlane = cam.distanceTo(src.origin().add(0, src.height() / 2, 0));
        float depth = be.viewDepth;
        RenderSystem.setShaderFogStart((float) toPlane + depth * (1f - be.viewFog) * 0.9f);
        RenderSystem.setShaderFogEnd((float) toPlane + depth);
        RenderSystem.setShaderFogColor(r, g, b, 1f);

        for (RenderType layer : LAYERS) {
            VertexBuffer vb = v.buffers.get(layer);
            if (vb == null) continue;
            layer.setupRenderState();
            vb.bind();
            vb.drawWithShader(mv, proj, RenderSystem.getShader());
            VertexBuffer.unbind();
            layer.clearRenderState();
        }
        v.renderEntities(level, eventPose, cam, dst, src, partial);

        RenderSystem.setShaderFogStart(fogStart0);
        RenderSystem.setShaderFogEnd(fogEnd0);
        RenderSystem.setShaderFogColor(fogColor0[0], fogColor0[1], fogColor0[2], fogColor0[3]);
        main.bindWrite(true);
        return target.getColorTextureId();
    }

    private void renderEntities(ClientLevel level, PoseStack eventPose, Vec3 cam, PortalFrame dst, PortalFrame src, float partial) {
        Minecraft mc = Minecraft.getInstance();
        EntityRenderDispatcher erd = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        float dy = PortalFrame.yawDelta(dst, src);
        boolean any = false;
        for (Entity e : level.getEntities((Entity) null, region, e -> true)) {
            if (e == mc.getCameraEntity() && !mc.gameRenderer.getMainCamera().isDetached()) continue;
            Vec3 ep = e.getPosition(partial);
            if (dst.toLocal(ep).z < -0.3) continue;
            Vec3 mp = PortalFrame.mapPoint(dst, src, ep);
            PoseStack ps = new PoseStack();
            ps.last().pose().set(eventPose.last().pose());
            ps.last().normal().set(eventPose.last().normal());
            ps.translate(mp.x - cam.x, mp.y - cam.y, mp.z - cam.z);
            ps.mulPose(Axis.YP.rotationDegrees(-dy));
            erd.render(e, 0, 0, 0, e.getYRot(), partial, ps, buf, erd.getPackedLightCoords(e, partial));
            any = true;
        }
        if (any) buf.endBatch();
    }

    // ---------------------------------------------------------------- модель коробки
    private void update(PortalBlockEntity be, ClientLevel level) {
        PortalFrame t = be.linkFrame;
        boolean moved = !t.equals(builtFor) || region.getSize() == 0;
        if (!moved && --nextCheck > 0) return;
        nextCheck = 20;
        List<BlockPos> cells = cells(be, t);
        long h = 1469598103934665603L;
        for (BlockPos p : cells) h = h * 1099511628211L ^ (blockId(level.getBlockState(p)) * 31L + p.asLong());
        if (!moved && h == hash) return;
        hash = h;
        builtFor = t;
        rebuild(level, cells);
    }

    private static long blockId(BlockState s) {
        return net.minecraft.world.level.block.Block.getId(s);
    }

    /** Клетки коробки перед целью (в её системе: |x| ≤ w/2+1.5, −1 ≤ y ≤ h+1, −0.5 ≤ z ≤ viewDepth). */
    private List<BlockPos> cells(PortalBlockEntity be, PortalFrame t) {
        double hx = t.width() / 2 + 1.5, y0 = -1, y1 = t.height() + 1, z0 = -0.5, z1 = be.viewDepth;
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
                ModelData md = model.getModelData(level, p, st, level.getModelDataManager().getAt(p));
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
        for (RenderType rt : LAYERS) {
            BufferBuilder.RenderedBuffer rb = builders.get(rt).endOrDiscardIfEmpty();
            if (rb == null) continue;
            VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vb.bind();
            vb.upload(rb);
            VertexBuffer.unbind();
            buffers.put(rt, vb);
        }
    }

    private void close() {
        buffers.values().forEach(VertexBuffer::close);
        buffers.clear();
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
