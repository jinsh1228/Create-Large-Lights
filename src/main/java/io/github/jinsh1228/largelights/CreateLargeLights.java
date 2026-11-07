package io.github.jinsh1228.largelights;

import java.util.function.Supplier;

import org.haruyq.createmediasystems.CreateMediaSystems;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(CreateLargeLights.MOD_ID)
public class CreateLargeLights {
    public static final String MOD_ID = "create_large_lights";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    /** Create: Media Systems' own tab, which these join. */
    private static final ResourceKey<CreativeModeTab> CMS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, CreateMediaSystems.id("main"));

    public static final DeferredBlock<FloodlightBlock> FLOODLIGHT = BLOCKS.register("floodlight",
            () -> new FloodlightBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(3).noOcclusion(),
                    FloodlightKind.FLOODLIGHT));
    public static final DeferredItem<BlockItem> FLOODLIGHT_ITEM = ITEMS.registerSimpleBlockItem("floodlight", FLOODLIGHT);
    public static final DeferredBlock<FloodlightBlock> STADIUM_LIGHT = BLOCKS.register("stadium_light",
            () -> new FloodlightBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK).strength(4).noOcclusion(),
                    FloodlightKind.STADIUM_LIGHT));
    public static final DeferredItem<BlockItem> STADIUM_LIGHT_ITEM =
            ITEMS.registerSimpleBlockItem("stadium_light", STADIUM_LIGHT, new Item.Properties());
    public static final Supplier<BlockEntityType<FloodlightBlock.Entity>> FLOODLIGHT_ENTITY = BLOCK_ENTITIES.register(
            "floodlight", () -> BlockEntityType.Builder.of(FloodlightBlock.Entity::new, FLOODLIGHT.get(),
                    STADIUM_LIGHT.get()).build(null));

    public CreateLargeLights(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, FloodlightConfig.SPEC);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(CreateLargeLights::registerPayloads);
        modBus.addListener(CreateLargeLights::addToTab);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        AimPacket.register(event.registrar("1"));
    }

    private static void addToTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CMS_TAB)) {
            event.accept(FLOODLIGHT_ITEM.get());
            event.accept(STADIUM_LIGHT_ITEM.get());
        }
    }
}
