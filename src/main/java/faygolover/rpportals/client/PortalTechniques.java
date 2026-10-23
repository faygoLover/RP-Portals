package faygolover.rpportals.client;

import faygolover.flrender.api.FlRender;
import faygolover.flrender.api.Quality;
import faygolover.flrender.api.Technique;
import faygolover.rpportals.RpPortals;

/** Техники RP Portals во вкладке FL Render (подписи — в lang: {@code flrender.technique.rpportals.<ключ>}). */
final class PortalTechniques {
    private PortalTechniques() {}

    /** Вид «живой»: та сторона рисуется каждый кадр. Выключено — сплошной цвет портала. */
    static final Technique LIVE_VIEW = FlRender.technique(RpPortals.MODID, "live_view", Quality.HIGH);
    /** Вид «снимок»: снимок той стороны с параллаксом. Выключено — сплошной цвет портала. */
    static final Technique PHOTO = FlRender.technique(RpPortals.MODID, "photo", Quality.MEDIUM);

    static void init() {
    }
}
