package faygolover.rpportals;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Клиент: загруженные порталы (для перехода, отрисовки и «держать ПКМ»). Без клиентских классов — грузится и на сервере. */
public final class PortalClientSet {
    private PortalClientSet() {}

    private static final Set<PortalBlockEntity> ALL = Collections.newSetFromMap(new WeakHashMap<>());

    public static synchronized void add(PortalBlockEntity be) {
        ALL.add(be);
    }

    public static synchronized void remove(PortalBlockEntity be) {
        ALL.remove(be);
    }

    public static synchronized PortalBlockEntity[] snapshot() {
        return ALL.toArray(new PortalBlockEntity[0]);
    }

    public static synchronized void clear() {
        ALL.clear();
    }
}
