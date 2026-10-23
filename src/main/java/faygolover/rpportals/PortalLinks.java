package faygolover.rpportals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Порталы по имени (в мире {@code data/rpportals_links.dat}): где стоит портал и его плоскость. Связь «портал → портал»
 * ищет цель здесь, поэтому цель может быть в незагруженном чанке или другом измерении.
 */
public final class PortalLinks extends SavedData {
    private static final String FILE = "rpportals_links";

    public record Entry(String name, ResourceKey<Level> dim, BlockPos pos, PortalFrame frame, String link) {}

    private final Map<String, Entry> byName = new HashMap<>();

    public static PortalLinks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PortalLinks::load, PortalLinks::new, FILE);
    }

    @Nullable
    public Entry find(String name) {
        return name == null || name.isEmpty() ? null : byName.get(name);
    }

    public void put(Entry e) {
        if (e.name().isEmpty()) return;
        Entry old = byName.put(e.name(), e);
        if (!e.equals(old)) setDirty();
    }

    /** Убрать, только если запись от этого же блока. */
    public void remove(String name, ResourceKey<Level> dim, BlockPos pos) {
        Entry e = byName.get(name);
        if (e != null && e.dim().equals(dim) && e.pos().equals(pos)) {
            byName.remove(name);
            setDirty();
        }
    }

    private static PortalLinks load(CompoundTag tag) {
        PortalLinks d = new PortalLinks();
        for (Tag t : tag.getList("portals", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Entry e = new Entry(c.getString("name"), ResourceKey.create(Registries.DIMENSION, new ResourceLocation(c.getString("dim"))),
                    BlockPos.of(c.getLong("pos")), readFrame(c), c.getString("link"));
            d.byName.put(e.name(), e);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry e : byName.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("name", e.name());
            c.putString("dim", e.dim().location().toString());
            c.putLong("pos", e.pos().asLong());
            writeFrame(c, e.frame());
            c.putString("link", e.link());
            list.add(c);
        }
        tag.put("portals", list);
        return tag;
    }

    public static void writeFrame(CompoundTag c, PortalFrame f) {
        c.putDouble("ox", f.origin().x);
        c.putDouble("oy", f.origin().y);
        c.putDouble("oz", f.origin().z);
        c.putFloat("yaw", f.yaw());
        c.putDouble("w", f.width());
        c.putDouble("h", f.height());
    }

    public static PortalFrame readFrame(CompoundTag c) {
        return new PortalFrame(new Vec3(c.getDouble("ox"), c.getDouble("oy"), c.getDouble("oz")), c.getFloat("yaw"),
                c.getDouble("w"), c.getDouble("h"));
    }
}
