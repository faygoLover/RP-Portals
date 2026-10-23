package faygolover.rpportals.client;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/** Включён ли шейдерпак Oculus/Iris (через их API, без зависимости). */
final class ShaderPacks {
    private ShaderPacks() {}

    private static boolean tried;
    private static Object api;
    private static Method inUse;

    static boolean active() {
        if (!tried) {
            tried = true;
            if (ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris")) {
                for (String cls : new String[]{"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"}) {
                    try {
                        Class<?> c = Class.forName(cls);
                        api = c.getMethod("getInstance").invoke(null);
                        inUse = c.getMethod("isShaderPackInUse");
                        break;
                    } catch (Throwable ignored) {
                        // другой пакет API
                    }
                }
            }
        }
        if (api == null) return false;
        try {
            return (Boolean) inUse.invoke(api);
        } catch (Throwable t) {
            return false;
        }
    }
}
