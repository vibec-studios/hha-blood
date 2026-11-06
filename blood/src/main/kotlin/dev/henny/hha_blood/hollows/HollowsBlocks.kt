package dev.henny.hha_blood.hollows

import dev.henny.hha_blood.HhaBlood
import net.minecraft.block.AbstractBlock
import net.minecraft.block.AmethystClusterBlock
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.block.MapColor
import net.minecraft.block.SlabBlock
import net.minecraft.block.StairsBlock
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.NoteBlockInstrument
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.item.Item
import net.minecraft.item.Items
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.util.Rarity

/**
 * Blocks of the "Aderngrotten" (Vein Hollows) - the blood cave biome under the Nether lava sea.
 * Textures/models/blockstates/loot tables come from `tools/textures/GenHollows.java`.
 */
object HollowsBlocks {

    private fun stone(color: MapColor = MapColor.DARK_CRIMSON) = AbstractBlock.Settings.create()
        .mapColor(color).instrument(NoteBlockInstrument.BASEDRUM).requiresTool()
        .strength(2.0f, 7.0f).sounds(BlockSoundGroup.NETHER_BRICKS)

    @JvmField val BLOOD_STONE: Block = block("blood_stone", stone().sounds(BlockSoundGroup.NETHERRACK).strength(1.2f, 6.0f))
    @JvmField val POLISHED_BLOOD_STONE: Block = block("polished_blood_stone", stone())
    @JvmField val BLOOD_STONE_BRICKS: Block = block("blood_stone_bricks", stone())
    @JvmField val CHISELED_BLOOD_STONE: Block = block("chiseled_blood_stone", stone())
    @JvmField val BLOOD_STONE_BRICK_STAIRS: Block = register("blood_stone_brick_stairs", { StairsBlock(BLOOD_STONE_BRICKS.defaultState, it) }, stone())
    @JvmField val BLOOD_STONE_BRICK_SLAB: Block = register("blood_stone_brick_slab", ::SlabBlock, stone())
    @JvmField val BLOOD_STONE_BRICK_WALL: Block = register("blood_stone_brick_wall", ::WallBlock, stone().solid())

    /** Blood stone with glowing, pulsing veins (animated texture). */
    @JvmField val VEIN_STONE: Block = block("vein_stone", stone(MapColor.BRIGHT_RED).luminance { 7 }.strength(1.5f, 6.0f))

    /**
     * Clotted blood - works like a honey block (Mats): you stick to its sides and slide down slowly, it
     * slows you down and dampens jumps; translucent jelly look with a dark core (honey-like model).
     */
    @JvmField val CLOTTED_BLOOD: Block = register(
        "clotted_blood", { net.minecraft.block.HoneyBlock(it) },
        AbstractBlock.Settings.create().mapColor(MapColor.DARK_RED).strength(0.6f)
            .velocityMultiplier(0.45f).jumpVelocityMultiplier(0.55f).nonOpaque().sounds(BlockSoundGroup.HONEY),
    )

    @JvmField val BLOOD_CRYSTAL_BLOCK: Block = block(
        "blood_crystal_block",
        AbstractBlock.Settings.create().mapColor(MapColor.RED).strength(1.5f).requiresTool()
            .sounds(BlockSoundGroup.AMETHYST_BLOCK).luminance { 6 },
    )
    @JvmField val BLOOD_CRYSTAL_CLUSTER: Block = register(
        "blood_crystal_cluster", { AmethystClusterBlock(7.0f, 10.0f, it) },
        AbstractBlock.Settings.create().mapColor(MapColor.RED).solid().nonOpaque()
            .sounds(BlockSoundGroup.AMETHYST_CLUSTER).strength(1.5f).luminance { 9 }.pistonBehavior(PistonBehavior.DESTROY),
    )
    @JvmField val SMALL_BLOOD_CRYSTAL: Block = register(
        "small_blood_crystal", { AmethystClusterBlock(3.0f, 4.0f, it) },
        AbstractBlock.Settings.create().mapColor(MapColor.RED).solid().nonOpaque()
            .sounds(BlockSoundGroup.SMALL_AMETHYST_BUD).strength(1.0f).luminance { 4 }.pistonBehavior(PistonBehavior.DESTROY),
    )

    /** The sacrificial altar in front of the Blood Prince's throne (summoning comes in round 2). */
    @JvmField val SANGUINE_ALTAR: Block = block(
        "sanguine_altar",
        stone(MapColor.DARK_CRIMSON).strength(50.0f, 1200.0f).luminance { 10 },
    )

    lateinit var BLOOD_CRYSTAL_SHARD: Item private set

    /** Order = order in the creative tab. */
    val ALL: List<Block> get() = listOf(
        BLOOD_STONE, POLISHED_BLOOD_STONE, BLOOD_STONE_BRICKS, CHISELED_BLOOD_STONE,
        BLOOD_STONE_BRICK_STAIRS, BLOOD_STONE_BRICK_SLAB, BLOOD_STONE_BRICK_WALL,
        VEIN_STONE, CLOTTED_BLOOD, BLOOD_CRYSTAL_BLOCK, BLOOD_CRYSTAL_CLUSTER, SMALL_BLOOD_CRYSTAL, SANGUINE_ALTAR,
    )

    fun init() {
        for (b in ALL) {
            if (b === SANGUINE_ALTAR) Items.register(b) { it.rarity(Rarity.EPIC).fireproof() } else Items.register(b)
        }
        BLOOD_CRYSTAL_SHARD = Items.register(RegistryKey.of(RegistryKeys.ITEM, HhaBlood.id("blood_crystal_shard")), ::Item, Item.Settings().rarity(Rarity.UNCOMMON))
    }

    private fun block(name: String, settings: AbstractBlock.Settings): Block = register(name, ::Block, settings)

    private fun register(name: String, factory: (AbstractBlock.Settings) -> Block, settings: AbstractBlock.Settings): Block =
        Blocks.register(RegistryKey.of(RegistryKeys.BLOCK, HhaBlood.id(name)), factory, settings)
}
