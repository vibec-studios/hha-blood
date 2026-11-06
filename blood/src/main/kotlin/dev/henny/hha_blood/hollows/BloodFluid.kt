package dev.henny.hha_blood.hollows

import dev.henny.hha_blood.BloodParticles
import dev.henny.hha_blood.HhaBlood
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.FluidBlock
import net.minecraft.block.MapColor
import net.minecraft.block.entity.BlockEntity
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityCollisionHandler
import net.minecraft.entity.LivingEntity
import net.minecraft.fluid.FlowableFluid
import net.minecraft.fluid.Fluid
import net.minecraft.fluid.FluidState
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.particle.ParticleEffect
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.FluidTags
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.sound.SoundEvent
import net.minecraft.state.StateManager
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.BlockView
import net.minecraft.world.World
import net.minecraft.world.WorldAccess
import net.minecraft.world.WorldView
import net.minecraft.entity.CollisionEvent
import java.util.Optional

/**
 * Blood - the liquid of the Vein Hollows lakes. Behaves like water (it is in the `minecraft:water`
 * fluid tag: swimming, drowning, breaks falls, extinguishes, red underwater fog from the biome) but it
 * cannot be scooped up with a bucket, and it does not form infinite sources.
 */
abstract class BloodFluid : FlowableFluid() {
    override fun getFlowing(): Fluid = HollowsFluids.FLOWING_BLOOD
    override fun getStill(): Fluid = HollowsFluids.BLOOD
    override fun getBucketItem(): Item = Items.AIR
    override fun getBucketFillSound(): Optional<SoundEvent> = Optional.empty()
    override fun getParticle(): ParticleEffect = BloodParticles.DRIP
    override fun isInfinite(world: ServerWorld): Boolean = false

    override fun beforeBreakingBlock(world: WorldAccess, pos: BlockPos, state: BlockState) {
        val be: BlockEntity? = if (state.hasBlockEntity()) world.getBlockEntity(pos) else null
        Block.dropStacks(state, world, pos, be)
    }

    override fun onEntityCollision(world: World, pos: BlockPos, entity: Entity, handler: EntityCollisionHandler) {
        handler.addEvent(CollisionEvent.EXTINGUISH)
    }

    override fun getMaxFlowDistance(world: WorldView): Int = 4
    override fun getLevelDecreasePerBlock(world: WorldView): Int = 1
    override fun getTickRate(world: WorldView): Int = 6          // a little thicker than water
    override fun getBlastResistance(): Float = 100.0f

    override fun toBlockState(state: FluidState): BlockState =
        HollowsFluids.BLOOD_BLOCK.defaultState.with(FluidBlock.LEVEL, getBlockStateLevel(state))

    override fun matchesType(fluid: Fluid): Boolean = fluid === HollowsFluids.BLOOD || fluid === HollowsFluids.FLOWING_BLOOD

    override fun canBeReplacedWith(state: FluidState, world: BlockView, pos: BlockPos, fluid: Fluid, direction: Direction): Boolean =
        direction == Direction.DOWN && !fluid.isIn(FluidTags.WATER)

    class Flowing : BloodFluid() {
        override fun appendProperties(builder: StateManager.Builder<Fluid, FluidState>) {
            super.appendProperties(builder)
            builder.add(LEVEL)
        }
        override fun getLevel(state: FluidState): Int = state.get(LEVEL)
        override fun isStill(state: FluidState): Boolean = false
    }

    class Still : BloodFluid() {
        override fun getLevel(state: FluidState): Int = 8
        override fun isStill(state: FluidState): Boolean = true
    }
}

/** Fluid block that refuses buckets (and sponges/other drainers). */
class BloodFluidBlock(fluid: FlowableFluid, settings: AbstractBlock.Settings) : FluidBlock(fluid, settings) {
    override fun tryDrainFluid(drainer: LivingEntity?, world: WorldAccess, pos: BlockPos, state: BlockState): ItemStack = ItemStack.EMPTY
}

object HollowsFluids {
    @JvmField val BLOOD: FlowableFluid = Registry.register(Registries.FLUID, HhaBlood.id("blood"), BloodFluid.Still())
    @JvmField val FLOWING_BLOOD: FlowableFluid = Registry.register(Registries.FLUID, HhaBlood.id("flowing_blood"), BloodFluid.Flowing())

    @JvmField val BLOOD_BLOCK: Block = Blocks.register(
        RegistryKey.of(RegistryKeys.BLOCK, HhaBlood.id("blood")),
        { BloodFluidBlock(BLOOD, it) },
        AbstractBlock.Settings.create().mapColor(MapColor.DARK_RED).replaceable().noCollision().strength(100.0f)
            .pistonBehavior(PistonBehavior.DESTROY).dropsNothing().liquid().sounds(BlockSoundGroup.INTENTIONALLY_EMPTY),
    )

    /** Source block for world generation. */
    val SOURCE: BlockState get() = BLOOD_BLOCK.defaultState

    fun init() {
        // touching the object registers everything above
    }
}
