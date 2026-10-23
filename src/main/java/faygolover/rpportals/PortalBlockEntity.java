package faygolover.rpportals;

import faygolover.rpcore.gm.GmConfigurable;
import faygolover.rpcore.gm.GmMarkerSet;
import faygolover.rpcore.gm.GmTeleport;
import faygolover.rpcore.gm.GmTools;
import faygolover.rpcore.gm.RpAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Портал: плоскость любого размера и формы над блоком-якорем, вид (цвет, туман, рябь, живой вид той стороны,
 * невидимый, снимок той стороны), связь с другим порталом по имени (по умолчанию двусторонняя).
 * Игроков переносит их клиент сразу при пересечении (бесшовно, сервер подтверждает), остальных сущностей — сервер.
 */
public class PortalBlockEntity extends BlockEntity implements GmConfigurable {
    private static final TicketType<ChunkPos> PRELOAD = TicketType.create("rpportals_preload", Comparator.comparingLong(ChunkPos::toLong), 300);

    // ---------------------------------------------------------------- настройки (видны всем клиентам)
    public String name = "";
    public String link = "";
    public float yaw;
    public float width = 1.0f, height = 2.0f;
    public float offX, offY, offZ;
    /** Где в блоке плоскость: front — передний край (со стороны лица), center, back — задний край. */
    public String place = "center";
    /** Форма: rect, rounded, oval. */
    public String shape = "rect";
    public float corner = 0.25f;
    /** Основа: solid — цвет, live — та сторона вживую, photo — снимок той стороны, invisible — ничего. */
    public String look = "solid";
    public String color = "000000";
    public float alpha = 1.0f;
    public boolean glow = true;
    /** Свет вокруг (если светится): радиус, блоков, и сила. */
    public float lightRange = 6f;
    public float lightPower = 1f;
    /** Туман поверх (как в Dark Souls), 0–1. */
    public float fog;
    /** Рябь, 0–1. */
    public float ripple;
    /** Обод: none, thin, thick. */
    public String rim = "none";
    public String rimColor = "FFFFFF";
    /** Живой вид: сколько блоков той стороны видно и как быстро она уходит в туман цвета портала. */
    public float viewDepth = 12f;
    public float viewFog = 0.6f;
    /** Снимок: на сколько блоков «за» плоскостью он висит (объём при взгляде сбоку). */
    public float photoDepth = 4f;
    /** Проход: front — только с лица, both — с обеих сторон. */
    public String sides = "front";
    /** Кого переносит: players, all. */
    public String who = "players";
    public int cooldown = 10;

    // ---------------------------------------------------------------- цель (сервер ищет по имени и рассылает)
    @Nullable public ResourceKey<Level> linkDim;
    @Nullable public PortalFrame linkFrame;

    // ---------------------------------------------------------------- сервер
    private final Map<UUID, Vec3> lastPos = new HashMap<>();
    private boolean unloading;
    private int ticks;

    public PortalBlockEntity(BlockPos pos, BlockState state) {
        super(PortalRegistry.PORTAL_BE.get(), pos, state);
    }

    public PortalFrame frame() {
        PortalFrame base = new PortalFrame(Vec3.ZERO, yaw, width, height);
        float edge = "front".equals(place) ? 0.5f : "back".equals(place) ? -0.5f : 0f;
        Vec3 o = new Vec3(worldPosition.getX() + 0.5, worldPosition.getY(), worldPosition.getZ() + 0.5)
                .add(base.right().scale(offX)).add(0, offY, 0).add(base.normal().scale(offZ + edge));
        return new PortalFrame(o, yaw, width, height);
    }

    public boolean linked() {
        return linkFrame != null && linkDim != null;
    }

    public int rgb() {
        return parseColor(color, 0x000000);
    }

    public int rimRgb() {
        return parseColor(rimColor, 0xFFFFFF);
    }

    static int parseColor(String s, int def) {
        try {
            return Integer.parseInt(s.replace("#", "").strip(), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------------------------------------------------------------- тик сервера
    static void serverTick(Level level, BlockPos pos, BlockState state, PortalBlockEntity be) {
        be.tick((ServerLevel) level);
    }

    private void tick(ServerLevel level) {
        ticks++;
        if (ticks % 40 == 0) {
            resolveLink(level);
            preload(level);
        }
        // Снимок вида с выхода (для порталов, которые ведут сюда с видом «снимок»): нет или просили обновить — снять.
        if (ticks % 100 == 7 && !name.isEmpty() && PortalPhotoServer.needs(level.getServer(), name)) {
            PortalPhotoServer.maybeAsk(level, name, worldPosition, frame().origin());
        }
        if ("all".equals(who) && linked()) moveEntities(level);
    }

    /** Сущности (не игроки) пересекли плоскость — перенести. */
    private void moveEntities(ServerLevel level) {
        PortalFrame f = frame();
        AABB box = planeBox(f).inflate(1.5);
        Map<UUID, Vec3> seen = new HashMap<>();
        for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player) && !e.isPassenger() && !e.isVehicle())) {
            Vec3 now = e.position().add(0, Math.min(e.getBbHeight() / 2, 0.5), 0);
            Vec3 before = lastPos.get(e.getUUID());
            seen.put(e.getUUID(), now);
            if (before == null || GmTeleport.onCooldown(e)) continue;
            if (crossed(f, before, now)) {
                ServerLevel to = level.getServer().getLevel(linkDim);
                if (to != level) continue;   // в другое измерение — только игроки
                Vec3 dest = PortalFrame.mapPoint(f, linkFrame, e.position());
                Vec3 vel = PortalFrame.mapDir(f, linkFrame, e.getDeltaMovement());
                e.teleportTo(dest.x, dest.y, dest.z);
                e.setYRot(e.getYRot() + PortalFrame.yawDelta(f, linkFrame));
                e.setDeltaMovement(vel);
                GmTeleport.setCooldown(e, cooldown);
                seen.remove(e.getUUID());
            }
        }
        lastPos.clear();
        lastPos.putAll(seen);
    }

    /** Отрезок {@code before → now} пересёк плоскость в нужную сторону внутри прямоугольника. */
    public boolean crossed(PortalFrame f, Vec3 before, Vec3 now) {
        Vec3 a = f.toLocal(before), b = f.toLocal(now);
        boolean front = a.z > 0 && b.z <= 0;
        boolean back = "both".equals(sides) && a.z < 0 && b.z >= 0;
        if (!front && !back) return false;
        double t = a.z / (a.z - b.z);
        Vec3 hit = a.add(b.subtract(a).scale(t));
        return f.inside(hit, 0.05);
    }

    public static AABB planeBox(PortalFrame f) {
        Vec3 r = f.right().scale(f.width() / 2);
        Vec3 a = f.origin().add(r), b = f.origin().subtract(r).add(0, f.height(), 0);
        return new AABB(a, b).inflate(0.05);
    }

    /** К цели подходят игроки — держать чанки цели загруженными (переход без ожидания генерации/загрузки). */
    private void preload(ServerLevel level) {
        if (!linked()) return;
        Vec3 c = frame().origin();
        boolean near = false;
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(c) < 32 * 32) { near = true; break; }
        if (!near) return;
        ServerLevel to = level.getServer().getLevel(linkDim);
        if (to == null) return;
        ChunkPos cp = new ChunkPos(BlockPos.containing(linkFrame.origin()));
        to.getChunkSource().addRegionTicket(PRELOAD, cp, 2, cp);
    }

    // ---------------------------------------------------------------- связь и регистрация
    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null) return;
        if (level.isClientSide) {
            GmMarkerSet.add(this);
            PortalClientSet.add(this);
        } else if (level instanceof ServerLevel sl) {
            PortalLinks.Entry e = PortalLinks.get(sl.getServer()).find(name);
            if (e != null && e.pos().equals(worldPosition) && link.isEmpty() && !e.link().isEmpty()) link = e.link();
            register(sl);
            resolveLink(sl);
        }
    }

    @Override
    public void onChunkUnloaded() {
        unloading = true;
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level == null) return;
        if (level.isClientSide) {
            GmMarkerSet.remove(this);
            PortalClientSet.remove(this);
        } else if (!unloading && level instanceof ServerLevel sl && !name.isEmpty()) {
            PortalLinks.get(sl.getServer()).remove(name, level.dimension(), worldPosition);
            RpAnchors.get(sl.getServer()).remove(name, anchorOwner());
        }
    }

    private String anchorOwner() {
        return "portal@" + level.dimension().location() + ":" + worldPosition.toShortString();
    }

    private void register(ServerLevel sl) {
        if (name.isEmpty()) return;
        PortalFrame f = frame();
        PortalLinks.get(sl.getServer()).put(new PortalLinks.Entry(name, level.dimension(), worldPosition, f, link));
        // Точка прибытия для пластин RP Core: чуть перед лицом, взгляд от портала.
        Vec3 at = f.origin().add(f.normal().scale(0.8));
        RpAnchors.get(sl.getServer()).put(new RpAnchors.Anchor(name, level.dimension(), at, f.yaw(), 0f, anchorOwner()));
    }

    private void resolveLink(ServerLevel sl) {
        PortalLinks.Entry e = PortalLinks.get(sl.getServer()).find(link);
        ResourceKey<Level> dim = e == null ? null : e.dim();
        PortalFrame f = e == null ? null : e.frame();
        if (!java.util.Objects.equals(dim, linkDim) || !java.util.Objects.equals(f, linkFrame)) {
            linkDim = dim;
            linkFrame = f;
            sync();
        }
    }

    private void sync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ---------------------------------------------------------------- настройки
    @Override
    public CompoundTag writeSettings() {
        CompoundTag t = new CompoundTag();
        t.putString("name", name);
        t.putString("link", link);
        t.putFloat("yaw", yaw);
        t.putFloat("width", width);
        t.putFloat("height", height);
        t.putFloat("offX", offX);
        t.putFloat("offY", offY);
        t.putFloat("offZ", offZ);
        t.putString("place", place);
        t.putString("shape", shape);
        t.putFloat("corner", corner);
        t.putString("look", look);
        t.putString("color", color);
        t.putFloat("alpha", alpha);
        t.putBoolean("glow", glow);
        t.putFloat("lightRange", lightRange);
        t.putFloat("lightPower", lightPower);
        t.putFloat("fog", fog);
        t.putFloat("ripple", ripple);
        t.putString("rim", rim);
        t.putString("rimColor", rimColor);
        t.putFloat("viewDepth", viewDepth);
        t.putFloat("viewFog", viewFog);
        t.putFloat("photoDepth", photoDepth);
        t.putString("sides", sides);
        t.putString("who", who);
        t.putInt("cooldown", cooldown);
        return t;
    }

    @Override
    public void readSettings(CompoundTag t) {
        name = GmTools.clean(t.getString("name"), 32);
        link = GmTools.clean(t.getString("link"), 32);
        yaw = Mth.wrapDegrees(t.getFloat("yaw"));
        width = Mth.clamp(t.contains("width") ? t.getFloat("width") : 1f, 0.25f, 32f);
        height = Mth.clamp(t.contains("height") ? t.getFloat("height") : 2f, 0.25f, 32f);
        offX = Mth.clamp(t.getFloat("offX"), -8f, 8f);
        offY = Mth.clamp(t.getFloat("offY"), -8f, 8f);
        offZ = Mth.clamp(t.getFloat("offZ"), -8f, 8f);
        place = oneOf(t.getString("place"), "center", "front", "back");
        shape = oneOf(t.getString("shape"), "rect", "rounded", "oval");
        corner = Mth.clamp(t.contains("corner") ? t.getFloat("corner") : 0.25f, 0f, 0.5f);
        look = oneOf(t.getString("look"), "solid", "live", "photo", "invisible");
        photoDepth = Mth.clamp(t.contains("photoDepth") ? t.getFloat("photoDepth") : 4f, 0f, 16f);
        color = hex(t.getString("color"), "000000");
        alpha = Mth.clamp(t.contains("alpha") ? t.getFloat("alpha") : 1f, 0f, 1f);
        glow = !t.contains("glow") || t.getBoolean("glow");
        lightRange = Mth.clamp(t.contains("lightRange") ? t.getFloat("lightRange") : 6f, 0f, 24f);
        lightPower = Mth.clamp(t.contains("lightPower") ? t.getFloat("lightPower") : 1f, 0f, 3f);
        fog = Mth.clamp(t.getFloat("fog"), 0f, 1f);
        ripple = Mth.clamp(t.getFloat("ripple"), 0f, 1f);
        rim = oneOf(t.getString("rim"), "none", "thin", "thick");
        rimColor = hex(t.getString("rimColor"), "FFFFFF");
        viewDepth = Mth.clamp(t.contains("viewDepth") ? t.getFloat("viewDepth") : 12f, 2f, 48f);
        viewFog = Mth.clamp(t.contains("viewFog") ? t.getFloat("viewFog") : 0.6f, 0f, 1f);
        sides = oneOf(t.getString("sides"), "front", "both");
        who = oneOf(t.getString("who"), "players", "all");
        cooldown = Mth.clamp(t.contains("cooldown") ? t.getInt("cooldown") : 10, 0, 1200);
    }

    @Override
    public void applyFrom(ServerPlayer gm, CompoundTag t) {
        if (!(level instanceof ServerLevel sl)) return;
        String oldName = name;
        readSettings(t);
        PortalLinks links = PortalLinks.get(sl.getServer());
        if (!oldName.isEmpty() && !oldName.equals(name)) {
            links.remove(oldName, level.dimension(), worldPosition);
            RpAnchors.get(sl.getServer()).remove(oldName, anchorOwner());
        }
        register(sl);
        // Снимок вида с выхода ЭТОГО портала (его показывают порталы, которые ведут сюда): по кнопке или после правок.
        PortalPhotoServer.markStale(name);
        // Связали с порталом, у которого своей связи нет, — он сразу ведёт сюда (по умолчанию пара в обе стороны).
        PortalLinks.Entry target = links.find(link);
        if (target != null && target.link().isEmpty() && !name.isEmpty() && !target.name().equals(name)) {
            links.put(new PortalLinks.Entry(target.name(), target.dim(), target.pos(), target.frame(), name));
            ServerLevel tl = sl.getServer().getLevel(target.dim());
            if (tl != null && tl.isLoaded(target.pos()) && tl.getBlockEntity(target.pos()) instanceof PortalBlockEntity other && other.link.isEmpty()) {
                other.link = name;
                other.resolveLink(tl);
                other.sync();
            }
        }
        resolveLink(sl);
        sync();
    }

    static String oneOf(String v, String def, String... others) {
        if (v.equals(def)) return def;
        for (String o : others) if (o.equals(v)) return o;
        return def;
    }

    private static String hex(String s, String def) {
        s = s.replace("#", "").strip().toUpperCase(java.util.Locale.ROOT);
        return s.matches("[0-9A-F]{6}") ? s : def;
    }

    /** Подпись — перед лицом портала, на уровне глаз (не внутри плоскости). */
    @Override
    public Vec3 markerLabelPos() {
        PortalFrame f = frame();
        return f.origin().add(f.normal().scale(0.7)).add(0, Math.min(f.height() - 0.1, 1.9), 0);
    }

    @Override
    public String markerLabel() {
        String n = name.isEmpty() ? "?" : name;
        return link.isEmpty() ? n : n + " → " + link;
    }

    // ---------------------------------------------------------------- сохранение и синхронизация
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("settings", writeSettings());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("settings")) readSettings(tag.getCompound("settings"));
        if (tag.contains("linkFrame")) {
            linkFrame = PortalLinks.readFrame(tag.getCompound("linkFrame"));
            linkDim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(tag.getString("linkDim")));
        } else if (tag.contains("settings") && level != null && level.isClientSide) {
            linkFrame = null;
            linkDim = null;
        }
    }

    /** Клиентам — настройки и найденная цель (нужна для перехода и живого вида). */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag t = new CompoundTag();
        t.put("settings", writeSettings());
        if (linked()) {
            CompoundTag f = new CompoundTag();
            PortalLinks.writeFrame(f, linkFrame);
            t.put("linkFrame", f);
            t.putString("linkDim", linkDim.location().toString());
        }
        return t;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public AABB getRenderBoundingBox() {
        return planeBox(frame()).inflate(1);
    }
}
