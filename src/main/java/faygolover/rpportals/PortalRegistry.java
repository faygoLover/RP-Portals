package faygolover.rpportals;

import faygolover.rpcore.api.RpCoreAPI;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Блок и предмет портала; предмет — во вкладке «RP: разное» RP Core. */
public final class PortalRegistry {
    private PortalRegistry() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, RpPortals.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, RpPortals.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, RpPortals.MODID);

    public static final RegistryObject<Block> PORTAL = BLOCKS.register("portal", () -> new PortalBlock(BlockBehaviour.Properties.of()
            .noCollission().noOcclusion().strength(-1.0f, 3600000.0f).noLootTable().pushReaction(PushReaction.BLOCK)
            .sound(SoundType.STONE).isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false)
            .isRedstoneConductor((s, l, p) -> false)));

    public static final RegistryObject<Item> PORTAL_ITEM = ITEMS.register("portal", () -> new BlockItem(PORTAL.get(), new Item.Properties()));

    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<PortalBlockEntity>> PORTAL_BE = BLOCK_ENTITIES.register("portal",
            () -> BlockEntityType.Builder.of(PortalBlockEntity::new, PORTAL.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(PortalRegistry::onTab);
    }

    private static void onTab(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == RpCoreAPI.MISC_TAB) e.accept(PORTAL_ITEM);
    }
}
