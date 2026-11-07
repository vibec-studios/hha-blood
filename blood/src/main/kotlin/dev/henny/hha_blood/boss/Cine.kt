package dev.henny.hha_blood.boss

import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.Entity
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d

/** Server -> client: one cutscene command (see [Cine]). Opcodes are the protocol: only append. */
data class CinePayload(val op: Int, val numbers: List<Double>, val texts: List<String>) : CustomPayload {
    override fun getId(): CustomPayload.Id<CinePayload> = ID

    companion object {
        val ID = CustomPayload.Id<CinePayload>(HhaBlood.id("cine"))
        val CODEC: PacketCodec<RegistryByteBuf, CinePayload> = PacketCodec.tuple(
            PacketCodecs.VAR_INT, CinePayload::op,
            PacketCodecs.DOUBLE.collect(PacketCodecs.toList()), CinePayload::numbers,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), CinePayload::texts,
            ::CinePayload,
        )
    }
}

/** Client -> server: "skip this cutscene" (every participant has to agree). */
// A singleton: PacketCodec.unit only encodes the exact instance it was built with.
object CineSkipPayload : CustomPayload {
    val ID = CustomPayload.Id<CineSkipPayload>(HhaBlood.id("cine_skip"))
    val CODEC: PacketCodec<RegistryByteBuf, CineSkipPayload> = PacketCodec.unit(this)

    override fun getId(): CustomPayload.Id<CineSkipPayload> = ID
}

/**
 * Commands for the client's cutscene player (`client/cine/CinePlayer`). Everything comes from the server,
 * the client only plays it back: camera moves (straight dolly or orbit, optionally looking at an entity),
 * letterbox, a dialogue box with typewriter text and voice blips, fades, title cards and the boss music.
 * Built after the cutscene system of HHA's Hermes fight (vibec-studios/HHA, branch `new`).
 */
object Cine {

    const val OP_START = 0
    const val OP_END = 1
    const val OP_CAMERA = 2
    const val OP_ORBIT = 3
    const val OP_DIALOG = 4
    const val OP_FADE = 5
    const val OP_TITLE = 6
    const val OP_MUSIC = 7
    const val OP_SKIP_STATE = 8
    const val OP_RESET = 9
    const val OP_PARALYZED = 10

    const val SPEAKER_NARRATOR = 0
    const val SPEAKER_MINOTAUR = 1
    const val SPEAKER_CERBERUS = 2
    /** HHA's messenger of the gods (cameo in the finale). */
    const val SPEAKER_HERMES = 3

    fun registerPayloads() {
        PayloadTypeRegistry.playS2C().register(CinePayload.ID, CinePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(CineSkipPayload.ID, CineSkipPayload.CODEC)
    }

    /** Letterbox on, input locked, HUD hidden. [skippable]: players may vote to skip. */
    fun start(players: Iterable<ServerPlayerEntity>, skippable: Boolean) =
        send(players, OP_START, listOf(if (skippable) 1.0 else 0.0))

    fun end(players: Iterable<ServerPlayerEntity>) = send(players, OP_END)

    /** Everything off (music too): for players that leave the fight. */
    fun reset(players: Iterable<ServerPlayerEntity>) = send(players, OP_RESET)

    /** Straight camera move from [from] to [to]; the view goes from [lookFrom] to [lookTo] or follows [lookAt]. */
    fun camera(
        players: Iterable<ServerPlayerEntity>, from: Vec3d, to: Vec3d, lookFrom: Vec3d, lookTo: Vec3d, ticks: Int,
        fovFrom: Double = 70.0, fovTo: Double = 70.0, lookAt: Entity? = null, lookOffset: Double = 1.6,
    ) = send(
        players, OP_CAMERA,
        listOf(
            from.x, from.y, from.z, to.x, to.y, to.z,
            lookFrom.x, lookFrom.y, lookFrom.z, lookTo.x, lookTo.y, lookTo.z,
            ticks.toDouble(), fovFrom, fovTo, (lookAt?.id ?: -1).toDouble(), lookOffset,
        ),
    )

    /** Camera circling [center] (angles in degrees, 0 = +X, 90 = +Z); looks at [lookAt] or the centre. */
    fun orbit(
        players: Iterable<ServerPlayerEntity>, center: Vec3d, radius: Double, height: Double, angleFrom: Double, angleTo: Double,
        ticks: Int, fovFrom: Double = 70.0, fovTo: Double = 70.0, lookAt: Entity? = null, lookOffset: Double = 1.6,
        radiusTo: Double = radius, heightTo: Double = height,
    ) = send(
        players, OP_ORBIT,
        listOf(
            center.x, center.y, center.z, radius, radiusTo, height, heightTo, angleFrom, angleTo,
            ticks.toDouble(), fovFrom, fovTo, (lookAt?.id ?: -1).toDouble(), lookOffset,
        ),
    )

    /** Dialogue box (also outside cutscenes, then without letterbox). [ticks] = minimum display time. */
    fun dialog(players: Iterable<ServerPlayerEntity>, speaker: Int, lineKey: String, ticks: Int) =
        send(players, OP_DIALOG, listOf(speaker.toDouble(), ticks.toDouble()), listOf(lineKey))

    /** Fade: colour 0xRRGGBB from opacity [from] to [to]. */
    fun fade(players: Iterable<ServerPlayerEntity>, color: Int, from: Double, to: Double, ticks: Int) =
        send(players, OP_FADE, listOf(color.toDouble(), from, to, ticks.toDouble()))

    fun title(players: Iterable<ServerPlayerEntity>, titleKey: String, subtitleKey: String, ticks: Int, color: Int = 0xE0202A) =
        send(players, OP_TITLE, listOf(ticks.toDouble(), color.toDouble()), listOf(titleKey, subtitleKey))

    /** Boss music (crossfades); null stops it. [loop] = false for a one-shot (victory). */
    fun music(players: Iterable<ServerPlayerEntity>, event: SoundEvent?, loop: Boolean = true) =
        send(players, OP_MUSIC, listOf(if (loop) 1.0 else 0.0), listOf(event?.id()?.toString() ?: ""))

    fun skipState(players: Iterable<ServerPlayerEntity>, votes: Int, needed: Int) =
        send(players, OP_SKIP_STATE, listOf(votes.toDouble(), needed.toDouble()))

    /** The player is paralysed by the Cerberus howl for [ticks] (screen effect). */
    fun paralyzed(player: ServerPlayerEntity, ticks: Int) = send(listOf(player), OP_PARALYZED, listOf(ticks.toDouble()))

    private fun send(players: Iterable<ServerPlayerEntity>, op: Int, numbers: List<Double> = emptyList(), texts: List<String> = emptyList()) {
        val payload = CinePayload(op, numbers, texts)
        for (player in players) if (ServerPlayNetworking.canSend(player, CinePayload.ID)) ServerPlayNetworking.send(player, payload)
    }
}
