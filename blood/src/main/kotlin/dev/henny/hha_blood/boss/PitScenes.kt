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
            wait(30)
            glide(mino, balconyFront, 34)
            mino.act(MinotaurEntity.IDLE)
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
                if (row == 2) cerb.act(CerberusEntity.WAKE)
                wait(5)
            }
            sound(BossSounds.CERBERUS_GROWL, cerb.entityPos, 4f, 0.8f)
            wait(16)
            // 6: the Cerberus walks out of its kennel
            Cine.camera(everyone, rel(10.0, 2.0, -15.0), rel(12.0, 2.2, -12.0), Vec3d.ZERO, Vec3d.ZERO, 80, 60.0, 56.0, cerb, 1.6)
            cerb.yaw = 0f; cerb.bodyYaw = 0f; cerb.headYaw = 0f
            glide(cerb, gateInside, 22, smooth = false)
            // out through the gate and round the altar in front of it
            val pastAltar = rel(4.5, 0.0, -27.0)
            val turn = yawTo(cerb.entityPos, pastAltar)
            cerb.yaw = turn; cerb.bodyYaw = turn; cerb.headYaw = turn
            glide(cerb, pastAltar, 20, smooth = false)
            val turn2 = yawTo(cerb.entityPos, cerberusStart)
            cerb.yaw = turn2; cerb.bodyYaw = turn2; cerb.headYaw = turn2
            glide(cerb, cerberusStart, 30, smooth = false)
            cerb.yaw = 0f; cerb.bodyYaw = 0f; cerb.headYaw = 0f
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
            wait(80)
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
            Cine.camera(everyone, rel(15.0, 3.0, -8.0), rel(13.0, 2.4, -11.0), rel(0.0, 9.0, -31.0), rel(0.0, 1.0, -22.0), 60, 66.0, 60.0)
            mino.act(MinotaurEntity.JUMP_DOWN)
            sound(BossSounds.MINOTAUR_GRUNT, mino.entityPos, 3f, 0.7f)
            wait(8)
            glide(mino, minotaurLanding, 24, arc = 5.0, smooth = false)
            mino.act(MinotaurEntity.ROAR)
            val land = mino.entityPos
            sound(BossSounds.IMPACT_HEAVY, land, 5f, 0.7f)
            sound(BossSounds.AXE_IMPACT, land, 4f, 0.7f)
            BloodFx.shake(world, land, 100.0, 2.6f, 30)
            world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, land.x, land.y + 0.3, land.z, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticles(ParticleTypes.CLOUD, land.x, land.y + 0.3, land.z, 40, 3.0, 0.2, 3.0, 0.05)
            PitHazards.shockRing(mino, land, 16.0, 0.8, 0f, false)
            sound(BossSounds.MINOTAUR_ROAR, land, 5f, 0.9f)
            wait(24)
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

    fun labyrinth(): PitFight.SceneRun {
        wallsRaised = false
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
            PitHazards.raiseWalls(world, mino, 380)
            wallsRaised = true
            Cine.orbit(everyone, rel(0.0, 0.0, 0.0), 30.0, 24.0, toMid + 160.0, toMid + 220.0, 170, 70.0, 66.0, null, 0.0)
            wait(20)
            say(Cine.SPEAKER_MINOTAUR, "laby.2")
            say(Cine.SPEAKER_MINOTAUR, "laby.3")
            Cine.title(everyone, "boss.hha_blood.title.labyrinth", "boss.hha_blood.title.labyrinth.sub", 80)
            sound(BossSounds.STINGER, at, 3f, 1.0f)
            wait(80)
        }
        return PitFight.SceneRun("labyrinth", steps.iterator(), true) {
            if (!wallsRaised) PitHazards.raiseWalls(world, mino, 380)
            mino.act(MinotaurEntity.IDLE)
            mino.scripted = false
            PitFight.beginStage(world, PitFight.LABYRINTH)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stage 4 -> 5: he takes the Cerberus' hellfire
    // ---------------------------------------------------------------------------------------------

    fun lastBull(): PitFight.SceneRun {
        val steps = sequence {
            val everyone = players
            val at = mino.entityPos
            val toMid = angleDeg(at, PitArena.mid())
            PitFight.gather(world, at, 12.0, toMid)
            Cine.music(everyone, BossSounds.MUSIC_TENSION)
            mino.act(MinotaurEntity.KNEEL)
            sound(BossSounds.MINOTAUR_HURT, at, 3f, 0.8f)
            Cine.orbit(everyone, at, 5.5, 2.0, toMid - 20.0, toMid + 20.0, 120, 52.0, 46.0, mino, 2.4)
            say(Cine.SPEAKER_MINOTAUR, "last.1")
            // three spirit flames come out of the kennel
            Cine.camera(everyone, at.add(9.0, 5.0, 9.0), at.add(7.0, 4.0, 7.0), Vec3d.ZERO, Vec3d.ZERO, 110, 66.0, 60.0, mino, 2.4)
            val gate = gateFront
            sound(BossSounds.CERBERUS_HOWL, gate, 3f, 1.4f)
            mino.act(MinotaurEntity.ABSORB)
            for (i in 0 until 56) {
                val t = i / 56.0
                for (k in 0..2) {
                    val side = (k - 1) * 3.0
                    val from = gate.add(side, 0.0, 0.0)
                    val to = at.add(0.0, 2.6, 0.0)
                    val p = from.lerp(to, t).add(0.0, Math.sin(t * Math.PI) * 6.0, 0.0)
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
            PitHazards.shockRing(mino, at, 14.0, 0.8, 0f, true)
            say(Cine.SPEAKER_NARRATOR, "last.2")
            mino.act(MinotaurEntity.ROAR)
            sound(BossSounds.MINOTAUR_ROAR, at, 5f, 0.8f)
            Cine.orbit(everyone, at, 8.0, 1.2, toMid + 30.0, toMid + 70.0, 100, 60.0, 68.0, mino, 3.0)
            Cine.title(everyone, "boss.hha_blood.title.last", "boss.hha_blood.title.last.sub", 80, 0xFF6A2A)
            sound(BossSounds.STINGER, at, 3f, 0.85f)
            wait(84)
        }
        return PitFight.SceneRun("last_bull", steps.iterator(), true) {
            mino.burning = true
            mino.fury = 4
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
            mino.axeOut = false
            mino.act(MinotaurEntity.KNEEL)
            sound(BossSounds.MINOTAUR_HURT, at, 4f, 0.7f)
            val front = mino.forward()
            Cine.camera(everyone, at.add(front.multiply(6.5)).add(0.0, 2.0, 0.0), at.add(front.multiply(4.2)).add(0.0, 2.4, 0.0),
                Vec3d.ZERO, Vec3d.ZERO, 260, 50.0, 40.0, mino, 2.2)
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
