package dev.henny.hha_blood.logic

import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.EntityType
import net.minecraft.entity.decoration.Brightness
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.AffineTransformation
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Collections
import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Short-lived 3D visuals (flying saws, the spinning mace, blood bolts) as vanilla item display
 * entities with an own item model (`minecraft:item_model` component -> `assets/hha_blood/items/<name>.json`).
 * Works without any client code. Every display is tagged; a display that survives its ability
 * (chunk unloaded, server crash) is removed as soon as it is loaded again.
 */
object FxDisplays {
    private const val TAG = "hha_blood_fx"
    private val alive: MutableSet<UUID> = Collections.synchronizedSet(HashSet())

    fun init() {
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (TAG in entity.commandTags && entity.uuid !in alive) entity.discard()
        }
    }

    /** Marks any short-lived effect entity (e.g. the Blood Trail mannequin) for the leftover cleanup. */
    fun track(entity: net.minecraft.entity.Entity) {
        entity.addCommandTag(TAG)
        alive += entity.uuid
    }

    fun removeEntity(entity: net.minecraft.entity.Entity?) {
        if (entity == null) return
        alive -= entity.uuid
        entity.discard()
    }

    fun spawn(
        world: ServerWorld, pos: Vec3d, base: Item, model: String,
        rotation: Quaternionf, scale: Float, fullBright: Boolean = false, teleportTicks: Int = 1,
    ): DisplayEntity.ItemDisplayEntity? {
        val display = DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY, world)
        val stack = ItemStack(base)
        stack.set(DataComponentTypes.ITEM_MODEL, HhaBlood.id(model))
        display.setItemStack(stack)
        display.setBillboardMode(DisplayEntity.BillboardMode.FIXED)
        display.setViewRange(3.0f)
        display.setTeleportDuration(teleportTicks)
        if (fullBright) display.setBrightness(Brightness(15, 15))
        display.setPosition(pos.x, pos.y, pos.z)
        display.setTransformation(transform(rotation, scale))
        display.addCommandTag(TAG)
        alive += display.uuid
        return if (world.spawnEntity(display)) display else {
            alive -= display.uuid
            null
        }
    }

    /** Like [spawn], but shows [stack] with its own item model (e.g. an item of another mod). */
    fun spawnStack(
        world: ServerWorld, pos: Vec3d, stack: ItemStack, rotation: Quaternionf, scale: Float,
        fullBright: Boolean = false, teleportTicks: Int = 1,
    ): DisplayEntity.ItemDisplayEntity? {
        val display = DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY, world)
        display.setItemStack(stack.copy())
        display.setBillboardMode(DisplayEntity.BillboardMode.FIXED)
        display.setViewRange(3.0f)
        display.setTeleportDuration(teleportTicks)
        if (fullBright) display.setBrightness(Brightness(15, 15))
        display.setPosition(pos.x, pos.y, pos.z)
        display.setTransformation(transform(rotation, scale))
        display.addCommandTag(TAG)
        alive += display.uuid
        return if (world.spawnEntity(display)) display else {
            alive -= display.uuid
            null
        }
    }

    /** Moves + rotates a display smoothly over [ticks]. */
    fun update(display: DisplayEntity.ItemDisplayEntity, pos: Vec3d, rotation: Quaternionf, scale: Float, ticks: Int = 1) =
        update(display, pos, rotation, Vector3f(scale, scale, scale), ticks)

    /**
     * Animates ONLY the transformation (the entity itself stays where it is): [offset] moves the model in
     * world axes. Transformations are interpolated smoothly by the client between updates, entity
     * position updates are not in sync with them - so for real animations use this one.
     */
    fun animate(display: DisplayEntity.ItemDisplayEntity, offset: Vector3f, rotation: Quaternionf, scale: Vector3f, ticks: Int = 1) {
        display.setStartInterpolation(0)
        display.setInterpolationDuration(ticks)
        display.setTransformation(AffineTransformation(offset, rotation, scale, UNFLIP))
    }

    /** Culling box of a display (they are 0 x 0 by default and vanish when their origin leaves the screen). */
    fun cullBox(display: DisplayEntity.ItemDisplayEntity, width: Float, height: Float) {
        display.setDisplayWidth(width)
        display.setDisplayHeight(height)
    }

    /** Same with a per-axis scale (applied in model space, before the rotation). */
    fun update(display: DisplayEntity.ItemDisplayEntity, pos: Vec3d, rotation: Quaternionf, scale: Vector3f, ticks: Int = 1) {
        display.setPosition(pos.x, pos.y, pos.z)
        display.setStartInterpolation(0)
        display.setInterpolationDuration(ticks)
        display.setTransformation(AffineTransformation(Vector3f(), rotation, scale, UNFLIP))
    }

    fun remove(display: DisplayEntity.ItemDisplayEntity?) {
        if (display == null) return
        alive -= display.uuid
        display.discard()
    }

    /**
     * Minecraft's item display renderer turns every item 180 degrees around Y before drawing it. This
     * right-hand rotation cancels that, so model +Z really is the direction we rotate it to.
     */
    private val UNFLIP: Quaternionf get() = Quaternionf().rotationY(Math.PI.toFloat())

    private fun transform(rotation: Quaternionf, scale: Float) =
        AffineTransformation(Vector3f(), rotation, Vector3f(scale, scale, scale), UNFLIP)

    /** Rotation that turns the model's +Z axis into [dir]. */
    fun facing(dir: Vec3d): Quaternionf {
        val d = dir.normalize()
        val yaw = atan2(d.x, d.z).toFloat()
        val pitch = asin(d.y.coerceIn(-1.0, 1.0)).toFloat()
        return Quaternionf().rotationY(yaw).rotateX(-pitch)
    }
}
