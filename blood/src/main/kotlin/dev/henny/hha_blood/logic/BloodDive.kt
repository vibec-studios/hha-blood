package dev.henny.hha_blood.logic

import dev.henny.hha_blood.mixin.MannequinEntityAccessor
import dev.henny.hha_blood.mixin.PlayerLikeEntityAccessor
import net.minecraft.block.ShapeContext
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.ProfileComponent
import net.minecraft.entity.EntityPose
import net.minecraft.entity.EntityType
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.decoration.MannequinEntity
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blood Trail "dive" (Mohg rising out of his blood pool - in reverse): a pool of blood opens under you,
 * a double of you (vanilla mannequin with your skin, armour and held items) crouches and sinks into it
 * while the real you is already truly invisible. When the double is gone the blood splashes up and the
 * pool slowly closes again.
 */
object BloodDive {

    private const val POOL_MODEL = "blood_pool"
    private const val POOL_SIZE = 3.4f          // diameter in blocks
    private const val OPEN_TICKS = 6
    private const val CROUCH_AT = 4
    private const val SINK_FROM = 7
    private const val SINK_TICKS = 16
    private const val SINK_DEPTH = 2.1
    private const val CLOSE_TICKS = 14

    private class Dive(
        val world: ServerWorld,
        val ground: Vec3d,
        val start: Vec3d,
        val double: MannequinEntity?,
        val pool: DisplayEntity.ItemDisplayEntity?,
        val total: Int,
    ) {
        var age = 0
        var splashed = false
    }

    private val dives = ArrayList<Dive>()

    /** Rising back out of the blood at the end of the invisibility (Mohg's entrance). */
    private class Emerge(val owner: java.util.UUID, val world: ServerWorld, val pool: DisplayEntity.ItemDisplayEntity?) {
        var age = 0
        var double: MannequinEntity? = null
        var ground: Vec3d = Vec3d.ZERO
    }

    private val pendingEmerge = HashMap<java.util.UUID, Int>()
    private val emerges = ArrayList<Emerge>()

    const val EMERGE_TICKS = 24
    private const val RISE_FROM = 5
    private const val RISE_TICKS = 15
    private const val STAND_AT = 18

    fun reset() {
        dives.forEach { FxDisplays.removeEntity(it.double); FxDisplays.remove(it.pool) }
        dives.clear()
        emerges.forEach { FxDisplays.removeEntity(it.double); FxDisplays.remove(it.pool) }
        emerges.clear()
        pendingEmerge.clear()
    }

    /**
     * [stayTicks]: how long the dive pool stays open. [invisTicks]: total invisibility - the rise out of
     * the blood starts [EMERGE_TICKS] before its end and ends it.
     */
    fun start(world: ServerWorld, player: ServerPlayerEntity, stayTicks: Int, invisTicks: Int) {
        val ground = groundBelow(world, player.entityPos) ?: player.entityPos
        val poolPos = ground.add(0.0, 0.03, 0.0)
        val pool = FxDisplays.spawn(world, poolPos, Items.PAPER, POOL_MODEL, Quaternionf(), 0.01f, teleportTicks = 1)
        val double = spawnDouble(world, player)
        dives += Dive(world, ground, player.entityPos, double, pool, maxOf(stayTicks, SINK_FROM + SINK_TICKS + 6))
        if (invisTicks > EMERGE_TICKS + SINK_FROM + SINK_TICKS) pendingEmerge[player.uuid] = invisTicks - EMERGE_TICKS

        BloodFx.sound(world, ground, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 1.0f, 0.5f)
        BloodFx.sound(world, ground, SoundEvents.BLOCK_HONEY_BLOCK_BREAK, 1.0f, 0.5f)
    }

    private fun startEmerge(player: ServerPlayerEntity) {
        val world = player.entityWorld as? ServerWorld ?: return
        val ground = groundBelow(world, player.entityPos) ?: player.entityPos
        val pool = FxDisplays.spawn(world, ground.add(0.0, 0.03, 0.0), Items.PAPER, POOL_MODEL, Quaternionf(), 0.01f, teleportTicks = 1)
        val e = Emerge(player.uuid, world, pool)
        e.ground = ground
        emerges += e
        BloodFx.sound(world, ground, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 1.2f, 0.45f)
        BloodFx.sound(world, ground, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.4f, 0.5f)
    }

    /** false = finished */
    private fun tickEmerge(e: Emerge, server: net.minecraft.server.MinecraftServer): Boolean {
        val player = server.playerManager.getPlayer(e.owner)
        val world = e.world
        e.age++
        val t = e.age
        // the player went visible some other way (milk, death, logout) -> just close the pool
        val alive = player != null && player.isAlive && TrueInvis.isActive(e.owner) && player.entityWorld === world
        if (alive) e.ground = groundBelow(world, player!!.entityPos) ?: player.entityPos

        // pool: opens fast, closes after the figure is out
        val open = (t / OPEN_TICKS.toFloat()).coerceAtMost(1f)
        val closing = ((t - EMERGE_TICKS).toFloat() / CLOSE_TICKS).coerceIn(0f, 1f)
        val size = POOL_SIZE * (1 - (1 - open) * (1 - open)) * (1 - closing)
        e.pool?.let { FxDisplays.update(it, e.ground.add(0.0, 0.03, 0.0), Quaternionf().rotationY(-t * 0.02f), size.coerceAtLeast(0.01f)) }
        if (t > EMERGE_TICKS + CLOSE_TICKS) return false
        if (t % 3 == 0 && closing < 1f) {
            val a = world.random.nextDouble() * PI * 2
            val r = world.random.nextDouble() * size * 0.42
            BloodFx.spawn(world, BloodFx.crumbs, e.ground.x + cos(a) * r, e.ground.y + 0.08, e.ground.z + sin(a) * r, 2, 0.05, 0.02, 0.05, 0.1)
        }
        if (!alive) {
            FxDisplays.removeEntity(e.double)
            e.double = null
            return t <= EMERGE_TICKS + CLOSE_TICKS
        }
        val p = player!!

        // the double rises: starts below the surface, decelerates while it comes up, crouched until the end
        if (t == RISE_FROM) {
            e.double = spawnDouble(world, p)?.also {
                it.pose = EntityPose.CROUCHING
                it.setPosition(p.x, e.ground.y - SINK_DEPTH, p.z)
            }
            // the surface bursts open
            BloodFx.column(world, e.ground, 2.6, 0.3, 16, BloodFx.dust(BloodFx.BRIGHT, 1.2f))
            BloodFx.spawn(world, BloodFx.crumbs, e.ground.add(0.0, 0.2, 0.0), 26, 0.3, 0.1, 0.3, 0.38)
            BloodFx.spawn(world, BloodFx.splat, e.ground.add(0.0, 0.5, 0.0), 4, 0.35, 0.0)
            BloodFx.sound(world, e.ground, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.9f, 0.55f)
            BloodFx.sound(world, e.ground, SoundEvents.BLOCK_HONEY_BLOCK_BREAK, 1.0f, 0.5f)
        }
        val m = e.double
        if (m != null) {
            val k = ((t - RISE_FROM) / RISE_TICKS.toDouble()).coerceIn(0.0, 1.0)
            val depth = SINK_DEPTH * (1 - k) * (1 - k)          // ease-out: fast at first, settles at the top
            m.setPosition(p.x, maxOf(e.ground.y, p.y) - depth, p.z)
            m.headYaw = p.headYaw
            m.bodyYaw = p.bodyYaw
            m.yaw = p.yaw
            if (t == STAND_AT) m.pose = EntityPose.STANDING
            // blood streaming off the body as it comes up
            if (t % 2 == 0) {
                BloodFx.spawn(world, BloodFx.dropsFalling, p.x, e.ground.y + 0.3 + (1 - depth / SINK_DEPTH) * 1.4, p.z, 3, 0.3, 0.3, 0.3, 0.0)
                BloodFx.ring(world, e.ground.add(0.0, 0.1, 0.0), 0.55, BloodFx.dust(BloodFx.BRIGHT, 0.8f), 14, t * 0.4)
            }
            if (t % 4 == 0) BloodFx.sound(world, e.ground, SoundEvents.BLOCK_MUD_STEP, 0.8f, 0.7f)
        }
        // out: swap the double for the real player in the same spot
        if (t == EMERGE_TICKS) {
            FxDisplays.removeEntity(e.double)
            e.double = null
            TrueInvis.stop(p)
            BloodFx.spawn(world, BloodFx.crumbs, p.entityPos.add(0.0, 1.0, 0.0), 12, 0.3, 0.5, 0.3, 0.15)
            BloodFx.sound(world, p.entityPos, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.7f)
        }
        return true
    }

    private fun spawnDouble(world: ServerWorld, player: ServerPlayerEntity): MannequinEntity? {
        val m = EntityType.MANNEQUIN.create(world, SpawnReason.TRIGGERED) ?: return null
        m.refreshPositionAndAngles(player.x, player.y, player.z, player.yaw, 0f)
        m.headYaw = player.headYaw
        m.bodyYaw = player.bodyYaw
        m.setComponent(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(player.gameProfile))
        (m as MannequinEntityAccessor).hhaBloodSetHideDescription(true)
        (m as MannequinEntityAccessor).hhaBloodSetImmovable(true)
        val key = PlayerLikeEntityAccessor.hhaBloodCustomizationKey()
        m.dataTracker.set(key, player.dataTracker.get(key))
        m.mainArm = player.mainArm
        for (slot in listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
            m.equipStack(slot, player.getEquippedStack(slot).copy())
        }
        m.setNoGravity(true)
        m.noClip = true
        m.isInvulnerable = true
        m.isSilent = true
        FxDisplays.track(m)
        return if (world.spawnEntity(m)) m else null
    }

    private fun groundBelow(world: ServerWorld, at: Vec3d): Vec3d? {
        val from = at.add(0.0, 0.5, 0.0)
        val hit = world.raycast(RaycastContext(from, from.add(0.0, -6.0, 0.0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, ShapeContext.absent()))
        return if (hit.type == HitResult.Type.BLOCK) hit.pos else null
    }

    fun tick(server: net.minecraft.server.MinecraftServer) {
        if (pendingEmerge.isNotEmpty()) {
            val pit = pendingEmerge.entries.iterator()
            while (pit.hasNext()) {
                val entry = pit.next()
                entry.setValue(entry.value - 1)
                if (entry.value > 0) continue
                pit.remove()
                val player = server.playerManager.getPlayer(entry.key) ?: continue
                if (player.isAlive && TrueInvis.isActive(player.uuid)) startEmerge(player)
            }
        }
        if (emerges.isNotEmpty()) {
            val eit = emerges.iterator()
            while (eit.hasNext()) {
                val e = eit.next()
                if (!tickEmerge(e, server)) {
                    FxDisplays.removeEntity(e.double)
                    FxDisplays.remove(e.pool)
                    eit.remove()
                }
            }
        }
        if (dives.isEmpty()) return
        val it = dives.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (!tickDive(d)) {
                FxDisplays.removeEntity(d.double)
                FxDisplays.remove(d.pool)
                it.remove()
            }
        }
    }

    private fun tickDive(d: Dive): Boolean {
        val world = d.world
        d.age++
        val t = d.age

        // ---- the pool: opens fast, ripples (animated texture) + slow turn, closes at the end
        val open = (t / OPEN_TICKS.toFloat()).coerceAtMost(1f)
        val closing = ((t - d.total).toFloat() / CLOSE_TICKS).coerceIn(0f, 1f)
        val size = POOL_SIZE * (1 - (1 - open) * (1 - open)) * (1 - closing)
        d.pool?.let { FxDisplays.update(it, d.ground.add(0.0, 0.03, 0.0), Quaternionf().rotationY(t * 0.02f), size.coerceAtLeast(0.01f)) }
        if (t > d.total + CLOSE_TICKS) return false

        // bubbling surface
        if (t % 3 == 0 && closing < 1f) {
            val a = world.random.nextDouble() * PI * 2
            val r = world.random.nextDouble() * size * 0.42
            BloodFx.spawn(world, BloodFx.crumbs, d.ground.x + cos(a) * r, d.ground.y + 0.08, d.ground.z + sin(a) * r, 2, 0.05, 0.02, 0.05, 0.08)
            if (t % 9 == 0) BloodFx.sound(world, d.ground, SoundEvents.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.7f, 0.5f)
        }

        // ---- the double: crouches, then sinks into the blood (accelerating)
        val m = d.double
        if (m != null && !d.splashed) {
            if (t == CROUCH_AT) m.pose = EntityPose.CROUCHING
            if (t >= SINK_FROM) {
                val k = ((t - SINK_FROM) / SINK_TICKS.toDouble()).coerceIn(0.0, 1.0)
                val depth = SINK_DEPTH * k * k
                m.setPosition(d.start.x, d.ground.y - depth, d.start.z)
                // blood climbing up the body + rings where the body breaks the surface
                if (t % 2 == 0) {
                    BloodFx.ring(world, d.ground.add(0.0, 0.1, 0.0), 0.5 + world.random.nextDouble() * 0.2, BloodFx.dust(BloodFx.BRIGHT, 0.8f), 14, t * 0.4)
                    BloodFx.spawn(world, BloodFx.dropsFalling, d.start.x, d.ground.y + 0.6 + world.random.nextDouble() * 1.2 * (1 - k), d.start.z, 2, 0.3, 0.1, 0.3, 0.0)
                }
                if (t % 4 == 0) BloodFx.sound(world, d.ground, SoundEvents.BLOCK_MUD_STEP, 0.8f, 0.6f)
            }
            if (t >= SINK_FROM + SINK_TICKS) {
                d.splashed = true
                FxDisplays.removeEntity(m)
                splash(world, d.ground)
            }
        }
        return true
    }

    /** The blood closes over the head: a column of blood shoots up and rains back into the pool. */
    private fun splash(world: ServerWorld, ground: Vec3d) {
        val c = ground.add(0.0, 0.15, 0.0)
        BloodFx.spawn(world, BloodFx.crumbs, c, 30, 0.25, 0.1, 0.25, 0.4)
        BloodFx.spawn(world, BloodFx.splat, c.add(0.0, 0.4, 0.0), 4, 0.3, 0.0)
        BloodFx.column(world, c, 2.2, 0.25, 14, BloodFx.dust(BloodFx.BRIGHT, 1.2f))
        BloodFx.ring(world, c, 1.2, BloodFx.dust(BloodFx.BLOOD, 1.2f), 24, 0.0, 0.15)
        BloodFx.spawn(world, ParticleTypes.SPLASH, c, 20, 0.4, 0.1)
        BloodFx.sound(world, ground, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.9f, 0.6f)
        BloodFx.sound(world, ground, SoundEvents.BLOCK_HONEY_BLOCK_BREAK, 1.0f, 0.5f)
        BloodFx.sound(world, ground, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.5f)
    }
}
