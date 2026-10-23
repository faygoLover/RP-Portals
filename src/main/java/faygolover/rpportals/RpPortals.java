package faygolover.rpportals;

import com.mojang.logging.LogUtils;
import faygolover.rpcore.api.RpCoreAPI;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * RP Portals — порталы семейства RP: проходы любого размера и формы, которые ставит ведущий. Вид: цвет, туман,
 * рябь, обод, живой вид той стороны, невидимый. Переход бесшовный, связь по имени, режим «держать ПКМ» с дверями.
 * Требует RP Core (вкладка, метки и окно настроек ведущего, именованные точки).
 */
@Mod(RpPortals.MODID)
public class RpPortals {
    public static final String MODID = "rpportals";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RpPortals() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        PortalRegistry.register(modBus);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(PortalPhotoServer::onLogout);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(PortalPhotoServer::onServerStopped);
        modBus.addListener(this::commonSetup);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> faygolover.rpportals.client.PortalClient.init(modBus));
    }

    private void commonSetup(FMLCommonSetupEvent e) {
        PortalNet.register();
        e.enqueueWork(() -> RpCoreAPI.registerModule(MODID, "порталы ведущего: проходы любого вида, бесшовный переход, вид той стороны"));
    }
}
