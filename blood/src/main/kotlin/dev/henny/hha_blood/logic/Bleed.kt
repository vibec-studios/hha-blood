package dev.henny.hha_blood.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha_blood.BloodEffects
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bleeding: a damage-over-time that (with the Blood Sword) also makes the victim take extra damage.
 *
 * Made to be impossible to miss:
 *  - a real status effect "Bleeding" (icon for the victim, falling blood drops as effect particles)
 *  - opening a wound: own "slash + spray" sound, a burst of blood and a ring of splatter
 *  - every second: an arterial spurt out of one side of the body + own "squirt + drop" sound,
 *    the damage tick, a heartbeat every 2 s
 *  - all the time: blood running down the body and dripping into puddles
 * Milk (removing the effect) also stops the bleeding.
 */
object Bleed {

    private class Entry(var ticksLeft: Int, var dps: Float, var source: UUID?, var tick: Int = 0)

    private val bleeding = HashMap<UUID, Entry>()

    @JvmOverloads
    fun apply(
        target: LivingEntity,
        durationTicks: Int = HhaConfig.num("hha_blood.bleed_duration").toInt(),
        damagePerSecond: Float = HhaConfig.num("hha_blood.bleed_dps").toFloat(),
        source: LivingEntity? = null,
    ) {
        if (!target.isAlive || durationTicks <= 0) return
        val existing = bleeding[target.uuid]
        val fresh = existing == null
        if (existing != null) {
            existing.ticksLeft = maxOf(existing.ticksLeft, durationTicks)
            existing.dps = maxOf(existing.dps, damagePerSecond)
            if (source != null) existing.source = source.uuid
        } else {
            bleeding[target.uuid] = Entry(durationTicks, damagePerSecond, source?.uuid)
        }
        val left = bleeding[target.uuid]!!.ticksLeft
        target.addStatusEffect(StatusEffectInstance(BloodEffects.BLEEDING, left, 0, false, true, true))

        val world = target.entityWorld as? ServerWorld ?: return
        val body = target.entityPos.add(0.0, target.height * 0.6, 0.0)
        BloodFx.wound(world, body, if (fresh) 3 else 2)
        BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.5f), body, 10, 0.3, 0.02)
        if (fresh) {
            // the wound OPENS: ring of splatter on the ground + own sound (refreshing stays quiet)
            val feet = target.entityPos.add(0.0, 0.1, 0.0)
            for (i in 0 until 14) {
                val a = i / 14.0 * PI * 2
                BloodFx.spawn(world, BloodFx.crumbs, feet.x, body.y, feet.z, 0, cos(a), 0.35, sin(a), 0.28)
            }
            BloodFx.sound(world, body, BloodSounds.BLEED_OPEN, 1.5f, 0.62f + world.random.nextFloat() * 0.08f)   // played low: Mats wants it deep
            BloodFx.sound(world, body, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.8f)
        }
    }

    @JvmStatic
    fun isBleeding(entity: LivingEntity): Boolean = bleeding.containsKey(entity.uuid)

    @JvmStatic
    fun damageMultiplier(entity: LivingEntity): Float {
        if (!isBleeding(entity) || !HhaConfig.enabled("hha_blood.hemorrhage")) return 1.0f
        return HhaConfig.num("hha_blood.hemorrhage_multiplier").toFloat().coerceAtLeast(1.0f)
    }

    fun clear(uuid: UUID) {
        bleeding.remove(uuid)
    }

    fun reset() = bleeding.clear()

    fun tick(server: MinecraftServer) {
        if (bleeding.isEmpty()) return
        val finished = ArrayList<UUID>()
        for ((uuid, entry) in bleeding) {
            entry.ticksLeft--
            entry.tick++
            val victim = findLiving(server, uuid)
            if (victim == null || entry.ticksLeft <= 0) {
                finished += uuid
                continue
            }
            // milk / totem / anything that removed the effect stops the bleeding too
            if (entry.tick > 2 && !victim.hasStatusEffect(BloodEffects.BLEEDING)) {
                finished += uuid
                continue
            }
            val world = victim.entityWorld as? ServerWorld ?: continue
            val w = (victim.width * 0.45).coerceAtLeast(0.18)

            // blood running down the body + drops falling off all the time
            if (entry.tick % 2 == 0) {
                val p = victim.entityPos.add(0.0, victim.height * (0.35 + world.random.nextDouble() * 0.45), 0.0)
                BloodFx.spawn(world, BloodFx.dropsFalling, p, 2, w)
                BloodFx.spawn(world, BloodFx.dust(BloodFx.BLOOD, 1.1f), p, 2, w * 0.8, 0.0)
            }
            if (entry.tick % 4 == 0) {
                BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 0.8f), victim.entityPos.add(0.0, victim.height * 0.6, 0.0), 3, w, 0.3, w, 0.0)
            }

            // every second: the damage tick with an arterial spurt out of one side of the body
            if (entry.tick % 20 == 0) {
                val p = victim.entityPos.add(0.0, victim.height * 0.55, 0.0)
                if (entry.dps > 0f) {
                    victim.timeUntilRegen = 0
                    victim.damage(world, world.damageSources.magic(), entry.dps)
                }
                val a = world.random.nextDouble() * PI * 2
                val side = p.add(cos(a) * w, 0.0, sin(a) * w)
                for (k in 0 until 9) {
                    val spread = (world.random.nextDouble() - 0.5) * 0.5
                    BloodFx.spawn(world, BloodFx.crumbs, side.x, side.y, side.z, 0, cos(a + spread), 0.45 + world.random.nextDouble() * 0.3, sin(a + spread), 0.32)
                }
                BloodFx.spawn(world, BloodFx.splat, side, 2, 0.1)
                BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.4f), side, 6, 0.15, 0.0)
                BloodFx.spawn(world, ParticleTypes.DAMAGE_INDICATOR, p, 1, 0.15)
                BloodFx.sound(world, p, BloodSounds.BLEED_PULSE, 1.2f, 0.5f + world.random.nextFloat() * 0.04f)   // 0.5 = lowest pitch Minecraft allows
                if (entry.tick % 40 == 0) BloodFx.sound(world, p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.6f, 0.9f)
            }
        }
        for (uuid in finished) {
            bleeding.remove(uuid)
            findLiving(server, uuid)?.let { if (it.hasStatusEffect(BloodEffects.BLEEDING)) it.removeStatusEffect(BloodEffects.BLEEDING) }
        }
    }

    private fun findLiving(server: MinecraftServer, uuid: UUID): LivingEntity? {
        for (world in server.worlds) {
            val e = world.getEntity(uuid)
            if (e is LivingEntity && e.isAlive) return e
        }
        return null
    }
}
