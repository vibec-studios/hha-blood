package dev.henny.hha_blood.net

import dev.henny.hha_blood.HhaBlood
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import java.util.UUID

/** Client asks to open the Blood Tracker target selection. */
class TrackerOpenPayload : CustomPayload {
    override fun getId(): CustomPayload.Id<TrackerOpenPayload> = ID

    companion object {
        val ID = CustomPayload.Id<TrackerOpenPayload>(HhaBlood.id("tracker_open"))
        val CODEC: PacketCodec<PacketByteBuf, TrackerOpenPayload> = CustomPayload.codecOf({ _, _ -> }, { TrackerOpenPayload() })
    }
}

/** Client picked a target ([target] == null -> stop the current track). */
data class TrackerSelectPayload(val target: UUID?) : CustomPayload {
    override fun getId(): CustomPayload.Id<TrackerSelectPayload> = ID

    companion object {
        val ID = CustomPayload.Id<TrackerSelectPayload>(HhaBlood.id("tracker_select"))
        val CODEC: PacketCodec<PacketByteBuf, TrackerSelectPayload> = CustomPayload.codecOf(
            { p, buf ->
                buf.writeBoolean(p.target != null)
                p.target?.let { buf.writeUuid(it) }
            },
            { buf -> TrackerSelectPayload(if (buf.readBoolean()) buf.readUuid() else null) },
        )
    }
}

/** Server -> client: candidates for the selection screen + the current track state. */
data class TrackerListPayload(
    val candidates: List<Candidate>,
    val currentTarget: UUID?,
    val currentName: String,
    val remainingTicks: Int,
    val durationTicks: Int,
    val cooldownTicks: Int,
) : CustomPayload {
    data class Candidate(val uuid: UUID, val name: String, val distance: Int, val dimension: String)

    override fun getId(): CustomPayload.Id<TrackerListPayload> = ID

    companion object {
        val ID = CustomPayload.Id<TrackerListPayload>(HhaBlood.id("tracker_list"))
        val CODEC: PacketCodec<PacketByteBuf, TrackerListPayload> = CustomPayload.codecOf(
            { p, buf ->
                buf.writeVarInt(p.candidates.size)
                for (c in p.candidates) {
                    buf.writeUuid(c.uuid)
                    buf.writeString(c.name, 64)
                    buf.writeVarInt(c.distance)
                    buf.writeString(c.dimension, 128)
                }
                buf.writeBoolean(p.currentTarget != null)
                p.currentTarget?.let { buf.writeUuid(it) }
                buf.writeString(p.currentName, 64)
                buf.writeVarInt(p.remainingTicks)
                buf.writeVarInt(p.durationTicks)
                buf.writeVarInt(p.cooldownTicks)
            },
            { buf ->
                val n = buf.readVarInt().coerceIn(0, 512)
                val list = ArrayList<Candidate>(n)
                repeat(n) { list += Candidate(buf.readUuid(), buf.readString(64), buf.readVarInt(), buf.readString(128)) }
                val current = if (buf.readBoolean()) buf.readUuid() else null
                TrackerListPayload(list, current, buf.readString(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
            },
        )
    }
}

/** Server -> client: entity [entityId] is (no longer) fully invisible (Blood Trail). */
data class TrueInvisPayload(val entityId: Int, val active: Boolean) : CustomPayload {
    override fun getId(): CustomPayload.Id<TrueInvisPayload> = ID

    companion object {
        val ID = CustomPayload.Id<TrueInvisPayload>(HhaBlood.id("true_invis"))
        val CODEC: PacketCodec<PacketByteBuf, TrueInvisPayload> = CustomPayload.codecOf(
            { p, buf ->
                buf.writeVarInt(p.entityId)
                buf.writeBoolean(p.active)
            },
            { buf -> TrueInvisPayload(buf.readVarInt(), buf.readBoolean()) },
        )
    }
}

/** Server -> client: you are (no longer) being hunted by a Blood Tracker. */
data class HuntedPayload(val active: Boolean) : CustomPayload {
    override fun getId(): CustomPayload.Id<HuntedPayload> = ID

    companion object {
        val ID = CustomPayload.Id<HuntedPayload>(HhaBlood.id("hunted"))
        val CODEC: PacketCodec<PacketByteBuf, HuntedPayload> = CustomPayload.codecOf(
            { p, buf -> buf.writeBoolean(p.active) },
            { buf -> HuntedPayload(buf.readBoolean()) },
        )
    }
}

/**
 * Server -> client: a status line (translation key + string args) that the client draws ABOVE HHA's
 * cooldown icons - the vanilla action bar sits exactly behind them.
 */
data class HudLinePayload(val key: String, val args: List<String>, val color: Int, val ticks: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<HudLinePayload> = ID

    companion object {
        val ID = CustomPayload.Id<HudLinePayload>(HhaBlood.id("hud_line"))
        val CODEC: PacketCodec<PacketByteBuf, HudLinePayload> = CustomPayload.codecOf(
            { p, buf ->
                buf.writeString(p.key, 128)
                buf.writeVarInt(p.args.size)
                p.args.forEach { buf.writeString(it, 128) }
                buf.writeInt(p.color)
                buf.writeVarInt(p.ticks)
            },
            { buf ->
                val key = buf.readString(128)
                val n = buf.readVarInt().coerceIn(0, 16)
                val args = List(n) { buf.readString(128) }
                HudLinePayload(key, args, buf.readInt(), buf.readVarInt())
            },
        )
    }
}

/** Server -> client: shake the camera ([strength] ~ degrees of roll at the start, fades over [ticks]). */
data class ShakePayload(val strength: Float, val ticks: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<ShakePayload> = ID

    companion object {
        val ID = CustomPayload.Id<ShakePayload>(HhaBlood.id("shake"))
        val CODEC: PacketCodec<PacketByteBuf, ShakePayload> = CustomPayload.codecOf(
            { p, buf -> buf.writeFloat(p.strength); buf.writeVarInt(p.ticks) },
            { buf -> ShakePayload(buf.readFloat(), buf.readVarInt()) },
        )
    }
}

/** Server -> client: tint the whole screen with [color] at [strength] (0..1) for a moment, fading over [ticks]. */
data class ScreenFlashPayload(val color: Int, val strength: Float, val ticks: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<ScreenFlashPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ScreenFlashPayload>(HhaBlood.id("screen_flash"))
        val CODEC: PacketCodec<PacketByteBuf, ScreenFlashPayload> = CustomPayload.codecOf(
            { p, buf -> buf.writeInt(p.color); buf.writeFloat(p.strength); buf.writeVarInt(p.ticks) },
            { buf -> ScreenFlashPayload(buf.readInt(), buf.readFloat(), buf.readVarInt()) },
        )
    }
}

object BloodNet {
    fun registerPayloads() {
        PayloadTypeRegistry.playC2S().register(TrackerOpenPayload.ID, TrackerOpenPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(TrackerSelectPayload.ID, TrackerSelectPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(TrackerListPayload.ID, TrackerListPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(TrueInvisPayload.ID, TrueInvisPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HuntedPayload.ID, HuntedPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(HudLinePayload.ID, HudLinePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ShakePayload.ID, ShakePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ScreenFlashPayload.ID, ScreenFlashPayload.CODEC)
    }
}
