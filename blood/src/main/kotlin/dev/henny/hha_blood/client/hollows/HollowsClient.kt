package dev.henny.hha_blood.client.hollows

import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.HollowsParticles
import dev.henny.hha_blood.hollows.HollowsRegion
import dev.henny.hha_blood.hollows.PitBlocks
import dev.henny.hha_blood.hollows.PitApproach
import dev.henny.hha_blood.hollows.PitHeartBlockEntity
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteProvider
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry
import net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap
import net.minecraft.client.MinecraftClient
import net.minecraft.client.particle.BillboardParticle
import net.minecraft.client.particle.ParticleFactory
import net.minecraft.client.render.BlockRenderLayer
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.SimpleParticleType
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** Client side of the Vein Hollows / Slaughter Pit decoration (called from HhaBloodClient). */
object HollowsClient {
    fun register() {
        BlockEntityRendererFactories.register(PitBlocks.WATCHING_EYE_BE, ::WatchingEyeRenderer)
        BlockEntityRendererFactories.register(PitBlocks.PIT_HEART_BE, ::PitHeartRenderer)
        BlockRenderLayerMap.putBlock(PitBlocks.BLOOD_FALL, BlockRenderLayer.TRANSLUCENT)
        ParticleFactoryRegistry.getInstance().register(HollowsParticles.MOTE, ParticleFactoryRegistry.PendingParticleFactory<SimpleParticleType> { provider ->
            ParticleFactory<SimpleParticleType> { _, world, x, y, z, _, vy, _, _ -> BloodMoteParticle(world, x, y, z, vy, provider) }
        })
        ClientTickEvents.END_CLIENT_TICK.register { Heartbeat.tick(it) }
    }
}

/**
 * One clock for the heart of the pit: the renderer reads [phaseAt] and the sound plays on every new beat, so
 * picture and sound are always in sync. The pit "smells blood": the lower YOUR health, the faster it beats
 * (52 bpm at full health, up to ~120 bpm close to death) - every player hears and sees his own tempo.
 * Away from the heart, inside the Vein Hollows, the cave itself starts beating now and then, far and muffled.
 */
object Heartbeat {
    private var phase = 0.0
    private var bpm = 52.0
    private var episode = 0           // > 0: beats left of a distant heartbeat episode in the biome
    private var episodeLength = 0
    private var cooldown = 400

    fun phaseAt(tickDelta: Float): Double = phase + bpm / 1200.0 * tickDelta

    fun tick(client: MinecraftClient) {
        val player = client.player ?: return
        val world = client.world ?: return
        if (client.isPaused) return
        val f = (player.health / player.maxHealth).toDouble().coerceIn(0.0, 1.0)
        val target = if (f < 0.6) 52.0 + (0.6 - f) / 0.6 * 70.0 else 52.0
        bpm += (target - bpm) * 0.03
        val before = floor(phase)
        phase += bpm / 1200.0
        if (floor(phase) != before) beat(client, world, player.entityPos)
        if (cooldown > 0) cooldown--
    }

    private fun beat(client: MinecraftClient, world: ClientWorld, at: Vec3d) {
        val heart = PitHeartBlockEntity.HEARTS.filter { !it.isRemoved && it.world === world }
            .minByOrNull { it.pos.getSquaredDistance(at) }
        if (heart != null) {
            // on the way down (stair tower + processional hall) the heart is heard through the rock, louder with
            // every step towards the pit; the arena centre lies right below the heart's anchor
            val dx = at.x - (heart.pos.x + 0.5)
            val dz = at.z - (heart.pos.z + 0.5)
            val dy = at.y - (heart.pos.y - (HeartVaultPiece.H.toInt() - 1))
            if (abs(dx) <= PitApproach.GATE_HALF + 3 && dz in 44.0..(PitApproach.MAX_DZ + 1.0) && dy in -6.0..90.0) {
                val k = ((PitApproach.TOWER_DZ - dz) / (PitApproach.TOWER_DZ - 44.0)).coerceIn(0.0, 1.0)
                val vol = (0.07 + 0.38 * k * k).toFloat()
                client.soundManager.play(PositionedSoundInstance.ambient(HollowsParticles.HEARTBEAT, (0.92 + 0.08 * k).toFloat(), vol))
                return
            }
        }
        if (heart != null && heart.pos.getSquaredDistance(at) < 110.0 * 110.0) {
            val p = heart.pos
            // volume > 1 only widens the range (16 blocks per unit): audible in the whole pit
            world.playSoundClient(p.x + 0.5, p.y - 8.0, p.z + 0.5, HollowsParticles.HEARTBEAT, SoundCategory.AMBIENT, 4.5f, 1.0f, false)
            return
        }
        // far from the heart: in the Hollows the cave beats in episodes of 8-14 beats every 40-90 s
        val inBiome = world.getBiome(net.minecraft.util.math.BlockPos.ofFloored(at)).matchesKey(HollowsRegion.BIOME)
        if (!inBiome) { episode = 0; return }
        if (episode <= 0) {
            if (cooldown > 0) return
            episodeLength = 8 + world.random.nextInt(7)
            episode = episodeLength
            cooldown = 800 + world.random.nextInt(1000)
        }
        val k = (episodeLength - episode).toDouble() / (episodeLength - 1).coerceAtLeast(1)
        val vol = (0.12 + 0.33 * sin(k * Math.PI)).toFloat()
        client.soundManager.play(PositionedSoundInstance.ambient(HollowsParticles.HEARTBEAT_FAR, 0.92f, vol))
        episode--
    }
}

/** "Reverse blood rain": a tiny glowing drop that floats up, sways and fades. */
class BloodMoteParticle(
    world: ClientWorld, x: Double, y: Double, z: Double, vy: Double, provider: FabricSpriteProvider,
) : BillboardParticle(world, x, y, z, provider.sprites[world.random.nextInt(provider.sprites.size)]) {
    private val baseScale: Float
    private val swayPhase = random.nextDouble() * 6.28
    private val rise: Double

    init {
        setBoundingBoxSpacing(0.01f, 0.01f)
        maxAge = 70 + random.nextInt(90)
        baseScale = 0.035f + random.nextFloat() * 0.04f
        scale = baseScale
        rise = if (vy > 0.0) vy * 0.5 else 0.012 + random.nextDouble() * 0.014
        velocityY = rise
        collidesWithWorld = false
        alpha = 0f
    }

    override fun getRenderType(): RenderType = RenderType.PARTICLE_ATLAS_TRANSLUCENT
    override fun getBrightness(tint: Float): Int = 0xF000F0

    override fun tick() {
        lastX = x; lastY = y; lastZ = z
        if (age++ >= maxAge) { markDead(); return }
        val t = age * 0.07 + swayPhase
        velocityX = sin(t) * 0.006
        velocityZ = sin(t * 1.3 + 1.0) * 0.006
        velocityY = velocityY * 0.97 + rise * 0.03
        move(velocityX, velocityY, velocityZ)
        val life = age.toFloat() / maxAge
        alpha = when {
            life < 0.15f -> life / 0.15f
            life > 0.7f -> (1f - life) / 0.3f
            else -> 1f
        } * 0.9f
        scale = baseScale * (0.8f + 0.2f * sin(age * 0.3f))
    }
}
