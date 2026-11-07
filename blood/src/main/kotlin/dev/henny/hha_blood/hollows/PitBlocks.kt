package dev.henny.hha_blood.hollows

import com.mojang.serialization.MapCodec
import dev.henny.hha_blood.BloodParticles
import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockRenderType
import net.minecraft.block.BlockState
import net.minecraft.block.BlockWithEntity
import net.minecraft.block.MapColor
import net.minecraft.block.ShapeContext
import net.minecraft.block.entity.BlockEntity
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityCollisionHandler
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemPlacementContext
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.state.StateManager
import net.minecraft.state.property.BooleanProperty
import net.minecraft.state.property.EnumProperty
import net.minecraft.state.property.Properties
import net.minecraft.util.BlockMirror
import net.minecraft.util.BlockRotation
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView
import net.minecraft.world.World

/**
 * The "living" blocks of the Vein Hollows and the Slaughter Pit (round "Biom + Arena cooler"):
 *  - [WATCHING_EYE]  a flesh block with an eye that follows the viewer (client renderer, blinks)
 *  - [PIT_HEART]     invisible anchor at the dome apex; the client renders the giant beating heart below it
 *  - [BLOOD_FALL]    a curtain of pouring blood (no collision, no spreading); RISING = flows upwards and
 *                    carries entities up like an elevator (sneak to sink slowly)
 *  - [BLOOD_GLOW]    "Blutglut", a glowing clot of blood (light 15)
 *  - [BLOOD_VENT]    "Blutschlot", a vent in the cave floor that hurls whoever steps on it into the air
 */
object PitBlocks {

    lateinit var WATCHING_EYE: Block private set
    lateinit var PIT_HEART: Block private set
    lateinit var BLOOD_FALL: Block private set
    lateinit var BLOOD_GLOW: Block private set
    lateinit var BLOOD_VENT: Block private set

    lateinit var WATCHING_EYE_BE: BlockEntityType<WatchingEyeBlockEntity> private set
    lateinit var PIT_HEART_BE: BlockEntityType<PitHeartBlockEntity> private set

    /** Blocks that get an item (creative tab, after the other Hollows blocks). */
    val WITH_ITEM: List<Block> get() = listOf(WATCHING_EYE, BLOOD_GLOW, BLOOD_VENT)

    fun init() {
        WATCHING_EYE = register("watching_eye", ::WatchingEyeBlock,
            AbstractBlock.Settings.create().mapColor(MapColor.DARK_RED).strength(0.8f).sounds(BlockSoundGroup.HONEY).luminance { 3 })
        PIT_HEART = register("pit_heart", ::PitHeartBlock,
            AbstractBlock.Settings.create().mapColor(MapColor.DARK_RED).strength(-1.0f, 3600000.0f).dropsNothing()
                .noCollision().nonOpaque().luminance { 15 }.pistonBehavior(PistonBehavior.BLOCK))
        BLOOD_FALL = register("blood_fall", ::BloodFallBlock,
            AbstractBlock.Settings.create().mapColor(MapColor.DARK_RED).noCollision().replaceable().nonOpaque()
                .strength(100.0f).dropsNothing().luminance { 5 }.pistonBehavior(PistonBehavior.DESTROY)
                .sounds(BlockSoundGroup.HONEY).suffocates { _, _, _ -> false }.blockVision { _, _, _ -> false })
        BLOOD_GLOW = register("blood_glow", ::Block,
            AbstractBlock.Settings.create().mapColor(MapColor.BRIGHT_RED).strength(0.6f).sounds(BlockSoundGroup.FROGLIGHT).luminance { 15 })
        BLOOD_VENT = register("blood_vent", ::BloodVentBlock,
            AbstractBlock.Settings.create().mapColor(MapColor.DARK_CRIMSON).strength(1.2f, 6.0f)
                .sounds(BlockSoundGroup.NETHERRACK).luminance { 6 })

        WATCHING_EYE_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, HhaBlood.id("watching_eye"),
            FabricBlockEntityTypeBuilder.create(::WatchingEyeBlockEntity, WATCHING_EYE).build())
        PIT_HEART_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE, HhaBlood.id("pit_heart"),
            FabricBlockEntityTypeBuilder.create(::PitHeartBlockEntity, PIT_HEART).build())
    }

    private fun register(name: String, factory: (AbstractBlock.Settings) -> Block, settings: AbstractBlock.Settings): Block =
        net.minecraft.block.Blocks.register(RegistryKey.of(RegistryKeys.BLOCK, HhaBlood.id(name)), factory, settings)
}

// ------------------------------------------------------------------------------------------ watching eye

class WatchingEyeBlock(settings: Settings) : BlockWithEntity(settings) {
    init { defaultState = stateManager.defaultState.with(FACING, Direction.NORTH) }

    override fun getCodec(): MapCodec<out BlockWithEntity> = CODEC
    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) { builder.add(FACING) }
    override fun getPlacementState(ctx: ItemPlacementContext): BlockState = defaultState.with(FACING, ctx.playerLookDirection.opposite)
    override fun rotate(state: BlockState, rotation: BlockRotation): BlockState = state.with(FACING, rotation.rotate(state.get(FACING)))
    override fun mirror(state: BlockState, mirror: BlockMirror): BlockState = state.rotate(mirror.getRotation(state.get(FACING)))
    override fun createBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = WatchingEyeBlockEntity(pos, state)

    companion object {
        val FACING: EnumProperty<Direction> = Properties.FACING
        val CODEC: MapCodec<WatchingEyeBlock> = createCodec(::WatchingEyeBlock)
    }
}

/** No data - the eye's gaze and blinking are computed by the client renderer from the camera and the world time. */
class WatchingEyeBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(PitBlocks.WATCHING_EYE_BE, pos, state)

// ------------------------------------------------------------------------------------------ the pit's heart

/** Anchor of the giant heart hanging from the dome of the Slaughter Pit (the heart itself is client-rendered). */
class PitHeartBlock(settings: Settings) : BlockWithEntity(settings) {
    override fun getCodec(): MapCodec<out BlockWithEntity> = CODEC
    override fun createBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = PitHeartBlockEntity(pos, state)
    override fun getRenderType(state: BlockState): BlockRenderType = BlockRenderType.INVISIBLE
    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = VoxelShapes.empty()

    override fun randomDisplayTick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        // blood dripping from the heart's tip now and then
        if (random.nextInt(3) == 0) {
            val x = pos.x + 0.5 + random.nextGaussian() * 0.9
            val z = pos.z + 0.5 + random.nextGaussian() * 0.9
            world.addParticleClient(BloodParticles.DRIP, x, pos.y - 19.0, z, 0.0, 0.0, 0.0)
        }
    }

    companion object {
        val CODEC: MapCodec<PitHeartBlock> = createCodec(::PitHeartBlock)
    }
}

class PitHeartBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(PitBlocks.PIT_HEART_BE, pos, state) {
    override fun setWorld(world: World) {
        super.setWorld(world)
        if (world.isClient) HEARTS += this
    }

    override fun markRemoved() {
        super.markRemoved()
        HEARTS -= this
    }

    companion object {
        /** Client side: hearts that are currently loaded (the heartbeat sound plays at the nearest one). */
        val HEARTS: MutableSet<PitHeartBlockEntity> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    }
}

// ------------------------------------------------------------------------------------------ blood fall

/**
 * Pouring blood that never spreads: a decoration column (falls from the dome, from cave ceilings) or, with
 * RISING, a column flowing upwards that lifts entities like an elevator (sneaking sinks slowly, no fall damage).
 */
class BloodFallBlock(settings: Settings) : Block(settings) {
    init { defaultState = stateManager.defaultState.with(RISING, false) }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) { builder.add(RISING) }
    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = VoxelShapes.empty()
    override fun getCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = VoxelShapes.empty()
    override fun isSideInvisible(state: BlockState, stateFrom: BlockState, direction: Direction): Boolean =
        stateFrom.isOf(this) || super.isSideInvisible(state, stateFrom, direction)
    override fun isTransparent(state: BlockState): Boolean = true

    override fun onEntityCollision(state: BlockState, world: World, pos: BlockPos, entity: Entity, handler: EntityCollisionHandler, bl: Boolean) {
        val v = entity.velocity
        if (state.get(RISING)) {
            val vy = if (entity.isSneaking) maxOf(v.y - 0.02, -0.22) else minOf(v.y + 0.085, 0.55)
            entity.setVelocity(v.x, vy, v.z)
            entity.onLanding()
        } else {
            entity.setVelocity(v.x, maxOf(v.y - 0.015, -0.7), v.z)
        }
        if (entity.isOnFire) entity.extinguish()
    }

    override fun randomDisplayTick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        val rising = state.get(RISING)
        val below = world.getBlockState(pos.down())
        val above = world.getBlockState(pos.up())
        val x = pos.x + 0.5
        val z = pos.z + 0.5
        if (!rising && !below.isOf(this)) {
            // the foot of a fall: splashes and a little red spray
            for (i in 0 until 2) {
                world.addParticleClient(BloodParticles.GLOB, x + random.nextGaussian() * 0.3, pos.y + 0.1, z + random.nextGaussian() * 0.3,
                    random.nextGaussian() * 0.08, 0.12 + random.nextDouble() * 0.12, random.nextGaussian() * 0.08)
            }
            if (random.nextInt(3) == 0) world.addParticleClient(BloodParticles.SPLAT, x, pos.y + 0.15, z, 0.0, 0.0, 0.0)
            if (random.nextInt(6) == 0) world.playSoundClient(x, pos.y + 0.5, z, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.AMBIENT,
                0.5f, 0.55f + random.nextFloat() * 0.15f, false)
        } else if (rising && !above.isOf(this)) {
            // the head of a rising column: blood sprays against the ceiling / out of the shaft
            for (i in 0 until 2) {
                world.addParticleClient(BloodParticles.GLOB, x + random.nextGaussian() * 0.35, pos.y + 0.9, z + random.nextGaussian() * 0.35,
                    random.nextGaussian() * 0.1, 0.05 + random.nextDouble() * 0.15, random.nextGaussian() * 0.1)
            }
            if (random.nextInt(6) == 0) world.playSoundClient(x, pos.y + 0.5, z, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundCategory.AMBIENT,
                0.5f, 0.5f + random.nextFloat() * 0.1f, false)
        }
        if (rising && random.nextInt(4) == 0) {
            world.addParticleClient(HollowsParticles.MOTE, x + random.nextGaussian() * 0.4, pos.y + random.nextDouble(), z + random.nextGaussian() * 0.4,
                0.0, 0.12, 0.0)
        }
        if (!rising && random.nextInt(5) == 0) {
            world.addParticleClient(BloodParticles.DRIP_FALL, x + (random.nextDouble() - 0.5) * 1.1, pos.y + random.nextDouble(), z + (random.nextDouble() - 0.5) * 1.1,
                0.0, -0.2, 0.0)
        }
    }

    companion object {
        val RISING: BooleanProperty = BooleanProperty.of("rising")
    }
}

// ------------------------------------------------------------------------------------------ blood vent

/** A vent in the cave floor: stepping on it (not sneaking) hurls you up in a fountain of blood. */
class BloodVentBlock(settings: Settings) : Block(settings) {

    override fun onSteppedOn(world: World, pos: BlockPos, state: BlockState, entity: Entity) {
        if (entity !is LivingEntity || entity.bypassesSteppingEffects() || entity.velocity.y > 0.2) {
            super.onSteppedOn(world, pos, state, entity)
            return
        }
        entity.setVelocity(entity.velocity.x * 0.6, LAUNCH, entity.velocity.z * 0.6)
        entity.velocityDirty = true
        if (world.isClient) {
            for (i in 0 until 26) {
                world.addParticleClient(BloodParticles.GLOB, pos.x + 0.5 + world.random.nextGaussian() * 0.2, pos.y + 1.05, pos.z + 0.5 + world.random.nextGaussian() * 0.2,
                    world.random.nextGaussian() * 0.12, 0.45 + world.random.nextDouble() * 0.5, world.random.nextGaussian() * 0.12)
            }
            world.addParticleClient(ParticleTypes.EXPLOSION, pos.x + 0.5, pos.y + 1.3, pos.z + 0.5, 0.0, 0.0, 0.0)
        } else if (world is ServerWorld) {
            world.playSound(null, pos, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 0.9f, 0.55f)
            world.playSound(null, pos, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, SoundCategory.BLOCKS, 1.2f, 0.6f)
        }
    }

    override fun randomDisplayTick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        if (!world.getBlockState(pos.up()).isAir) return
        // steam of blood above the crater, sometimes a little spurt
        world.addParticleClient(HollowsParticles.MOTE, pos.x + 0.5 + random.nextGaussian() * 0.15, pos.y + 1.05, pos.z + 0.5 + random.nextGaussian() * 0.15,
            0.0, 0.05 + random.nextDouble() * 0.05, 0.0)
        if (random.nextInt(5) == 0) {
            for (i in 0 until 5) {
                world.addParticleClient(BloodParticles.GLOB, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5,
                    random.nextGaussian() * 0.05, 0.2 + random.nextDouble() * 0.25, random.nextGaussian() * 0.05)
            }
            if (random.nextInt(3) == 0) world.playSoundClient(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, SoundEvents.BLOCK_LAVA_POP, SoundCategory.BLOCKS, 0.4f, 0.5f, false)
        }
    }

    companion object {
        /** Upward speed of the launch (~5.5 blocks high). */
        const val LAUNCH = 1.0
    }
}

