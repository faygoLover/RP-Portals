package faygolover.rpportals.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import faygolover.rpcore.client.gm.GmSchema;
import faygolover.rpportals.PortalClientSet;
import faygolover.rpportals.PortalRegistry;
import faygolover.rpportals.RpPortals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.io.IOException;

/** Клиент RP Portals: шейдер поверхности, отрисовка, переход, «держать ПКМ» (кольцо у прицела), окно настроек. */
public final class PortalClient {
    private PortalClient() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(PortalClient::onShaders);
        modBus.addListener(PortalClient::onSetup);
        modBus.addListener(PortalClient::onOverlays);
        MinecraftForge.EVENT_BUS.addListener(PortalRender::onRenderLevel);
        MinecraftForge.EVENT_BUS.addListener(PortalCrossing::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(PortalCrossing::onInteract);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            PortalClientSet.clear();
            PortalCrossing.clear();
            PortalView.clearAll();
        });
    }

    private static void onShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), new ResourceLocation(RpPortals.MODID, "portal"),
                    DefaultVertexFormat.POSITION_TEX), s -> PortalRender.shader = s);
        } catch (IOException ex) {
            RpPortals.LOGGER.error("RP Portals: шейдер портала не загрузился", ex);
        }
    }

    private static void onOverlays(RegisterGuiOverlaysEvent e) {
        e.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "hold", PortalClient::renderHold);
        e.registerAboveAll("fade", PortalClient::renderFade);
    }

    /** Кольцо «держать ПКМ» вокруг прицела: заполняется по часовой стрелке. */
    private static void renderHold(ForgeGui gui, GuiGraphics g, float partial, int w, int h) {
        if (PortalCrossing.holdTarget == null || Minecraft.getInstance().options.hideGui) return;
        float prog = PortalCrossing.holdProgress;
        int cx = w / 2, cy = h / 2, segs = 32, radius = 9;
        for (int i = 0; i < segs; i++) {
            float a = (float) (i / (double) segs * Math.PI * 2 - Math.PI / 2);
            int x = cx + Math.round(Mth.cos(a) * radius), y = cy + Math.round(Mth.sin(a) * radius);
            boolean done = i < prog * segs;
            g.fill(x - 1, y - 1, x + 1, y + 1, done ? 0xF0FFFFFF : 0x50FFFFFF);
        }
    }

    /** Затемнение при переходе в другое измерение. */
    private static void renderFade(ForgeGui gui, GuiGraphics g, float partial, int w, int h) {
        if (PortalCrossing.fadeTicks <= 0) return;
        int a = Math.min(255, (int) (PortalCrossing.fadeTicks * 255 / 12f));
        g.fill(0, 0, w, h, a << 24);
    }

    private static void onSetup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> GmSchema.register(PortalRegistry.PORTAL.get(), new GmSchema("portal")
                .text("name", 32).text("link", 32)
                .decimal("yaw", -180, 180).decimal("width", 0.25, 32).decimal("height", 0.25, 32)
                .choice("place", "center", "front", "back")
                .decimal("offX", -8, 8).decimal("offY", -8, 8).decimal("offZ", -8, 8)
                .choice("shape", "rect", "rounded", "oval")
                .when("shape", "rounded").decimal("corner", 0, 0.5)
                .always().choice("look", "solid", "live", "invisible")
                .text("color", 7).decimal("alpha", 0, 1).bool("glow")
                .decimal("fog", 0, 1).decimal("ripple", 0, 1)
                .choice("rim", "none", "thin", "thick")
                .when("rim", "thin", "thick").text("rimColor", 7)
                .when("look", "live").decimal("viewDepth", 2, 48).decimal("viewFog", 0, 1)
                .always().choice("sides", "front", "both").choice("who", "players", "all")
                .choice("activation", "walk", "hold")
                .when("activation", "hold").decimal("holdSeconds", 0.2, 10).bool("openDoors").decimal("openSeconds", 0.5, 60)
                .always().integer("cooldown", 0, 1200)
                .action("rpportals.gm.preset.door", t -> preset(t, "door"))
                .action("rpportals.gm.preset.fog", t -> preset(t, "fog"))
                .action("rpportals.gm.preset.portal", t -> preset(t, "portal"))));
    }

    /** Заготовки вида: чёрный проём-дверь, туманная стена, портал «как в Portal» (овал, обод, живой вид). */
    private static void preset(net.minecraft.nbt.CompoundTag t, String kind) {
        switch (kind) {
            case "door" -> {
                t.putString("shape", "rect");
                t.putString("look", "solid");
                t.putString("color", "000000");
                t.putFloat("alpha", 1f);
                t.putFloat("fog", 0f);
                t.putFloat("ripple", 0f);
                t.putString("rim", "none");
                t.putFloat("width", 1f);
                t.putFloat("height", 2f);
            }
            case "fog" -> {
                t.putString("shape", "rect");
                t.putString("look", "solid");
                t.putString("color", "D8DCE0");
                t.putFloat("alpha", 0.85f);
                t.putFloat("fog", 1f);
                t.putFloat("ripple", 0.3f);
                t.putString("rim", "none");
                t.putBoolean("glow", true);
            }
            case "portal" -> {
                t.putString("shape", "oval");
                t.putString("look", "live");
                t.putString("color", "101418");
                t.putFloat("fog", 0f);
                t.putFloat("ripple", 0.15f);
                t.putString("rim", "thin");
                t.putString("rimColor", "3FA9FF");
                t.putFloat("viewDepth", 12f);
                t.putFloat("viewFog", 0.5f);
            }
            default -> {}
        }
    }
}
