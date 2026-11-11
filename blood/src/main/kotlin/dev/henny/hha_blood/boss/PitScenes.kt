package dev.henny.hha_blood.boss

import dev.henny.hha_blood.BloodItems
import dev.henny.hha_blood.BloodParticles
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.logic.BloodFx
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2

/**
 * The screenplay of the Slaughter Pit: every function is one cutscene that runs tick by tick (`wait` waits,
 * `say` shows a line and waits until it can be read). Each scene has an end state ([PitFight.SceneRun.finish])
 * that is also applied when the players skip it, so skipping never breaks the fight.
 * Texts: `boss.hha_blood.line.*` / `boss.hha_blood.title.*` in the lang files.
 */
class PitScenes(val world: ServerWorld, private val mino: MinotaurEntity, private val cerb: CerberusEntity) {

    private val players: List<ServerPlayerEntity> get() = PitFight.players(world)

    private fun rel(dx: Double, dy: Double, dz: Double): Vec3d = PitArena.mid().add(dx, dy, dz)

    /** Where the Minotaur watches stages 1 and 2: at the parapet of his balcony. */
    private val balconyFront: Vec3d get() = rel(0.0, HeartVaultPiece.BALCONY_DY.toDouble(), -34.6)
    private val cerberusStart: Vec3d get() = rel(0.5, 0.0, -18.0)
    /** The kennel lies under the balcony: the portcullis (gate plane dz = GATE_Z) and a point just inside it. */
    private val gateFront: Vec3d get() = rel(0.5, 3.0, HeartVaultPiece.GATE_Z + 0.5)
    private val gateInside: Vec3d get() = rel(0.5, 0.0, HeartVaultPiece.GATE_Z - 2.5)
    private val minotaurLanding: Vec3d get() = rel(0.0, 0.0, -22.0)

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    private suspend fun SequenceScope<Unit>.say(speaker: Int, key: String, extra: Int = 0) {
        val t = PitLines.ticks(key)
        Cine.dialog(players, speaker, PitLines.key(key), t)
        wait(t + 4 + extra)
    }

    /** Moves a boss smoothly to [to] (optional jump arc of [arc] blocks). */
    private suspend fun SequenceScope<Unit>.glide(boss: PitBoss, to: Vec3d, ticks: Int, arc: Double = 0.0, smooth: Boolean = true) {
        val from = boss.entityPos
        boss.setNoGravity(true)
        for (i in 1..ticks) {
            val raw = i.toFloat() / ticks
            val t = if (smooth) raw * raw * (3f - 2f * raw) else raw
            val p = from.lerp(to, t.toDouble()).add(0.0, arc * 4 * raw * (1 - raw), 0.0)
            boss.setPosition(p)
            boss.velocity = Vec3d.ZERO
            yield(Unit)
        }
        boss.setNoGravity(false)
    }

    /** Turns a boss by at most [step] degrees towards [yaw] (body and head together). */
    private fun turn(boss: PitBoss, yaw: Float, step: Float) {
        val y = net.minecraft.util.math.MathHelper.stepUnwrappedAngleTowards(boss.yaw, yaw, step)
        boss.yaw = y; boss.bodyYaw = y; boss.headYaw = y
    }

    /** Turns a boss on the spot towards [yaw] over [ticks] ticks (the client animates the steps). */
    private suspend fun SequenceScope<Unit>.turnTo(boss: PitBoss, yaw: Float, ticks: Int, step: Float = 9f) {
        repeat(ticks) { turn(boss, yaw, step); yield(Unit) }
    }

    private fun catmull(p0: Vec3d, p1: Vec3d, p2: Vec3d, p3: Vec3d, u: Double): Vec3d {
        val u2 = u * u
        val u3 = u2 * u
        fun c(a: Double, b: Double, c: Double, d: Double) = 0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3)
        return Vec3d(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z))
    }

    /** Distance profile 0..1 that speeds up over the first [r] of the time, keeps the pace and slows down over the last [r]. */
    private fun trapezoid(x: Double, r: Double): Double {
        val v = 1.0 / (1.0 - r)
        return when {
            x < r -> 0.5 * v * x * x / r
            x > 1 - r -> 1.0 - 0.5 * v * (1 - x) * (1 - x) / r
            else -> 0.5 * v * r + v * (x - r)
        }
    }

    /**
     * Walks a boss along a smooth curve through [points] in [ticks] ticks: it speeds up and slows down, and turns into
     * the direction it walks (at most [turnStep] degrees a tick) instead of snapping round at the corners.
     */
    private suspend fun SequenceScope<Unit>.prowl(boss: PitBoss, points: List<Vec3d>, ticks: Int, turnStep: Float = 10f) {
        val ctrl = listOf(boss.entityPos) + points
        val samples = ArrayList<Vec3d>()
        for (seg in 0 until ctrl.size - 1) {
            val p0 = ctrl[maxOf(seg - 1, 0)]
            val p3 = ctrl[minOf(seg + 2, ctrl.size - 1)]
            for (k in 0 until 24) samples += catmull(p0, ctrl[seg], ctrl[seg + 1], p3, k / 24.0)
        }
        samples += ctrl.last()
        val acc = DoubleArray(samples.size)
        for (i in 1 until samples.size) acc[i] = acc[i - 1] + samples[i].distanceTo(samples[i - 1])
        val total = acc.last()
        boss.setNoGravity(true)
        var j = 0
        for (i in 1..ticks) {
            val s = total * trapezoid(i.toDouble() / ticks, 0.2)
            while (j < samples.size - 2 && acc[j + 1] < s) j++
            val len = (acc[j + 1] - acc[j]).coerceAtLeast(1e-6)
            val p = samples[j].lerp(samples[j + 1], ((s - acc[j]) / len).coerceIn(0.0, 1.0))
            val ahead = samples[minOf(j + 4, samples.size - 1)]
            if (ahead.squaredDistanceTo(p) > 0.04) turn(boss, yawTo(p, ahead), turnStep)
            boss.setPosition(p)
            boss.velocity = Vec3d.ZERO
            yield(Unit)
        }
        boss.setNoGravity(false)
    }

    private fun sound(event: SoundEvent, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) =
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)

    private fun yawTo(from: Vec3d, to: Vec3d): Float = (Math.toDegrees(atan2(to.z - from.z, to.x - from.x)) - 90.0).toFloat()

    private fun place(boss: PitBoss, at: Vec3d, yaw: Float) {
        boss.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f)
        boss.headYaw = yaw
        boss.bodyYaw = yaw
        boss.velocity = Vec3d.ZERO
        boss.setNoGravity(false)
    }

    private fun angleDeg(from: Vec3d, to: Vec3d): Double = Math.toDegrees(atan2(to.z - from.z, to.x - from.x))

    private fun wearsBlood(p: PlayerEntity): Boolean =
        p.getEquippedStack(EquipmentSlot.CHEST).isOf(BloodItems.BLOOD_CHESTPLATE) ||
            p.getEquippedStack(EquipmentSlot.HEAD).isOf(BloodItems.BLOOD_HELMET) ||
            p.getEquippedStack(EquipmentSlot.LEGS).isOf(BloodItems.BLOOD_LEGGINGS)

    // ------------------------------------------------------------------------------------- HHA cameo

    /** An item of the HHA base mod (null if this HHA version does not have it). */
    private fun hhaItem(path: String): net.minecraft.item.Item? {
        val id = net.minecraft.util.Identifier.of("hha", path)
        return if (net.minecraft.registry.Registries.ITEM.containsId(id)) net.minecraft.registry.Registries.ITEM.get(id) else null
    }

    /** A sound of the HHA base mod (clients without it simply stay silent). */
    private fun hhaSound(path: String): SoundEvent = SoundEvent.of(net.minecraft.util.Identifier.of("hha", path))

    /** Has this player met HHA's Hermes (his feather) or does he wear Heaven armour? */
    private fun knowsHermes(p: PlayerEntity): Boolean {
        val feather = hhaItem("hermes_feather")
        if (feather != null && p.inventory.contains { it.isOf(feather) }) return true
        return EquipmentSlot.entries.any { slot ->
            val id = net.minecraft.registry.Registries.ITEM.getId(p.getEquippedStack(slot).item)
            id.namespace == "hha" && id.path.startsWith("heaven_")
        }
    }

    private var feather: net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity? = null
    private var letter: net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity? = null

    private fun removeCameo() {
        dev.henny.hha_blood.logic.FxDisplays.removeEntity(feather)
        dev.henny.hha_blood.logic.FxDisplays.removeEntity(letter)
        feather = null
        letter = null
    }

    fun cleanup() { removeCameo() }

    // ---------------------------------------------------------------------------------------------
    // Intro: the pit, the Minotaur on his throne, "CERBERUS!", the portcullis rises
    // ---------------------------------------------------------------------------------------------

    fun intro(again: Boolean): PitFight.SceneRun {
        val steps = sequence {
            val everyone = players
            Cine.fade(everyone, 0x000000, 1.0, 0.0, 30)
            Cine.music(everyone, BossSounds.MUSIC_TENSION)
            PitFight.gather(world, rel(0.0, 0.0, -6.0), 30.0, 90.0)
            mino.act(MinotaurEntity.THRONE)
            cerb.act(CerberusEntity.SLEEP)

            if (!again) {
                // 1: from the crystal oculus down into the pit, towards the throne
                Cine.camera(everyone, rel(0.5, 38.0, 8.0), rel(0.5, 15.0, 16.0), rel(0.5, 0.0, -4.0), rel(0.5, 11.0, -32.0), 190, 66.0, 56.0)
                wait(20)
                say(Cine.SPEAKER_NARRATOR, "intro.1")
                wait(6)
            }
            // 2: push in towards the balcony, he rises from the throne
            Cine.camera(everyone, rel(0.0, 3.5, -6.0), rel(0.0, 9.0, -21.0), Vec3d.ZERO, Vec3d.ZERO, 130, 62.0, 48.0, mino, 2.8)
            wait(18)
            mino.act(MinotaurEntity.STAND)
            sound(BossSounds.MINOTAUR_SNORT, mino.entityPos, 3f, 0.8f)
            wait(20)
            // up on his hooves: the balcony creaks under him
            sound(BossSounds.MINOTAUR_STEP, mino.entityPos, 2.5f, 0.7f)
            BloodFx.shake(world, mino.entityPos, 40.0, 0.4f, 8)
            wait(12)
            // he walks (the steps come from the motion) to the parapet
            mino.act(MinotaurEntity.IDLE)
            glide(mino, balconyFront, 34)
            sound(BossSounds.MINOTAUR_STEP, mino.entityPos, 2.2f, 0.8f)
            mino.yaw = 0f; mino.headYaw = 0f; mino.bodyYaw = 0f
            if (again) {
                say(Cine.SPEAKER_MINOTAUR, "intro.again")
            } else {
                // 3: close-up
                Cine.camera(everyone, rel(-4.6, 14.2, -29.0), rel(-3.6, 14.6, -30.2), Vec3d.ZERO, Vec3d.ZERO, 260, 44.0, 38.0, mino, 3.3)
                say(Cine.SPEAKER_MINOTAUR, "intro.2")
                say(Cine.SPEAKER_MINOTAUR, when {
                    everyone.any(::wearsBlood) -> "intro.3b"
                    everyone.any(::knowsHermes) -> "intro.3h"
                    else -> "intro.3"
                })
                say(Cine.SPEAKER_MINOTAUR, "intro.lore")
                // 4: from below, he roars for his hound
                Cine.camera(everyone, rel(2.5, 1.4, -23.0), rel(1.5, 1.2, -25.5), Vec3d.ZERO, Vec3d.ZERO, 120, 56.0, 66.0, mino, 3.0)
                mino.act(MinotaurEntity.ROAR)
                sound(BossSounds.MINOTAUR_ROAR, mino.entityPos, 4f, 0.9f)
                BloodFx.shake(world, mino.entityPos, 80.0, 1.4f, 24)
                say(Cine.SPEAKER_MINOTAUR, "intro.4")
            }
            // 5: the portcullis
            // the gate under his balcony - he stands right above it
            Cine.camera(everyone, rel(-9.0, 2.6, -15.0), rel(-6.0, 2.3, -18.5), rel(0.5, 6.0, -33.0), rel(0.5, 5.0, -33.0), 120, 58.0, 52.0)
            sound(BossSounds.GATE_OPEN, gateFront, 4f, 1f)
            sound(BossSounds.CHAINS, gateFront.add(0.0, 2.0, 0.0), 3f, 0.9f)
            for (row in 0..HeartVaultPiece.GATE_TOP) {
                PitArena.openGateStep(world, row)
                // every row of the portcullis rumbles through the floor
                BloodFx.shake(world, gateFront, 36.0, 0.35f, 6)
                if (row == 2) cerb.act(CerberusEntity.WAKE)
                wait(5)
            }
            sound(BossSounds.CERBERUS_GROWL, cerb.entityPos, 4f, 0.8f)
            wait(16)
            // 6: the Cerberus walks out of its kennel
            Cine.camera(everyone, rel(10.0, 2.0, -15.0), rel(12.0, 2.2, -12.0), Vec3d.ZERO, Vec3d.ZERO, 80, 60.0, 56.0, cerb, 1.6)
            cerb.yaw = 0f; cerb.bodyYaw = 0f; cerb.headYaw = 0f
            // out through the gate and round the altar in front of it: one smooth prowl instead of three straight
            // pieces with a snap turn at each corner
            val pastAltar = rel(4.5, 0.0, -27.0)
            prowl(cerb, listOf(gateInside, pastAltar, cerberusStart), 66)
            // it turns to face the pit (small steps on the spot)
            turnTo(cerb, 0f, 6, 12f)
            // 7: three heads, one roar - title card
            Cine.orbit(everyone, cerb.entityPos, 7.5, 1.6, 55.0, 115.0, 110, 60.0, 52.0, cerb, 1.7, 6.0, 1.9)
            cerb.act(CerberusEntity.GROWL)
            sound(BossSounds.CERBERUS_BARK, cerb.entityPos, 4f, 0.8f)
            wait(6)
            sound(BossSounds.CERBERUS_BARK, cerb.entityPos, 4f, 0.95f)
            wait(6)
            sound(BossSounds.CERBERUS_BARK, cerb.entityPos, 4f, 0.7f)
            BloodFx.shake(world, cerb.entityPos, 60.0, 1.0f, 16)
            wait(14)
            Cine.title(everyone, "boss.hha_blood.title.cerberus", "boss.hha_blood.title.cerberus.sub", 80)
            sound(BossSounds.STINGER, cerb.entityPos, 3f, 1f)
            wait(84)
        }
        return PitFight.SceneRun("intro", steps.iterator(), true) {
            PitArena.openGate(world)
            place(mino, balconyFront, 0f)
            mino.act(MinotaurEntity.IDLE)
            mino.scripted = true          // he watches from up there
            place(cerb, cerberusStart, 0f)
            cerb.act(CerberusEntity.IDLE)
            cerb.scripted = false
            PitFight.beginStage(world, PitFight.CERBERUS)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stage 1 -> 2: the Cerberus falls, its master makes it burn
    // ---------------------------------------------------------------------------------------------

    fun cerberusRises(): PitFight.SceneRun {
        val steps = sequence {
            val everyone = players
            val at = cerb.entityPos
            val toMid = angleDeg(at, PitArena.mid())
            PitFight.gather(world, at, 13.0, toMid)
            Cine.music(everyone, BossSounds.MUSIC_TENSION)
            cerb.act(CerberusEntity.COLLAPSE)
            sound(BossSounds.CERBERUS_WHIMPER, at, 3f, 1f)
            Cine.orbit(everyone, at, 7.5, 1.4, toMid - 40.0, toMid + 10.0, 100, 58.0, 50.0, cerb, 0.8, 6.0, 1.2)
            say(Cine.SPEAKER_NARRATOR, "rise.1")
            Cine.camera(everyone, rel(-6.0, 3.0, -20.0), rel(-3.0, 6.0, -24.0), Vec3d.ZERO, Vec3d.ZERO, 160, 60.0, 48.0, mino, 3.0)
            mino.act(MinotaurEntity.ROAR)
            sound(BossSounds.MINOTAUR_ROAR, mino.entityPos, 4f, 1f)
            say(Cine.SPEAKER_MINOTAUR, "rise.2")
            say(Cine.SPEAKER_MINOTAUR, "rise.2b")
            mino.act(MinotaurEntity.POINT)
            say(Cine.SPEAKER_MINOTAUR, "rise.3")
            // the blood of the pit flows into the hound, its veins catch fire
            Cine.orbit(everyone, at, 9.0, 2.6, toMid + 20.0, toMid + 80.0, 120, 64.0, 74.0, cerb, 1.2)
            for (i in 0 until 34) {
                for (k in 0 until 4) {
                    val a = (k * 90.0 + i * 8.0) * Math.PI / 180
                    val r = 7.0 - i * 0.18
                    world.spawnParticles(BloodParticles.GLOB, at.x + Math.cos(a) * r, at.y + 0.2, at.z + Math.sin(a) * r, 2, 0.1, 0.05, 0.1, 0.02)
                }
                if (i % 8 == 0) sound(BossSounds.HEARTBEAT, at, 2.5f, 0.8f + i * 0.01f)
                yield(Unit)
            }
            cerb.unleashed = true
            cerb.act(CerberusEntity.RISE)
            sound(BossSounds.FIRE_BURST, at, 4f, 0.8f)
            sound(BossSounds.CERBERUS_HOWL, at, 4f, 0.9f)
            BloodFx.screenFlash(world, at, 80.0, 0xFF5A14, 0.45f, 14)
            BloodFx.shake(world, at, 80.0, 1.6f, 22)
            world.spawnParticles(BloodParticles.FLAME, at.x, at.y + 1.5, at.z, 120, 1.6, 1.0, 1.6, 0.15)
            world.spawnParticles(ParticleTypes.LAVA, at.x, at.y + 1.0, at.z, 30, 1.5, 0.5, 1.5, 0.0)
            wait(36)
            Cine.title(everyone, "boss.hha_blood.title.unleashed", "boss.hha_blood.title.unleashed.sub", 80, 0xFF6A2A)
            sound(BossSounds.STINGER, at, 3f, 1.1f)
            wait(10)
            // the front paws come down (RISE animation tick 46)
            sound(BossSounds.IMPACT_HEAVY, at, 3.5f, 1.0f)
            BloodFx.shake(world, at, 60.0, 1.2f, 12)
            world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, 24, 2.0, 0.1, 2.0, 0.04)
            wait(70)
        }
        return PitFight.SceneRun("cerberus_rises", steps.iterator(), true) {
            cerb.unleashed = true
            cerb.act(CerberusEntity.IDLE)
            cerb.scripted = false
            mino.act(MinotaurEntity.IDLE)
            mino.scripted = true
            PitFight.beginStage(world, PitFight.UNLEASHED)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stage 2 -> 3: the Cerberus dies, the Minotaur jumps into the pit
    // ---------------------------------------------------------------------------------------------

    fun cerberusFalls(): PitFight.SceneRun {
        val steps = sequence {
            val everyone = players
            val at = cerb.entityPos
            val toMid = angleDeg(at, PitArena.mid())
            PitFight.gather(world, at, 13.0, toMid)
            Cine.music(everyone, null)
            cerb.act(CerberusEntity.DEATH)
            sound(BossSounds.CERBERUS_DEATH, at, 4f, 1f)
            Cine.orbit(everyone, at, 8.0, 1.1, toMid - 25.0, toMid + 15.0, 120, 56.0, 46.0, cerb, 0.9, 5.0, 0.9)
            say(Cine.SPEAKER_NARRATOR, "fall.1")
            // it dissolves into blood
            for (i in 0 until 30) {
                world.spawnParticles(BloodParticles.GLOB, at.x, at.y + 1.0, at.z, 6, 1.4, 0.6, 1.4, 0.05)
                world.spawnParticles(BloodParticles.FLAME, at.x, at.y + 1.0, at.z, 3, 1.4, 0.6, 1.4, 0.02)
                yield(Unit)
            }
            cerb.discard()
            say(Cine.SPEAKER_NARRATOR, "fall.1b")
            Cine.music(everyone, BossSounds.MUSIC_TENSION)
            Cine.camera(everyone, rel(2.0, 2.0, -23.0), rel(1.0, 3.6, -26.5), Vec3d.ZERO, Vec3d.ZERO, 260, 60.0, 50.0, mino, 3.2)
            mino.act(MinotaurEntity.KNEEL)
            say(Cine.SPEAKER_MINOTAUR, "fall.2", 10)
            say(Cine.SPEAKER_MINOTAUR, "fall.3")
            mino.act(MinotaurEntity.ROAR)
            sound(BossSounds.MINOTAUR_ROAR, mino.entityPos, 5f, 0.85f)
            BloodFx.shake(world, mino.entityPos, 90.0, 1.8f, 30)
            say(Cine.SPEAKER_MINOTAUR, "fall.4")
            // the jump into the pit
            Cine.camera(everyone, rel(15.0, 3.0, -8.0), rel(13.0, 2.4, -11.0), Vec3d.ZERO, Vec3d.ZERO, 70, 66.0, 60.0, mino, 2.4)
            mino.act(MinotaurEntity.JUMP_DOWN)
            sound(BossSounds.MINOTAUR_GRUNT, mino.entityPos, 3f, 0.7f)
            wait(8)
            glide(mino, minotaurLanding, 24, arc = 5.0, smooth = false)
            // JUMP_DOWN keeps going: the axe slams into the floor as he lands, he stays down a moment
            val land = mino.entityPos
            sound(BossSounds.IMPACT_HEAVY, land, 5f, 0.7f)
            sound(BossSounds.AXE_IMPACT, land, 4f, 0.7f)
            BloodFx.shake(world, land, 100.0, 2.6f, 30)
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, land.x, land.y + 0.3, land.z, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticles(ParticleTypes.CLOUD, land.x, land.y + 0.3, land.z, 40, 3.0, 0.2, 3.0, 0.05)
            PitHazards.shockRing(mino, land, 16.0, 0.8, 0f, false)
            wait(12)
            mino.act(MinotaurEntity.ROAR)
            sound(BossSounds.MINOTAUR_ROAR, land, 5f, 0.9f)
            BloodFx.shake(world, land, 60.0, 1.0f, 18)
            wait(12)
            Cine.orbit(everyone, land, 8.0, 2.4, 60.0, 110.0, 120, 58.0, 50.0, mino, 3.0)
            wait(10)
            Cine.title(everyone, "boss.hha_blood.title.minotaur", "boss.hha_blood.title.minotaur.sub", 90)
            sound(BossSounds.STINGER, land, 3f, 0.9f)
            wait(96)
        }
        return PitFight.SceneRun("cerberus_falls", steps.iterator(), true) {
            if (!cerb.isRemoved) cerb.discard()
            if (mino.squaredDistanceTo(minotaurLanding) > 4.0) place(mino, minotaurLanding, 180f)
            mino.setNoGravity(false)
            mino.act(MinotaurEntity.IDLE)
            mino.scripted = false
            PitFight.beginStage(world, PitFight.MINOTAUR)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stage 3 -> 4: the labyrinth awakens
    // ---------------------------------------------------------------------------------------------

    private var wallsRaised = false
    private var placedInMaze = false

    /** He goes to the lair in the middle of the maze, the players are scattered into its far corridors. */
    private fun placeInMaze() {
        placedInMaze = true
        val mid = PitArena.mid()
        place(mino, mid, mino.yaw)
        val list = players
        val spots = PitLabyrinth.spreadSpots(mid, list.size, 18.0, 10.0, world.random)
        for ((i, p) in list.withIndex()) {
            val s = spots.getOrNull(i) ?: continue
            p.teleport(world, s.x, s.y, s.z, emptySet(), yawTo(s, mid), 0f, false)
            p.velocity = Vec3d.ZERO
            p.velocityDirty = true
            p.fallDistance = 0.0
        }
    }

    fun labyrinth(): PitFight.SceneRun {
        wallsRaised = false
        placedInMaze = false
        val steps = sequence {
            val everyone = players
            val at = mino.entityPos
            val toMid = angleDeg(at, PitArena.mid())
            PitFight.gather(world, at, 12.0, toMid)
            Cine.music(everyone, BossSounds.MUSIC_TENSION)
            mino.act(MinotaurEntity.ROAR)
            sound(BossSounds.MINOTAUR_ROAR, at, 4f, 1f)
            Cine.orbit(everyone, at, 7.0, 2.6, toMid - 30.0, toMid + 10.0, 90, 60.0, 54.0, mino, 3.0)
            say(Cine.SPEAKER_MINOTAUR, "laby.1")
            mino.act(MinotaurEntity.LABYRINTH)
            wait(18)
            sound(BossSounds.AXE_IMPACT, at, 4f, 0.6f)
            BloodFx.shake(world, at, 100.0, 2.0f, 30)
            BloodFx.screenFlash(world, at, 80.0, 0x300000, 0.4f, 8)
            // black: when it lifts, the maze is growing out of the floor and everyone is somewhere inside
            Cine.fade(everyone, 0x000000, 0.0, 1.0, 10)
            wait(12)
            PitLabyrinth.raise(world, false)
            wallsRaised = true
            placeInMaze()
            mino.act(MinotaurEntity.ROAR)
            Cine.fade(everyone, 0x000000, 1.0, 0.0, 16)
            // from high above: wave after wave of walls, from his lair outwards
            Cine.orbit(everyone, rel(0.0, 0.0, 0.0), 24.0, 30.0, toMid + 160.0, toMid + 240.0, 200, 70.0, 62.0, null, 0.0, radiusTo = 14.0, heightTo = 36.0)
            wait(16)
            say(Cine.SPEAKER_MINOTAUR, "laby.2")
            say(Cine.SPEAKER_MINOTAUR, "laby.3")
            // the blood of the pit rises around the players
            val p0 = players.firstOrNull()
            if (p0 != null) {
                val a = p0.yaw.toDouble() + 90.0
                Cine.orbit(everyone, p0.entityPos, 3.6, 1.9, a - 40.0, a + 30.0, 130, 62.0, 52.0, p0, 1.1)
            }
            for (i in 0 until 40) {
                for (p in players) {
                    val r = 1.4 - i * 0.02
                    val ang = i * 0.5
                    world.spawnParticles(BloodFx.dust(BloodFx.BRIGHT, 1.2f), p.x + kotlin.math.cos(ang) * r, p.y + 0.1 + i * 0.03, p.z + kotlin.math.sin(ang) * r, 2, 0.05, 0.05, 0.05, 0.0)
                    if (i % 4 == 0) world.spawnParticles(BloodParticles.DRIP_FALL, p.x, p.y + 2.2, p.z, 3, 0.4, 0.1, 0.4, 0.0)
                }
                if (i % 10 == 0) for (p in players) sound(net.minecraft.sound.SoundEvents.ENTITY_WARDEN_HEARTBEAT, p.entityPos, 1.2f, 0.5f)
                yield(Unit)
            }
            say(Cine.SPEAKER_NARRATOR, "laby.4")
            Cine.title(everyone, "boss.hha_blood.title.labyrinth", "boss.hha_blood.title.labyrinth.sub", 80)
            sound(BossSounds.STINGER, at, 3f, 1.0f)
            wait(80)
        }
        return PitFight.SceneRun("labyrinth", steps.iterator(), true) {
            if (!wallsRaised) PitLabyrinth.raise(world, true)
            if (!placedInMaze) placeInMaze()
            mino.act(MinotaurEntity.IDLE)
            mino.scripted = false
            PitFight.beginStage(world, PitFight.LABYRINTH)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stage 4 -> 5: he takes the Cerberus' hellfire - and goes berserk
    // ---------------------------------------------------------------------------------------------

    fun lastBull(): PitFight.SceneRun {
        var gathered = false
        val steps = sequence {
            val everyone = players
            // He may be airborne or beside the stands. Stage the toss in the lair under a brief black fade.
            Cine.fade(everyone, 0x000000, 0.0, 1.0, 8)
            wait(10)
            val at = PitArena.mid()
            place(mino, at, -90f)
            PitArena.smashWalls(world, at, 11.0)
            Cine.fade(everyone, 0x000000, 1.0, 0.0, 10)
            val toMid = angleDeg(at, PitArena.mid())
            Cine.music(everyone, BossSounds.MUSIC_TENSION)
            mino.act(MinotaurEntity.KNEEL)
            sound(BossSounds.MINOTAUR_HURT, at, 3f, 0.8f)
            // from above the walls: he kneels in his own maze
            Cine.orbit(everyone, at, 8.0, 7.5, toMid - 20.0, toMid + 30.0, 130, 60.0, 50.0, mino, 2.4)
            say(Cine.SPEAKER_MINOTAUR, "last.1")
            // He deliberately throws the axe away before becoming a bare-handed brawler.
            Cine.orbit(everyone, at, 8.5, 3.0, toMid - 40.0, toMid + 20.0, 55, 62.0, 58.0, mino, 2.6)
            mino.act(MinotaurEntity.DISCARD)
            wait(14)
            sound(BossSounds.AXE_SWING, at, 3f, 0.7f)
            PitHazards.discardAxe(mino)
            wait(30)
            // three spirit flames come out of the kennel
            val gate = gateFront
            sound(BossSounds.CERBERUS_HOWL, gate, 3f, 1.4f)
            mino.act(MinotaurEntity.ABSORB)
            for (i in 0 until 56) {
                val t = i / 56.0
                for (k in 0..2) {
                    val side = (k - 1) * 3.0
                    val from = gate.add(side, 0.0, 0.0)
                    val to = at.add(0.0, 2.6, 0.0)
                    val p = from.lerp(to, t).add(0.0, Math.sin(t * Math.PI) * 9.0, 0.0)
                    world.spawnParticles(BloodParticles.FLAME, p.x, p.y, p.z, 6, 0.25, 0.25, 0.25, 0.01)
                    world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 1, 0.2, 0.2, 0.2, 0.0)
                }
                yield(Unit)
            }
            mino.burning = true
            sound(BossSounds.FIRE_BURST, at, 4f, 0.7f)
            BloodFx.screenFlash(world, at, 80.0, 0xFF7A20, 0.5f, 14)
            BloodFx.shake(world, at, 80.0, 2.0f, 24)
            world.spawnParticles(BloodParticles.FLAME, at.x, at.y + 2.0, at.z, 140, 1.4, 1.6, 1.4, 0.14)
            say(Cine.SPEAKER_NARRATOR, "last.2")
            // close on his face: something breaks in him
            val front = mino.forward()
            Cine.camera(everyone, at.add(front.multiply(5.5)).add(0.0, 2.6, 0.0), at.add(front.multiply(3.8)).add(0.0, 3.2, 0.0),
                Vec3d.ZERO, Vec3d.ZERO, 120, 55.0, 42.0, mino, 3.3)
            mino.act(MinotaurEntity.RAGE)
            for (i in 0 until 24) {
                if (i % 3 == 0) world.spawnParticles(ParticleTypes.WHITE_SMOKE, at.x, at.y + 3.3, at.z, 0, 0.0, 1.0, 0.0, 0.12)
                if (i % 6 == 0) sound(BossSounds.MINOTAUR_SNORT, at, 2.5f, 0.6f)
                yield(Unit)
            }
            say(Cine.SPEAKER_MINOTAUR, "last.3")
            // the berserker: the maze is blown apart
            mino.berserk = true
            mino.act(MinotaurEntity.ROAR)
            sound(BossSounds.MINOTAUR_ROAR, at, 5f, 0.55f)
            sound(BossSounds.IMPACT_HEAVY, at, 4f, 0.5f)
            BloodFx.shake(world, at, 120.0, 2.8f, 40)
            BloodFx.screenFlash(world, at, 80.0, 0xC01010, 0.55f, 16)
            world.spawnParticles(BloodFx.dust(BloodFx.BRIGHT, 2.5f), at.x, at.y + 2.0, at.z, 160, 2.5, 2.0, 2.5, 0.0)
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1.0, at.z, 60, 2.0, 1.0, 2.0, 0.2)
            PitHazards.shockRing(mino, at, 36.0, 1.1, 0f, true)
            PitHazards.collapseWalls()
            Cine.orbit(everyone, at, 13.0, 11.0, toMid + 60.0, toMid + 150.0, 80, 70.0, 76.0, mino, 2.6)
            for (i in 0 until 40) {
                if (i % 5 == 0) sound(BossSounds.IMPACT_HEAVY, rel(world.random.nextDouble() * 50 - 25, 2.0, world.random.nextDouble() * 50 - 25), 2.5f, 0.7f)
                yield(Unit)
            }
            // now everyone stands in front of him again
            PitFight.gather(world, at, 12.0, toMid)
            gathered = true
            say(Cine.SPEAKER_NARRATOR, "last.4")
            Cine.orbit(everyone, at, 8.0, 1.2, toMid + 30.0, toMid + 70.0, 100, 60.0, 68.0, mino, 3.0)
            mino.act(MinotaurEntity.STOMP)
            Cine.title(everyone, "boss.hha_blood.title.last", "boss.hha_blood.title.last.sub", 80, 0xFF3A2A)
            sound(BossSounds.STINGER, at, 3f, 0.85f)
            wait(84)
        }
        return PitFight.SceneRun("last_bull", steps.iterator(), true) {
            // the maze is gone for good
            PitHazards.clear(world)
            PitHazards.discardAxe(mino, instant = true)
            if (!gathered) PitFight.gather(world, mino.entityPos, 12.0, angleDeg(mino.entityPos, PitArena.mid()))
            mino.burning = true
            mino.berserk = true
            mino.fury = 6
            mino.act(MinotaurEntity.IDLE)
            mino.scripted = false
            PitFight.beginStage(world, PitFight.LAST_BULL)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Finale: the butcher falls
    // ---------------------------------------------------------------------------------------------

    fun finale(): PitFight.SceneRun {
        val steps = sequence {
            val everyone = players
            val at = mino.entityPos
            val toMid = angleDeg(at, PitArena.mid())
            PitFight.gather(world, at, 10.0, toMid)
            Cine.music(everyone, null)
            mino.axeOut = true
            mino.act(MinotaurEntity.KNEEL)
            sound(BossSounds.MINOTAUR_HURT, at, 4f, 0.7f)
            val front = mino.forward()
            Cine.camera(everyone, at.add(front.multiply(6.5)).add(0.0, 2.0, 0.0), at.add(front.multiply(4.2)).add(0.0, 2.4, 0.0),
                Vec3d.ZERO, Vec3d.ZERO, 260, 50.0, 40.0, mino, 2.2)
            // his knee hits the floor (KNEEL animation tick ~14)
            wait(13)
            sound(BossSounds.IMPACT_HEAVY, at, 2.5f, 0.7f)
            BloodFx.shake(world, at, 30.0, 0.7f, 10)
            say(Cine.SPEAKER_MINOTAUR, "end.1", 6)
            say(Cine.SPEAKER_MINOTAUR, "end.2")
            mino.act(MinotaurEntity.DEATH)
            sound(BossSounds.MINOTAUR_DEATH, at, 4f, 1f)
            Cine.orbit(everyone, at, 9.0, 3.0, toMid + 40.0, toMid + 100.0, 200, 62.0, 58.0, mino, 1.0)
            wait(36)
            sound(BossSounds.IMPACT_HEAVY, at, 4f, 0.9f)
            BloodFx.shake(world, at, 60.0, 1.4f, 16)
            Cine.music(everyone, BossSounds.MUSIC_VICTORY, loop = false)
            say(Cine.SPEAKER_NARRATOR, "end.3")
            for (i in 0 until 40) {
                world.spawnParticles(BloodParticles.GLOB, at.x, at.y + 1.0, at.z, 5, 1.2, 0.8, 1.2, 0.05)
                if (i % 2 == 0) world.spawnParticles(BloodParticles.FLAME, at.x, at.y + 1.5, at.z, 3, 1.0, 1.0, 1.0, 0.02)
                yield(Unit)
            }
            mino.discard()

            // Hermes delivers a letter from Hades (HHA's messenger of the gods - a voice, his feather and a letter)
            val first = PitArena.state(world.server!!).kills == 0
            val above = at.add(0.0, 2.3, 0.0)
            val sky = at.add(0.0, 20.0, 0.0)
            sound(hhaSound("boss.whoosh"), at, 3f, 1f)
            sound(hhaSound("boss.chime"), at, 2.5f, 1f)
            BloodFx.screenFlash(world, at, 60.0, 0xF2C14E, 0.3f, 10)
            Cine.camera(everyone, at.add(front.multiply(8.0)).add(0.0, 1.4, 0.0), at.add(front.multiply(5.5)).add(0.0, 2.0, 0.0),
                sky, above, 70, 62.0, 52.0)
            feather = dev.henny.hha_blood.logic.FxDisplays.spawnStack(world, sky, net.minecraft.item.ItemStack(hhaItem("hermes_feather") ?: net.minecraft.item.Items.FEATHER),
                org.joml.Quaternionf(), 1.3f, fullBright = true, teleportTicks = 4)
            val featherParticle = net.minecraft.registry.Registries.PARTICLE_TYPE.get(net.minecraft.util.Identifier.of("hha", "feather")) as? net.minecraft.particle.SimpleParticleType
            for (i in 0..64) {
                val t = i / 64.0
                // a lazy spiral down to where the butcher fell
                val r = 3.0 * (1 - t)
                val a = t * 5 * Math.PI
                val p = sky.lerp(above, 1 - (1 - t) * (1 - t)).add(Math.cos(a) * r, 0.0, Math.sin(a) * r)
                if (i % 4 == 0) feather?.let { dev.henny.hha_blood.logic.FxDisplays.update(it, p, org.joml.Quaternionf().rotateY((a * 0.7).toFloat()).rotateZ(0.6f), 1.3f, 4) }
                world.spawnParticles(BloodFx.dust(0xF2C14E, 1.1f), p.x, p.y, p.z, 2, 0.15, 0.15, 0.15, 0.0)
                if (featherParticle != null && i % 8 == 0) world.spawnParticles(featherParticle, p.x, p.y, p.z, 1, 0.3, 0.3, 0.3, 0.0)
                yield(Unit)
            }
            // the feather drifts aside, the letter appears where it landed - always facing the viewer
            val aside = Vec3d(-Math.sin(Math.toRadians(toMid)), 0.0, Math.cos(Math.toRadians(toMid))).multiply(1.3)
            feather?.let { dev.henny.hha_blood.logic.FxDisplays.update(it, above.add(aside).add(0.0, 0.9, 0.0), org.joml.Quaternionf().rotateZ(0.5f), 0.8f, 12) }
            letter = dev.henny.hha_blood.logic.FxDisplays.spawnStack(world, above,
                net.minecraft.item.ItemStack(hhaItem("hermes_summons") ?: net.minecraft.item.Items.PAPER), org.joml.Quaternionf(), 1.2f, fullBright = true, teleportTicks = 3)
            letter?.setBillboardMode(net.minecraft.entity.decoration.DisplayEntity.BillboardMode.CENTER)
            sound(hhaSound("boss.blink"), above, 2f, 1.1f)
            world.spawnParticles(ParticleTypes.END_ROD, above.x, above.y, above.z, 20, 0.5, 0.5, 0.5, 0.05)
            Cine.orbit(everyone, above, 4.2, 0.6, toMid - 30.0, toMid + 30.0, 400, 56.0, 50.0, null, 0.0)
            if (first) {
                say(Cine.SPEAKER_HERMES, if (everyone.any(::knowsHermes)) "hermes.1b" else "hermes.1")
                say(Cine.SPEAKER_HERMES, "hermes.2")
                say(Cine.SPEAKER_NARRATOR, "hermes.letter")
                say(Cine.SPEAKER_HERMES, "hermes.3")
            } else {
                say(Cine.SPEAKER_HERMES, "hermes.again")
            }
            sound(hhaSound("boss.whoosh"), above, 2.5f, 1.2f)
            world.spawnParticles(BloodFx.dust(0xF2C14E, 1.4f), above.x, above.y, above.z, 30, 0.6, 0.6, 0.6, 0.0)
            removeCameo()
            Cine.title(everyone, "boss.hha_blood.title.victory", "boss.hha_blood.title.victory.sub", 110, 0xF2C14E)
            wait(110)
        }
        return PitFight.SceneRun("finale", steps.iterator(), true) {
            removeCameo()
            if (!mino.isRemoved) mino.discard()
            PitFight.victory(world)
        }
    }
}
