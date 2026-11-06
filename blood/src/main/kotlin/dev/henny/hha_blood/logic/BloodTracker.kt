package dev.henny.hha_blood.logic

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.UltraMode
import dev.henny.hha_blood.BloodItems
import dev.henny.hha_blood.net.HuntedPayload
import dev.henny.hha_blood.net.TrackerListPayload
import dev.henny.hha_blood.net.TrackerOpenPayload
import dev.henny.hha_blood.net.TrackerSelectPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.EquipmentSlot
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Blood Helmet "Blood Tracker" (active).
 *
 * Press the tracker key -> pick a player from a list -> for [tracker_duration] a pulsing vein of blood
 * that only you can see leads to that player, plus a crimson beacon above them and the distance /
 * direction in your action bar. Afterwards the helmet needs [tracker_cooldown] before the next hunt
 * (ending early still costs the full cooldown, counted from the moment it ends). The prey notices it:
 * a message, a heartbeat every few seconds and a red pulse at the screen edges - but not who it is.
 */
object BloodTracker {

    private class Session(val target: UUID, val targetName: String, val total: Int) {
        var ticksLeft = total
        var age = 0
    }

    private val sessions = HashMap<UUID, Session>()
    /** server tick at which the helmet is ready again */
    private val readyAt = HashMap<UUID, Long>()

    private const val VEIN_LENGTH = 22.0
    private const val BEACON_RANGE = 72.0

    fun registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(TrackerOpenPayload.ID) { _, ctx -> openFor(ctx.player()) }
        ServerPlayNetworking.registerGlobalReceiver(TrackerSelectPayload.ID) { p, ctx -> select(ctx.player(), p.target) }
    }

    fun reset() {
        sessions.clear()
        readyAt.clear()
    }

    private fun now(player: ServerPlayerEntity): Long = player.entityWorld.server?.ticks?.toLong() ?: 0L

    private fun hasHelmet(player: ServerPlayerEntity) = player.getEquippedStack(EquipmentSlot.HEAD).isOf(BloodItems.BLOOD_HELMET)

    private fun cooldownLeft(player: ServerPlayerEntity): Int =
        ((readyAt[player.uuid] ?: 0L) - now(player)).coerceAtLeast(0L).toInt()

    private fun range(): Double = HhaConfig.num("hha_blood.tracker_select_range")

    private fun inRange(player: ServerPlayerEntity, other: ServerPlayerEntity): Boolean {
        if (other.entityWorld !== player.entityWorld) return false
        val r = range()
        return r <= 0.0 || other.squaredDistanceTo(player) <= r * r
    }

    private fun isCandidate(player: ServerPlayerEntity, other: ServerPlayerEntity) =
        other !== player && other.isAlive && !other.isSpectator && inRange(player, other)

    // ------------------------------------------------------------------ open / select

    /** Opens the target selection (HHA ability "Ability 4" with the Blood helmet, see BloodAddon). */
    fun openFor(player: ServerPlayerEntity) {
        if (!HhaConfig.enabled("hha_blood.blood_tracker")) return
        if (!hasHelmet(player)) {
            BloodHud.show(player, "hha_blood.msg.tracker_need_helmet", BloodHud.DARK_RED)
            return
        }
        val server = player.entityWorld.server ?: return
        val candidates = server.playerManager.playerList
            .filter { isCandidate(player, it) }
            .sortedBy { it.squaredDistanceTo(player) }
            .take(64)
            .map { TrackerListPayload.Candidate(it.uuid, it.name.string, sqrt(it.squaredDistanceTo(player)).toInt(), "") }
        val s = sessions[player.uuid]
        ServerPlayNetworking.send(
            player,
            TrackerListPayload(
                candidates,
                s?.target, s?.targetName ?: "", s?.ticksLeft ?: 0,
                HhaConfig.num("hha_blood.tracker_duration").toInt(),
                if (s != null) 0 else cooldownLeft(player),
            ),
        )
        BloodFx.soundFor(player, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.2f)
    }

    private fun select(player: ServerPlayerEntity, targetId: UUID?) {
        if (!HhaConfig.enabled("hha_blood.blood_tracker") || !hasHelmet(player)) return
        if (targetId == null) {
            if (sessions.containsKey(player.uuid)) end(player, early = true, reason = "hha_blood.msg.tracker_stopped")
            return
        }
        if (sessions.containsKey(player.uuid)) return
        if (cooldownLeft(player) > 0) {
            BloodHud.show(player, "hha_blood.msg.tracker_cooldown", BloodHud.DARK_RED, (cooldownLeft(player) + 19) / 20)
            return
        }
        val target = player.entityWorld.server?.playerManager?.getPlayer(targetId)
        if (target == null || !isCandidate(player, target)) {
            BloodHud.show(player, "hha_blood.msg.tracker_lost", BloodHud.DARK_RED)
            return
        }

        val duration = HhaConfig.num("hha_blood.tracker_duration").toInt().coerceAtLeast(20)
        val cooldown = HhaConfig.num("hha_blood.tracker_cooldown").toInt().coerceAtLeast(0)
        sessions[player.uuid] = Session(target.uuid, target.name.string, duration)
        if (!UltraMode.isActive(player)) {
            readyAt[player.uuid] = now(player) + duration + cooldown
            player.itemCooldownManager.set(player.getEquippedStack(EquipmentSlot.HEAD), duration + cooldown)
        }

        BloodHud.show(player, "hha_blood.msg.tracker_start", BloodHud.RED, target.name, ticks = 60)
        BloodFx.soundFor(player, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.4f, 0.6f)
        BloodFx.soundFor(player, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 0.35f, 1.6f)

        // the prey feels it - but does not learn who it is
        BloodHud.show(target, "hha_blood.msg.hunted_start", BloodHud.DARK_RED, ticks = 80)
        BloodFx.soundFor(target, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.4f, 0.5f)
        BloodFx.soundFor(target, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 0.25f, 0.7f)
        sendHunted(target, true)
        val world = player.entityWorld as? ServerWorld ?: return
        // a ring of blood around the hunter, only they see it
        for (i in 0 until 24) {
            val a = i / 24.0 * Math.PI * 2
            BloodFx.spawnFor(player, world, BloodFx.dust(BloodFx.BRIGHT, 1.3f), Vec3d(player.x + cos(a) * 1.2, player.y + 0.1, player.z + sin(a) * 1.2))
        }
    }

    private fun sendHunted(target: ServerPlayerEntity, on: Boolean) {
        if (ServerPlayNetworking.canSend(target, HuntedPayload.ID)) ServerPlayNetworking.send(target, HuntedPayload(on))
    }

    /** Ends the hunt. Ending early (stop button, target lost, helmet off) still costs the full cooldown, counted from now. */
    private fun end(player: ServerPlayerEntity, early: Boolean, reason: String) {
        val session = sessions.remove(player.uuid) ?: return
        val server = player.entityWorld.server
        val target = server?.playerManager?.getPlayer(session.target)
        if (target != null && sessions.values.none { it.target == session.target }) {
            sendHunted(target, false)
            BloodHud.show(target, "hha_blood.msg.hunted_end", BloodHud.GRAY, ticks = 60)
        }
        if (early && !UltraMode.isActive(player)) {
            val cooldown = HhaConfig.num("hha_blood.tracker_cooldown").toInt().coerceAtLeast(0)
            readyAt[player.uuid] = now(player) + cooldown
            val helmet = player.getEquippedStack(EquipmentSlot.HEAD)
            if (helmet.isOf(BloodItems.BLOOD_HELMET)) player.itemCooldownManager.set(helmet, cooldown)
        }
        BloodHud.show(player, reason, BloodHud.DARK_RED, ticks = 60)
        BloodFx.soundFor(player, SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.5f, 0.6f)
    }

    // ------------------------------------------------------------------ tick

    fun tick(server: MinecraftServer) {
        if (sessions.isEmpty()) return
        for ((ownerId, s) in sessions.entries.toList()) {
            val owner = server.playerManager.getPlayer(ownerId)
            if (owner == null) {
                sessions.remove(ownerId)
                continue
            }
            if (!owner.isAlive || !hasHelmet(owner) || !HhaConfig.enabled("hha_blood.blood_tracker")) {
                end(owner, early = true, reason = "hha_blood.msg.tracker_stopped")
                continue
            }
            val target = server.playerManager.getPlayer(s.target)
            if (target == null) {
                end(owner, early = true, reason = "hha_blood.msg.tracker_lost")
                continue
            }
            s.ticksLeft--
            s.age++
            if (s.ticksLeft <= 0) {
                end(owner, early = false, reason = "hha_blood.msg.tracker_done")
                continue
            }
            // the prey: a slow heartbeat every few seconds, the HUD flag is refreshed (relogs)
            if (s.age % 80 == 0) BloodFx.soundFor(target, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.6f, 0.55f)
            if (s.age % 200 == 0) sendHunted(target, true)
            val world = owner.entityWorld as? ServerWorld ?: continue
            if (target.entityWorld !== owner.entityWorld) {
                if (s.age % 10 == 0) {
                    BloodHud.show(owner, "hha_blood.msg.tracker_other_dim", BloodHud.DARK_RED, target.name, (s.ticksLeft + 19) / 20, ticks = 25)
                }
                continue
            }
            if (s.age % 2 == 0) drawVein(world, owner, target, s.age)
            if (s.age % 10 == 0) actionBar(owner, target, s)
            val dist = sqrt(owner.squaredDistanceTo(target))
            val beat = (12 + dist / 4).toInt().coerceIn(12, 40)
            if (s.age % beat == 0) BloodFx.soundFor(owner, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.7f, (1.4 - (dist / 120).coerceAtMost(0.8)).toFloat())
        }
    }

    private fun actionBar(owner: ServerPlayerEntity, target: ServerPlayerEntity, s: Session) {
        val dx = target.x - owner.x
        val dz = target.z - owner.z
        val dist = sqrt(dx * dx + dz * dz + (target.y - owner.y).let { it * it }).toInt()
        // angle of the target relative to where the owner looks (0 = straight ahead)
        val toTarget = Math.toDegrees(atan2(-dx, dz))
        val rel = MathHelper.wrapDegrees(toTarget - owner.yaw)
        val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
        val arrow = arrows[(((rel + 360 + 22.5) % 360) / 45).toInt() % 8]
        val height = when {
            target.y - owner.y > 4 -> " ▲"
            owner.y - target.y > 4 -> " ▼"
            else -> ""
        }
        BloodHud.show(owner, "hha_blood.msg.tracker_bar", BloodHud.RED, arrow, target.name, dist, height, (s.ticksLeft + 19) / 20, ticks = 25)
    }

    /** Pulsing vein from the hunter towards the target + beacon above the target (only the hunter sees it). */
    private fun drawVein(world: ServerWorld, owner: ServerPlayerEntity, target: ServerPlayerEntity, age: Int) {
        val from = owner.entityPos.add(0.0, owner.standingEyeHeight * 0.75, 0.0)
        val to = target.entityPos.add(0.0, target.height * 0.5, 0.0)
        val delta = to.subtract(from)
        val dist = delta.length()
        if (dist < 0.5) return
        val dir = delta.normalize()
        val length = dist.coerceAtMost(VEIN_LENGTH)
        val side = dir.crossProduct(Vec3d(0.0, 1.0, 0.0)).let { if (it.lengthSquared() < 1e-4) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
        val steps = (length * 1.5).toInt().coerceIn(6, 34)
        val flow = (age % 20) / 20.0
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val wave = sin(t * 12.0 - age * 0.5)
            val fade = if (dist > VEIN_LENGTH) 1.0 - t * 0.5 else 1.0
            val p = from.add(dir.multiply(t * length)).add(side.multiply(wave * 0.08)).add(0.0, wave * 0.05, 0.0)
            val pulse = ((t - flow) % 1.0 + 1.0) % 1.0 < 0.14
            BloodFx.spawnFor(owner, world, BloodFx.dust(if (pulse) BloodFx.BRIGHT else BloodFx.DARK, ((if (pulse) 1.3 else 0.8) * fade).toFloat()), p)
            if (i % 6 == 0 && age % 6 == 0) BloodFx.spawnFor(owner, world, BloodFx.dropsFalling, p)
        }
        // arrow head at the end of a capped vein
        if (dist > VEIN_LENGTH) {
            val tip = from.add(dir.multiply(length))
            for (k in 1..3) {
                val back = tip.subtract(dir.multiply(k * 0.35))
                BloodFx.spawnFor(owner, world, BloodFx.dust(BloodFx.BRIGHT, 1.2f), back.add(side.multiply(k * 0.22)))
                BloodFx.spawnFor(owner, world, BloodFx.dust(BloodFx.BRIGHT, 1.2f), back.subtract(side.multiply(k * 0.22)))
            }
        }
        if (dist <= BEACON_RANGE) {
            for (i in 0 until 6) {
                BloodFx.spawnFor(owner, world, BloodFx.dust(BloodFx.BRIGHT, 1.5f), Vec3d(target.x, target.y + target.height + 0.3 + i * 0.45, target.z), 1, 0.04)
            }
            val a = age * 0.3
            for (k in 0 until 4) {
                val ang = a + k * Math.PI / 2
                BloodFx.spawnFor(owner, world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 1.2f), Vec3d(target.x + cos(ang) * 0.6, target.y + 0.15, target.z + sin(ang) * 0.6))
            }
        }
    }
}
