package dev.henny.hha_blood.boss

import com.mojang.serialization.MapCodec
import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.HorizontalFacingBlock
import net.minecraft.block.MapColor
import net.minecraft.block.ShapeContext
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.EquippableComponent
import net.minecraft.entity.EntityType
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.SpawnGroup
import net.minecraft.item.Item
import net.minecraft.item.ItemPlacementContext
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.state.StateManager
import net.minecraft.util.Rarity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.shape.VoxelShape
import net.minecraft.world.BlockView

/** One of the three heads of the Blood Cerberus as a trophy: placeable (facing the player) and wearable. */
class CerberusHeadBlock(settings: Settings) : HorizontalFacingBlock(settings) {
    companion object {
        val CODEC: MapCodec<CerberusHeadBlock> = createCodec(::CerberusHeadBlock)
        private val SHAPE: VoxelShape = createCuboidShape(2.0, 0.0, 2.0, 14.0, 12.0, 14.0)
    }

    init {
        defaultState = stateManager.defaultState.with(FACING, net.minecraft.util.math.Direction.NORTH)
    }

    override fun getCodec(): MapCodec<out HorizontalFacingBlock> = CODEC

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getPlacementState(ctx: ItemPlacementContext): BlockState =
        defaultState.with(FACING, ctx.horizontalPlayerFacing.opposite)

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = SHAPE
}

/** Registration of the Slaughter Pit content (bosses, summoning item, trophies). */
object BossContent {
    private fun key(name: String): RegistryKey<EntityType<*>> = RegistryKey.of(RegistryKeys.ENTITY_TYPE, HhaBlood.id(name))

    private val MINOTAUR_KEY = key("crimson_minotaur")
    private val CERBERUS_KEY = key("blood_cerberus")

    @JvmField val MINOTAUR: EntityType<MinotaurEntity> = Registry.register(
        Registries.ENTITY_TYPE, MINOTAUR_KEY,
        EntityType.Builder.create(::MinotaurEntity, SpawnGroup.MONSTER)
            .dimensions(1.9f, 4.0f).eyeHeight(3.5f).makeFireImmune().maxTrackingRange(10).trackingTickInterval(1)
            .build(MINOTAUR_KEY),
    )

    @JvmField val CERBERUS: EntityType<CerberusEntity> = Registry.register(
        Registries.ENTITY_TYPE, CERBERUS_KEY,
        EntityType.Builder.create(::CerberusEntity, SpawnGroup.MONSTER)
            .dimensions(3.3f, 3.6f).eyeHeight(3.0f).makeFireImmune().maxTrackingRange(10).trackingTickInterval(1)
            .build(CERBERUS_KEY),
    )

    private fun headSettings() = AbstractBlock.Settings.create().mapColor(MapColor.BLACK).strength(1.0f)
        .sounds(BlockSoundGroup.BONE).pistonBehavior(PistonBehavior.DESTROY).nonOpaque()

    @JvmField val HEAD_BLEED_BLOCK: Block = Blocks.register(RegistryKey.of(RegistryKeys.BLOCK, HhaBlood.id("cerberus_head_bleed")), ::CerberusHeadBlock, headSettings())
    @JvmField val HEAD_FLAME_BLOCK: Block = Blocks.register(RegistryKey.of(RegistryKeys.BLOCK, HhaBlood.id("cerberus_head_flame")), ::CerberusHeadBlock, headSettings().luminance { 6 })
    @JvmField val HEAD_HOWL_BLOCK: Block = Blocks.register(RegistryKey.of(RegistryKeys.BLOCK, HhaBlood.id("cerberus_head_howl")), ::CerberusHeadBlock, headSettings())

    /** "Blutopfer" - laid on the Sanguine Altar to call the Minotaur and his hound back after a victory. */
    lateinit var BLOOD_OFFERING: Item private set
    lateinit var HEAD_BLEED: Item private set
    lateinit var HEAD_FLAME: Item private set
    lateinit var HEAD_HOWL: Item private set

    private fun head(block: Block): Item = Items.register(block) {
        it.rarity(Rarity.EPIC).fireproof().maxCount(16)
            .component(DataComponentTypes.EQUIPPABLE, EquippableComponent.builder(EquipmentSlot.HEAD).swappable(true).build())
    }

    fun init() {
        BossSounds.init()
        Cine.registerPayloads()
        FabricDefaultAttributeRegistry.register(MINOTAUR, MinotaurEntity.createAttributes())
        FabricDefaultAttributeRegistry.register(CERBERUS, CerberusEntity.createAttributes())
        BLOOD_OFFERING = Items.register(
            RegistryKey.of(RegistryKeys.ITEM, HhaBlood.id("blood_offering")), ::Item,
            Item.Settings().rarity(Rarity.EPIC).maxCount(16).fireproof(),
        )
        HEAD_BLEED = head(HEAD_BLEED_BLOCK)
        HEAD_FLAME = head(HEAD_FLAME_BLOCK)
        HEAD_HOWL = head(HEAD_HOWL_BLOCK)
        PitFight.init()
        BossDebug.init()
    }
}
