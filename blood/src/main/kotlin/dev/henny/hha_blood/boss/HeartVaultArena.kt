package dev.henny.hha_blood.boss

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.henny.hha_blood.BloodItems
import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.HollowsBlocks
import dev.henny.hha_blood.hollows.HollowsRegion
import dev.henny.hha_blood.logic.BloodFx
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Uuids
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import net.minecraft.world.World
import java.util.Optional
import java.util.UUID

/**
 * The Heart Vault fight: spawns the Blood Prince on his throne when players come close, wakes him up
 * when someone enters the dome, shows the boss bar, sends him back to the throne (fully healed) when
 * everybody fled or died, hands out the rewards (first kill per player = full reward, every later kill
 * a lot less - Mats) and lets players call him back with a Blood Offering on the altar.
 */
object HeartVaultArena {

    class VaultState(
        var boss: Optional<UUID> = Optional.empty(),
        var defeated: Boolean = false,
        var kills: Int = 0,
        val playerKills: MutableMap<UUID, Int> = HashMap(),
    ) : PersistentState() {
        companion object {
            val CODEC: Codec<VaultState> = RecordCodecBuilder.create { i ->
                i.group(
                    Uuids.CODEC.optionalFieldOf("boss").forGetter { it.boss },
                    Codec.BOOL.optionalFieldOf("defeated", false).forGetter { it.defeated },
                    Codec.INT.optionalFieldOf("kills", 0).forGetter { it.kills },
                    Codec.unboundedMap(Uuids.STRING_CODEC, Codec.INT).optionalFieldOf("player_kills", emptyMap()).forGetter { it.playerKills },
                ).apply(i) { b, d, k, m -> VaultState(b, d, k, HashMap(m)) }
            }
            val TYPE = PersistentStateType("hha_blood_heart_vault", { VaultState() }, CODEC, null)
        }
    }

    private var emptyTicks = 0
    private var missingTicks = 0
    private val barViewers = HashSet<UUID>()

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        ServerLifecycleEvents.SERVER_STOPPING.register { PrinceAttacks.reset(); DragonAttacks.reset(); barViewers.clear() }
        UseBlockCallback.EVENT.register { player, world, hand, hit ->
            if (world.isClient || !world.getBlockState(hit.blockPos).isOf(HollowsBlocks.SANGUINE_ALTAR)) return@register ActionResult.PASS
            useAltar(world as ServerWorld, player, player.getStackInHand(hand))
        }
    }

    fun state(server: MinecraftServer): VaultState = server.overworld.persistentStateManager.getOrCreate(VaultState.TYPE)

    // ------------------------------------------------------------------------------------- geometry

    fun inDome(pos: Vec3d): Boolean {
        val c = HollowsRegion.center() ?: return false
        val dx = pos.x - (c.x + 0.5)
        val dz = pos.z - (c.z + 0.5)
        val dy = pos.y - HeartVaultPiece.FLOOR
        return dy > -6 && dy < HeartVaultPiece.H + 2 && dx * dx + dz * dz < (HeartVaultPiece.R - 0.5) * (HeartVaultPiece.R - 0.5)
    }

    fun playersInArena(world: ServerWorld): List<PlayerEntity> =
        if (world.registryKey != World.NETHER) emptyList()
        else (world.players + BossDebug.extraTargets).filter { !it.isSpectator && it.isAlive && inDome(it.entityPos) }

    fun prince(world: ServerWorld): BloodPrinceEntity? =
        state(world.server!!).boss.map { world.getEntity(it) as? BloodPrinceEntity }.orElse(null)

    fun dragon(world: ServerWorld): CruoraxEntity? =
        state(world.server!!).boss.map { world.getEntity(it) as? CruoraxEntity }.orElse(null)

    // ------------------------------------------------------------------------------------- tick

    private fun tick(server: MinecraftServer) {
        PrinceAttacks.tick()
        DragonAttacks.tick()
        if (server.ticks % 5 != 0) return
        val world = server.getWorld(World.NETHER) ?: return
        val c = HollowsRegion.center() ?: return
        val st = state(server)
        val loaded = world.chunkManager.isChunkLoaded(c.x shr 4, (c.z - 25) shr 4) && world.chunkManager.isChunkLoaded(c.x shr 4, c.z shr 4)
        if (!loaded) { updateBar(world, null); return }
        var prince = prince(world)
        val dragon = dragon(world)

        // the stored boss is gone (killed by a command, ...) -> forget him after a grace time
        if (st.boss.isPresent && prince == null && dragon == null) {
            if (++missingTicks > 20) { st.boss = Optional.empty(); st.markDirty(); missingTicks = 0 }
        } else missingTicks = 0

        val inside = playersInArena(world)
        if (dragon != null) {
            // phase 2/3 - everybody fled or died: the whole fight resets, the Prince sits on his throne again
            if (inside.isEmpty()) {
                emptyTicks += 5
                if (emptyTicks >= 400) {
                    BloodPrinceLines.sayNear(world, c, "reset")
                    dragon.vanish()
                    st.boss = Optional.empty(); st.markDirty()
                    spawnPrince(world, st)
                    emptyTicks = 0
                }
            } else emptyTicks = 0
            updateBar(world, if (dragon.isAlive) dragon.bossBar else null)
            return
        }

        val near = (world.players + BossDebug.extraTargets).any { !it.isSpectator && it.squaredDistanceTo(c.x + 0.5, HeartVaultPiece.FLOOR.toDouble(), c.z + 0.5) < 110.0 * 110.0 }
        if (prince == null && !st.boss.isPresent && !st.defeated && near) {
            prince = spawnPrince(world, st)
        }
        val boss = prince ?: run { updateBar(world, null); return }

        if (boss.action == BloodPrinceEntity.THRONE) {
            if (inside.isNotEmpty()) boss.awaken()
            emptyTicks = 0
        } else {
            // leash: never leaves the dome
            if (!inDome(boss.entityPos) && boss.action != BloodPrinceEntity.HIDDEN && boss.action != BloodPrinceEntity.TRANSFORM) {
                boss.refreshPositionAndAngles(c.x + 0.5, HeartVaultPiece.FLOOR + 0.5, c.z + 6.5, boss.yaw, 0f)
            }
            if (inside.isEmpty() && boss.action != BloodPrinceEntity.TRANSFORM) {
                emptyTicks += 5
                if (emptyTicks >= 400) {
                    BloodPrinceLines.sayNear(world, c, "reset")
                    boss.resetToThrone()
                    PrinceAttacks.clearOwner(boss.uuid)
                    emptyTicks = 0
                }
            } else emptyTicks = 0
        }
        updateBar(world, if (boss.action == BloodPrinceEntity.THRONE) null else boss.bossBar)
    }

    private fun updateBar(world: ServerWorld, bar: net.minecraft.entity.boss.ServerBossBar?) {
        val should = if (bar == null) emptySet() else playersInArena(world).filterIsInstance<ServerPlayerEntity>().map { it.uuid }.toSet()
        if (bar != null) {
            for (p in world.players) {
                if (p.uuid in should && p !in bar.players) bar.addPlayer(p)
                if (p.uuid !in should && p in bar.players) bar.removePlayer(p)
            }
            bar.isVisible = true
        }
        barViewers.clear(); barViewers.addAll(should)
    }
    fun spawnPrince(world: ServerWorld, st: VaultState): BloodPrinceEntity? {
        val e = BossContent.BLOOD_PRINCE.create(world, SpawnReason.EVENT) ?: return null
        e.equipPrince()
        val s = e.throneSeat()
        e.refreshPositionAndAngles(s.x, s.y, s.z, 0f, 0f)
        e.setNoGravity(true)
        if (!world.spawnEntity(e)) return null
        st.boss = Optional.of(e.uuid)
        st.markDirty()
        HhaBlood.LOGGER.info("Blood Prince spawned on his throne at {}", BlockPos.ofFloored(s))
        return e
    }

    // ------------------------------------------------------------------------------------- death + rewards

    /** Phase 1 is over: Cruorax forms above the middle of the arena. */
    fun onPrinceFallen(world: ServerWorld, at: Vec3d) {
        val st = state(world.server!!)
        if (dragon(world) != null) return
        val c = HollowsRegion.center() ?: return
        val d = BossContent.CRUORAX.create(world, SpawnReason.EVENT) ?: return
        d.refreshPositionAndAngles(c.x + 0.5, HeartVaultPiece.FLOOR + 10.0, c.z + 0.5, 180f, 0f)
        if (!world.spawnEntity(d)) return
        st.boss = Optional.of(d.uuid)
        st.markDirty()
        HhaBlood.LOGGER.info("Cruorax rises (Prince fell at {})", BlockPos.ofFloored(at))
    }

    /** The whole fight is won: rewards (full for the first kill per player, much less afterwards). */
    fun onDragonDeath(world: ServerWorld, dragon: CruoraxEntity) {
        val st = state(world.server!!)
        st.boss = Optional.empty()
        st.defeated = true
        st.kills++
        val at = dragon.entityPos.add(0.0, 1.0, 0.0)
        val players = playersInArena(world).ifEmpty { world.players.filter { it.squaredDistanceTo(dragon) < 80.0 * 80.0 } }
        for (p in players) {
            val n = st.playerKills.getOrDefault(p.uuid, 0)
            st.playerKills[p.uuid] = n + 1
            val loot = ArrayList<ItemStack>()
            if (n == 0) {
                // first time: the full reward - enough for a complete set of Blood armour
                loot += ItemStack(BloodItems.BLOOD_INGOT, 6)
                loot += ItemStack(Items.NETHERITE_INGOT, 2)
                loot += ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2)
                loot += ItemStack(HollowsBlocks.BLOOD_CRYSTAL_SHARD, 12)
                loot += ItemStack(BossContent.HEART_OF_CRUORAX, 1)
                p.sendMessage(Text.translatable("boss.hha_blood.reward.first").formatted(Formatting.GOLD), false)
            } else {
                // every later kill: much less (Mats)
                if (world.random.nextFloat() < 0.5f) loot += ItemStack(BloodItems.BLOOD_INGOT, 1)
                loot += ItemStack(HollowsBlocks.BLOOD_CRYSTAL_SHARD, 3 + world.random.nextInt(4))
                p.sendMessage(Text.translatable("boss.hha_blood.reward.again").formatted(Formatting.GRAY), false)
            }
            for (stack in loot) {
                val item = ItemEntity(world, at.x, at.y, at.z, stack, world.random.nextGaussian() * 0.1, 0.35, world.random.nextGaussian() * 0.1)
                item.setOwner(p.uuid)
                item.setPickupDelay(20)
                item.setNeverDespawn()
                world.spawnEntity(item)
            }
        }
        st.markDirty()
        BloodFx.sound(world, at, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.8f)
    }

    // ------------------------------------------------------------------------------------- altar

    private fun useAltar(world: ServerWorld, player: PlayerEntity, stack: ItemStack): ActionResult {
        val st = state(world.server!!)
        val boss = prince(world) ?: dragon(world)
        if (boss != null || st.boss.isPresent) {
            player.sendMessage(Text.translatable("boss.hha_blood.altar.awake").formatted(Formatting.DARK_RED), true)
            return ActionResult.SUCCESS
        }
        if (!st.defeated) {
            player.sendMessage(Text.translatable("boss.hha_blood.altar.waiting").formatted(Formatting.DARK_RED), true)
            return ActionResult.SUCCESS
        }
        if (!stack.isOf(BossContent.BLOOD_OFFERING)) {
            player.sendMessage(Text.translatable("boss.hha_blood.altar.need").formatted(Formatting.RED), true)
            return ActionResult.SUCCESS
        }
        if (!player.isCreative) stack.decrement(1)
        st.defeated = false
        st.markDirty()
        val e = spawnPrince(world, st) ?: return ActionResult.SUCCESS
        val c = HollowsRegion.center()
        if (c != null) {
            val altar = Vec3d(c.x + 0.5, HeartVaultPiece.FLOOR + 1.0, c.z - 16.5 - HeartVaultPiece.THRONE_SHIFT)
            BloodFx.spawn(world, BloodFx.flame, altar, 40, 0.4, 0.8, 0.4, 0.05)
            BloodFx.sound(world, altar, SoundEvents.BLOCK_END_PORTAL_SPAWN, 1.2f, 0.6f)
        }
        e.awaken()
        return ActionResult.SUCCESS
    }
}
