package dev.henny.hha_blood.boss

import dev.henny.hha_blood.BloodItems
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.hollows.HollowsRegion
import dev.henny.hha_blood.logic.Bleed
import dev.henny.hha_blood.logic.BloodFx
import dev.henny.hha_blood.logic.BloodSounds
import dev.henny.hha_blood.logic.FxDisplays
import dev.henny.hha_blood.logic.NihilFx
import net.minecraft.entity.EntityType
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fürst Sanguin, the Blood Prince - phase 1 of the Heart Vault boss (the dragon Cruorax follows in
 * round 3). Waits on his throne until a player enters the arena, then fights with the Blood armour's
 * own powers: sword combos, a lunge, Bloodsaws, Blood Trail (dives into a blood pool, comes up behind
 * you), the Blood Cyclone above the arena and - when wounded - Nihil.
 *
 * No vanilla goals: a small state machine in [mobTick]; the current action + its start tick are synced
 * to the client for the animations (`client/boss/BloodPrinceModel`).
 */
class BloodPrinceEntity(type: EntityType<out BloodPrinceEntity>, world: World) : HostileEntity(type, world) {

    companion object {
        val ACTION: TrackedData<Int> = DataTracker.registerData(BloodPrinceEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        val ACTION_AGE: TrackedData<Int> = DataTracker.registerData(BloodPrinceEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        const val THRONE = 0
        const val IDLE = 1
        const val AWAKEN = 2
        const val SLASH = 3
        const val LUNGE = 4
        const val SAWS = 5
        const val DIVE = 6
        const val HIDDEN = 7
        const val EMERGE = 8
        const val CYCLONE = 9
        const val NIHIL = 10
        /** Phase 1 -> 2: kneels, the blood takes him, Cruorax rises (he does not die). */
        const val TRANSFORM = 11

        const val MAX_HEALTH = 450.0
        const val SCALE = 1.45f

        fun createAttributes(): DefaultAttributeContainer.Builder = createHostileAttributes()
            .add(EntityAttributes.MAX_HEALTH, MAX_HEALTH)
            .add(EntityAttributes.ATTACK_DAMAGE, 12.0)
            .add(EntityAttributes.ARMOR, 14.0)
            .add(EntityAttributes.ARMOR_TOUGHNESS, 8.0)
            .add(EntityAttributes.MOVEMENT_SPEED, 0.3)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 64.0)
            .add(EntityAttributes.STEP_HEIGHT, 1.1)
    }

    val bossBar: ServerBossBar = ServerBossBar(Text.translatable("entity.hha_blood.blood_prince").formatted(Formatting.DARK_RED), BossBar.Color.RED, BossBar.Style.NOTCHED_10)
        .also { it.setDarkenSky(true); it.setThickenFog(true) } as ServerBossBar

    private var ticks = 0
    private var foe: PlayerEntity? = null
    private var globalCd = 40
    private var meleeCd = 0
    private var lungeCd = 60
    private var sawsCd = 100
    private var diveCd = 200
    private var cycloneCd = 260
    private var nihilCd = 300
    private var combo = 0
    private var enraged = false
    private var lungeDir = Vec3d.ZERO
    private val lungeHit = HashSet<java.util.UUID>()
    private var pool: DisplayEntity.ItemDisplayEntity? = null
    private var emergeAt: Vec3d? = null
    private var nihilVictims: List<PlayerEntity> = emptyList()

    init {
        experiencePoints = 250
        setPersistent()
        for (slot in EquipmentSlot.entries) setEquipmentDropChance(slot, 0f)
    }

    // ------------------------------------------------------------------------------------- state sync

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(ACTION, THRONE)
        builder.add(ACTION_AGE, 0)
    }

    val action: Int get() = dataTracker.get(ACTION)

    /** Ticks since the current action started (client: smooth with [tickDelta]). */
    fun actionTime(tickDelta: Float): Float = age - dataTracker.get(ACTION_AGE) + tickDelta

    /** Not rendered at all while under the floor. */
    val hiddenForRender: Boolean get() = action == HIDDEN

    private fun setAction(a: Int) {
        dataTracker.set(ACTION, a)
        dataTracker.set(ACTION_AGE, age)
        ticks = 0
    }

    fun equipPrince() {
        // only the sword - his own robe/crown is the look (Mats: the armour on him looked odd)
        for (slot in arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) equipStack(slot, ItemStack.EMPTY)
        equipStack(EquipmentSlot.MAINHAND, ItemStack(BloodItems.BLOOD_SWORD))
    }

    override fun writeCustomData(view: WriteView) {
        super.writeCustomData(view)
        view.putBoolean("Enraged", enraged)
        view.putBoolean("OnThrone", action == THRONE)
    }

    override fun readCustomData(view: ReadView) {
        super.readCustomData(view)
        enraged = view.getBoolean("Enraged", false)
        // a fight that was interrupted by unloading restarts from the throne - fully healed
        setAction(THRONE)
        health = maxHealth
        enraged = false
        if (hasCustomName()) bossBar.name = displayName
    }

    // ------------------------------------------------------------------------------------- basics

    override fun cannotDespawn(): Boolean = true
    override fun canImmediatelyDespawn(distanceSquared: Double): Boolean = false
    override fun canBreatheInWater(): Boolean = true
    override fun isPushable(): Boolean = action != THRONE && action != HIDDEN
    override fun canUsePortals(allowVehicles: Boolean): Boolean = false

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        if (source.isOf(DamageTypes.OUT_OF_WORLD) || source.isOf(DamageTypes.GENERIC_KILL)) return super.damage(world, source, amount)
        if (action == THRONE || action == AWAKEN || action == HIDDEN || action == DIVE || action == TRANSFORM) return false
        if (action == EMERGE && ticks < 10) return false
        if (source.isIn(DamageTypeTags.IS_FALL) || source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.DROWN) ||
            source.isOf(DamageTypes.CRAMMING)) return false
        // the killing blow does not kill him - it starts the transformation into Cruorax
        if (amount >= health) {
            super.damage(world, source, 0.01f)
            health = 1f
            startTransform(world)
            return true
        }
        return super.damage(world, source, amount)
    }

    override fun updatePostDeath() {
        deathTime++
        val w = entityWorld
        if (w is ServerWorld) {
            val body = entityPos.add(0.0, 1.0, 0.0)
            BloodFx.spawn(w, BloodFx.crumbs, body, 10, 0.6, 0.6, 0.6, 0.2)
            BloodFx.spawn(w, BloodFx.flame, body, 4, 0.5, 0.8, 0.5, 0.02)
            if (deathTime % 10 == 0) BloodFx.sound(w, body, SoundEvents.BLOCK_BELL_RESONATE, 1.5f, 0.5f)
            if (deathTime >= 60 && !isRemoved) {
                BloodFx.spawn(w, ParticleTypes.EXPLOSION_EMITTER, body, 1)
                BloodFx.sound(w, body, SoundEvents.ENTITY_WITHER_DEATH, 1.0f, 0.6f)
                w.sendEntityStatus(this, 60.toByte())
                remove(RemovalReason.KILLED)
            }
        }
    }

    override fun onDeath(damageSource: DamageSource) {
        super.onDeath(damageSource)
        cleanup()
        bossBar.isVisible = false
        (entityWorld as? ServerWorld)?.let { HeartVaultArena.onPrinceFallen(it, entityPos) }
    }

    override fun onRemoved() {
        super.onRemoved()
        cleanup()
        bossBar.clearPlayers()
    }

    private fun cleanup() {
        FxDisplays.remove(pool); pool = null
        PrinceAttacks.clearOwner(uuid)
    }

    // ------------------------------------------------------------------------------------- arena

    private fun center(): BlockPos = HollowsRegion.center() ?: blockPos

    /** Where he sits (feet position; seat block top minus the sitting offset). */
    fun throneSeat(): Vec3d {
        val c = center()
        return Vec3d(c.x + 0.5, HeartVaultPiece.FLOOR + 4 - 0.85, c.z - 25.0 - HeartVaultPiece.THRONE_SHIFT)
    }

    /** Called by the arena when a player steps in. */
    fun awaken() {
        if (action != THRONE) return
        setAction(AWAKEN)
    }

    /** Players fled or died: heal, back to the throne. */
    fun resetToThrone() {
        cleanup()
        health = maxHealth
        enraged = false
        noClip = false
        setAction(THRONE)
        val s = throneSeat()
        refreshPositionAndAngles(s.x, s.y, s.z, 0f, 0f)
        velocity = Vec3d.ZERO
        navigation.stop()
    }

    // ------------------------------------------------------------------------------------- AI

    override fun mobTick(world: ServerWorld) {
        super.mobTick(world)
        ticks++
        globalCd--; meleeCd--; lungeCd--; sawsCd--; diveCd--; cycloneCd--; nihilCd--
        bossBar.percent = health / maxHealth
        when (action) {
            THRONE -> holdThrone()
            AWAKEN -> tickAwaken(world)
            IDLE -> tickIdle(world)
            SLASH -> tickSlash(world)
            LUNGE -> tickLunge(world)
            SAWS -> tickSaws(world)
            DIVE -> tickDive(world)
            HIDDEN -> tickHidden(world)
            EMERGE -> tickEmerge(world)
            CYCLONE -> tickCyclone(world)
            NIHIL -> tickNihil(world)
            TRANSFORM -> tickTransform(world)
        }
        if (!enraged && action != THRONE && action != TRANSFORM && health < maxHealth * 0.5f) enrage(world)
        // wades through the blood lake instead of sinking to its bottom (no swim goal - we have no goals)
        if (!noClip && action != THRONE && action != TRANSFORM && getFluidHeight(net.minecraft.registry.tag.FluidTags.WATER) > 1.3) {
            velocity = velocity.add(0.0, 0.09, 0.0)
            if (ticks % 6 == 0) BloodFx.spawn(world, BloodFx.crumbs, entityPos.add(0.0, 1.6, 0.0), 4, 0.6, 0.1, 0.6, 0.1)
        }
        // ambient blood dripping from the armour
        if (age % 4 == 0 && action != HIDDEN) BloodFx.spawn(world, BloodFx.crumbs, entityPos.add(0.0, 1.4, 0.0), 1, 0.35, 0.6, 0.35, 0.0)
    }

    private fun cd(t: Int): Int = if (enraged) (t * 0.65).toInt() else t

    private fun holdThrone() {
        val s = throneSeat()
        setNoGravity(true)
        noClip = false
        refreshPositionAndAngles(s.x, s.y, s.z, 0f, 0f)
        setHeadYaw(0f); bodyYaw = 0f
        velocity = Vec3d.ZERO
        navigation.stop()
    }

    private fun tickAwaken(world: ServerWorld) {
        val c = center()
        if (ticks < 22) {
            holdThrone()
            if (ticks == 1) {
                val armour = HeartVaultArena.playersInArena(world).any { BloodPrinceLines.wearsBlood(it) }
                BloodPrinceLines.title(world, this)
                BloodPrinceLines.say(world, if (armour) "awaken_armor" else "awaken")
                BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 2.5f, 0.55f)
                BloodFx.sound(world, entityPos, SoundEvents.BLOCK_BELL_RESONATE, 3.0f, 0.5f)
            }
            BloodFx.spawn(world, BloodFx.flame, entityPos.add(0.0, 1.0, 0.0), 2, 0.6, 0.8, 0.6, 0.01)
            return
        }
        if (ticks == 22) {
            setNoGravity(false)
            refreshPositionAndAngles(c.x + 0.5, HeartVaultPiece.FLOOR + 3.0, c.z - 22.5 - HeartVaultPiece.THRONE_SHIFT, 0f, 0f)
        }
        if (ticks == 40) {
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_RAVAGER_ROAR, 3.0f, 0.6f)
            BloodFx.shake(world, entityPos, 40.0, 1.8f, 20)
            BloodFx.ring(world, entityPos.add(0.0, 0.2, 0.0), 6.0, BloodFx.flame, 48)
            for (p in HeartVaultArena.playersInArena(world)) {
                if (p.squaredDistanceTo(this) < 100.0) push(p, p.entityPos.subtract(entityPos).normalize().multiply(1.2).add(0.0, 0.5, 0.0))
            }
        }
        if (ticks >= 60) setAction(IDLE)
    }

    private fun pickFoe(world: ServerWorld): PlayerEntity? {
        val list = HeartVaultArena.playersInArena(world).filter { it.isAlive && !it.isSpectator }
        val f = list.minByOrNull { it.squaredDistanceTo(this) }
        foe = f
        return f
    }

    private fun tickIdle(world: ServerWorld) {
        setNoGravity(false); noClip = false
        val f = pickFoe(world) ?: run { navigation.stop(); return }
        val dist = distanceTo(f).toDouble()
        if (globalCd <= 0 && chooseSpecial(world, f, dist)) return
        if (dist < 3.9 && meleeCd <= 0) { combo = 0; navigation.stop(); setAction(SLASH); return }
        if (dist > 3.2) {
            if (ticks % 8 == 1 || navigation.isIdle) navigation.startMovingTo(f, if (enraged) 1.35 else 1.15)
        } else {
            // close enough: stand still and just face the target (walking "into" the player made him
            // spin around - the move target was right under him)
            navigation.stop()
            face(f.entityPos, 18f)
        }
    }

    private fun chooseSpecial(world: ServerWorld, f: PlayerEntity, dist: Double): Boolean {
        val hp = health / maxHealth
        val options = ArrayList<Pair<Int, Int>>()   // action, weight
        if (nihilCd <= 0 && hp < 0.72f) options += NIHIL to 5
        if (cycloneCd <= 0 && hp < 0.88f && PrinceAttacks.cycloneCount(uuid) == 0) options += CYCLONE to 4
        if (diveCd <= 0) options += DIVE to 3
        if (sawsCd <= 0 && dist > 4.5) options += SAWS to 4
        if (lungeCd <= 0 && dist in 5.5..18.0) options += LUNGE to 4
        if (options.isEmpty()) return false
        var roll = random.nextInt(options.sumOf { it.second })
        val pick = options.first { roll -= it.second; roll < 0 }.first
        navigation.stop()
        setAction(pick)
        globalCd = cd(70)
        when (pick) {
            NIHIL -> nihilCd = cd(700)
            CYCLONE -> cycloneCd = cd(560)
            DIVE -> diveCd = cd(320)
            SAWS -> sawsCd = cd(220)
            LUNGE -> lungeCd = cd(150)
        }
        return true
    }

    /** Sword combo: wind-up, a wide slash in front of him; sometimes chains up to 3 hits. */
    private fun tickSlash(world: ServerWorld) {
        val f = foe
        if (f != null && ticks < 8) face(f.entityPos, 20f)
        if (ticks == 6) swingHand(Hand.MAIN_HAND)
        if (ticks == 8) {
            val fwd = Vec3d.fromPolar(0f, yaw).normalize()
            var hit = false
            for (p in HeartVaultArena.playersInArena(world)) {
                val to = p.entityPos.subtract(entityPos)
                val flat = Vec3d(to.x, 0.0, to.z)
                if (flat.length() > 4.4 || kotlin.math.abs(to.y) > 3.0) continue
                if (flat.length() > 0.8 && flat.normalize().dotProduct(fwd) < 0.2) continue
                hit = true
                hurt(world, p, attackDamage(), 100)
                push(p, flat.normalize().multiply(0.7).add(0.0, 0.25, 0.0))
            }
            val front = entityPos.add(fwd.multiply(2.2)).add(0.0, 1.4, 0.0)
            BloodFx.spawn(world, ParticleTypes.SWEEP_ATTACK, front, 3, 1.0, 0.2, 1.0, 0.0)
            BloodFx.spawn(world, BloodFx.crumbs, front, 12, 1.0, 0.3, 1.0, 0.2)
            BloodFx.sound(world, front, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.6f)
            if (hit) BloodFx.sound(world, front, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.7f)
        }
        if (ticks >= 15) {
            combo++
            if (combo < 3 && f != null && distanceTo(f) < 4.6 && random.nextFloat() < 0.45f) { setAction(SLASH); return }
            meleeCd = cd(18)
            setAction(IDLE)
        }
    }

    /** Crouch, then dash through the target. */
    private fun tickLunge(world: ServerWorld) {
        val f = foe
        if (ticks < 12) {
            navigation.stop()
            if (f != null) face(f.entityPos, 30f)
            BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.4f), entityPos.add(0.0, 0.2, 0.0), 3, 0.8, 0.1, 0.8, 0.02)
            if (ticks == 2) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_RAVAGER_STUNNED, 1.0f, 1.4f)
            return
        }
        if (ticks == 12) {
            val to = (f?.entityPos ?: entityPos.add(Vec3d.fromPolar(0f, yaw))).subtract(entityPos)
            lungeDir = Vec3d(to.x, 0.0, to.z).normalize()
            lungeHit.clear()
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_BREEZE_JUMP, 1.6f, 0.5f)
            BloodFx.sound(world, entityPos, SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), 1.6f, 0.7f)
        }
        if (ticks in 12..22) {
            velocity = Vec3d(lungeDir.x * 1.45, velocity.y.coerceAtMost(0.1), lungeDir.z * 1.45)
            yaw = (atan2(-lungeDir.x, lungeDir.z) * 180 / PI).toFloat(); bodyYaw = yaw; headYaw = yaw
            BloodFx.spawn(world, BloodFx.fade(BloodFx.BRIGHT, BloodFx.BLACK, 1.6f), entityPos.add(0.0, 1.2, 0.0), 4, 0.3, 0.6, 0.3, 0.0)
            for (p in HeartVaultArena.playersInArena(world)) {
                if (p.squaredDistanceTo(this) < 4.0 && lungeHit.add(p.uuid)) {
                    if (ticks == 14) swingHand(Hand.MAIN_HAND)
                    hurt(world, p, attackDamage() + 3f, 80)
                    push(p, lungeDir.multiply(1.3).add(0.0, 0.45, 0.0))
                    BloodFx.wound(world, p.entityPos.add(0.0, 1.0, 0.0), 2)
                }
            }
        }
        if (ticks == 23) velocity = velocity.multiply(0.2, 1.0, 0.2)
        if (ticks >= 32) setAction(IDLE)
    }

    /** Two spinning blood saws thrown at the target (bounce off walls). */
    private fun tickSaws(world: ServerWorld) {
        val f = foe
        if (f != null) face(f.entityPos, 20f)
        if (ticks == 2) BloodFx.sound(world, entityPos, SoundEvents.ITEM_TRIDENT_RETURN, 1.2f, 0.6f)
        if (ticks == 13) {
            swingHand(Hand.MAIN_HAND)
            val from = entityPos.add(0.0, 1.6, 0.0)
            val aim = (f?.eyePos ?: from.add(Vec3d.fromPolar(0f, yaw))).subtract(from).normalize()
            val count = if (enraged) 3 else 2
            for (i in 0 until count) {
                val spread = (i - (count - 1) / 2.0) * 0.32
                PrinceAttacks.saw(world, this, from, aim.rotateY(spread.toFloat()), i)
            }
            BloodFx.sound(world, from, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.5f)
            BloodFx.sound(world, from, SoundEvents.ITEM_TRIDENT_THROW.value(), 1.4f, 0.8f)
        }
        if (ticks >= 26) setAction(IDLE)
    }

    /** Blood Trail: sinks into a pool of blood ... */
    private fun tickDive(world: ServerWorld) {
        navigation.stop()
        if (ticks == 1) {
            pool = FxDisplays.spawn(world, entityPos.add(0.0, 0.03, 0.0), Items.PAPER, "blood_pool", Quaternionf(), 0.5f, teleportTicks = 0)
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.5f, 0.5f)
            BloodFx.sound(world, entityPos, BloodSounds.BLEED_OPEN, 1.5f, 0.6f)
        }
        pool?.let { FxDisplays.update(it, it.entityPos, Quaternionf(), (0.5f + ticks / 20f * 3.5f).coerceAtMost(4f), 1) }
        setNoGravity(true); noClip = true
        velocity = Vec3d.ZERO
        if (ticks > 6) setPosition(x, y - 0.17, z)
        BloodFx.spawn(world, BloodFx.crumbs, entityPos.add(0.0, 0.6, 0.0), 6, 0.6, 0.2, 0.6, 0.15)
        if (ticks >= 22) {
            FxDisplays.remove(pool); pool = null
            setAction(HIDDEN)
        }
    }

    /** ... travels under the floor towards the target (a trail of bubbling blood shows where) ... */
    private fun tickHidden(world: ServerWorld) {
        val f = foe ?: pickFoe(world)
        velocity = Vec3d.ZERO
        if (f != null) {
            val ground = f.y
            val to = Vec3d(f.x - x, 0.0, f.z - z)
            val step = if (to.length() > 1.2) to.normalize().multiply(0.9) else Vec3d.ZERO
            setPosition(x + step.x, ground - 2.6, z + step.z)
            BloodFx.spawn(world, BloodFx.crumbs, Vec3d(x, ground + 0.1, z), 5, 0.4, 0.05, 0.4, 0.12)
            BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.8f), Vec3d(x, ground + 0.05, z), 2, 0.3, 0.0, 0.3, 0.0)
            if (ticks % 6 == 0) BloodFx.sound(world, Vec3d(x, ground, z), SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 1.2f, 0.6f)
        }
        if (ticks >= 30) {
            // come up behind the target
            val t = f
            val at = if (t != null) {
                val back = Vec3d.fromPolar(0f, t.yaw).multiply(-2.4)
                Vec3d(t.x + back.x, t.y, t.z + back.z)
            } else Vec3d(x, center().y.toDouble(), z)
            emergeAt = at
            setPosition(at.x, at.y - 2.6, at.z)
            if (t != null) { val d = t.entityPos.subtract(at); yaw = (atan2(-d.x, d.z) * 180 / PI).toFloat(); bodyYaw = yaw; headYaw = yaw }
            pool = FxDisplays.spawn(world, at.add(0.0, 0.03, 0.0), Items.PAPER, "blood_pool", Quaternionf(), 3.5f, teleportTicks = 0)
            BloodFx.sound(world, at, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.8f, 0.6f)
            setAction(EMERGE)
        }
    }

    /** ... and bursts out of it with an uppercut. */
    private fun tickEmerge(world: ServerWorld) {
        val at = emergeAt ?: entityPos
        velocity = Vec3d.ZERO
        if (ticks <= 10) setPosition(at.x, at.y - 2.6 + 2.6 * ticks / 10.0, at.z)
        if (ticks == 10) {
            setNoGravity(false); noClip = false
            swingHand(Hand.MAIN_HAND)
            for (p in HeartVaultArena.playersInArena(world)) {
                if (p.squaredDistanceTo(at) < 3.4 * 3.4) {
                    hurt(world, p, attackDamage() + 2f, 120)
                    push(p, Vec3d(0.0, 1.05, 0.0).add(p.entityPos.subtract(at).normalize().multiply(0.4)))
                }
            }
            BloodFx.spawn(world, BloodFx.crumbs, at.add(0.0, 0.5, 0.0), 50, 0.8, 1.2, 0.8, 0.45)
            BloodFx.spawn(world, BloodFx.flame, at.add(0.0, 0.5, 0.0), 20, 0.6, 1.0, 0.6, 0.05)
            BloodFx.sound(world, at, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.6f, 0.5f)
            BloodFx.sound(world, at, BloodSounds.SPIKE_IMPALE, 1.4f, 1.2f)
            BloodFx.shake(world, at, 16.0, 1.2f, 10)
        }
        if (ticks >= 18) {
            FxDisplays.remove(pool); pool = null
            setAction(IDLE)
        }
    }

    /** Raises the sword - a giant blood mace rises above the arena and rains blood bolts. */
    private fun tickCyclone(world: ServerWorld) {
        if (ticks < 20) BloodFx.spawn(world, BloodFx.flame, entityPos.add(0.0, 3.6, 0.0), 2, 0.2, 0.2, 0.2, 0.02)
        if (ticks == 2) BloodFx.sound(world, entityPos, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, 2.0f, 0.5f)
        if (ticks == 20) {
            swingHand(Hand.MAIN_HAND)
            val c = center()
            PrinceAttacks.cyclone(world, this, Vec3d(c.x + 0.5, HeartVaultPiece.FLOOR + 0.0, c.z + 0.5), if (enraged) 200 else 160)
            BloodFx.shake(world, entityPos, 40.0, 1.0f, 10)
            BloodFx.sound(world, entityPos, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, 2.0f, 0.6f)
        }
        if (ticks >= 34) setAction(IDLE)
    }

    /** Nihil: pulls everyone in, three drain beats, then a blood thorn impales the closest victim. */
    private fun tickNihil(world: ServerWorld) {
        navigation.stop()
        velocity = Vec3d(0.0, velocity.y, 0.0)
        if (ticks == 1) {
            nihilVictims = HeartVaultArena.playersInArena(world).filter { it.squaredDistanceTo(this) < 22.0 * 22.0 }
            BloodPrinceLines.say(world, "nihil")
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 2.5f, 0.5f)
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.5f)
        }
        val center = entityPos.add(0.0, 1.5, 0.0)
        if (ticks in 8..70) {
            for (p in nihilVictims) {
                if (!p.isAlive) continue
                val to = center.subtract(p.entityPos)
                val flat = Vec3d(to.x, 0.0, to.z)
                val pull = if (flat.length() > 3.0) flat.normalize().multiply(0.16) else Vec3d.ZERO
                val vy = ((center.y + 2.5 - p.y) * 0.08).coerceIn(-0.1, 0.18)
                push(p, Vec3d(pull.x, vy, pull.z))
                if (ticks % 3 == 0) BloodFx.vein(world, p.entityPos.add(0.0, 1.0, 0.0), center, ticks / 10.0, 4)
            }
            BloodFx.spawn(world, BloodFx.flame, center, 3, 1.0, 1.0, 1.0, 0.02)
        }
        if (ticks == 25 || ticks == 42 || ticks == 58) {
            var drained = 0f
            for (p in nihilVictims) {
                if (!p.isAlive) continue
                p.timeUntilRegen = 0
                if (hurt(world, p, 5f, 60)) drained += 5f
                BloodFx.ring(world, p.entityPos.add(0.0, 0.1, 0.0), 1.6, BloodFx.flame, 20)
            }
            heal(drained * 2f)
            BloodFx.sound(world, entityPos, SoundEvents.BLOCK_BELL_USE, 3.0f, 0.5f + ticks / 120f)
            BloodFx.sound(world, entityPos, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.4f)
            BloodFx.ring(world, entityPos.add(0.0, 0.2, 0.0), 4.0 + ticks / 15.0, BloodFx.flame, 40)
        }
        if (ticks == 62) {
            nihilVictims.filter { it.isAlive }.minByOrNull { it.squaredDistanceTo(this) }?.let {
                NihilFx.impale(world, this, it, 12, 26, if (enraged) 11f else 9f)
            }
        }
        if (ticks >= 95) setAction(IDLE)
    }

    /** Turns body + head towards [p] by at most [step] degrees per tick (no jitter, no spinning). */
    private fun face(p: Vec3d, step: Float) {
        val d = p.subtract(entityPos)
        if (d.horizontalLengthSquared() < 0.01) return
        val want = (atan2(-d.x, d.z) * 180 / PI).toFloat()
        val y = MathHelper.stepUnwrappedAngleTowards(yaw, want, step)
        yaw = y; bodyYaw = y; headYaw = y
    }

    fun startTransform(world: ServerWorld) {
        if (action == TRANSFORM) return
        cleanup()
        navigation.stop()
        velocity = Vec3d.ZERO
        setAction(TRANSFORM)
        BloodPrinceLines.say(world, "transform")
        BloodFx.sound(world, entityPos, SoundEvents.ENTITY_WITHER_HURT, 2.5f, 0.4f)
        BloodFx.sound(world, entityPos, SoundEvents.BLOCK_BELL_RESONATE, 3.0f, 0.4f)
    }

    /** Kneels, a blood vortex lifts him, a thorn of his own blood pierces him, he bursts - Cruorax forms. */
    private fun tickTransform(world: ServerWorld) {
        velocity = Vec3d.ZERO
        bossBar.percent = 0f
        val body = entityPos.add(0.0, 1.4, 0.0)
        val k = ticks / 80.0
        for (i in 0 until 4) {
            val a = ticks * 0.35 + i * PI / 2
            val r = 3.5 - k * 2.5
            BloodFx.spawn(world, BloodFx.flame, body.add(cos(a) * r, -1.2 + k * 3.0 + i * 0.3, sin(a) * r), 1, 0.05)
            BloodFx.spawn(world, BloodFx.dust(BloodFx.BRIGHT, 1.8f), body.add(cos(a + 1) * r, -1.0 + k * 2.0, sin(a + 1) * r), 1, 0.05)
        }
        if (ticks > 20) {
            setNoGravity(true)
            setPosition(x, y + 0.03, z)
        }
        if (ticks % 12 == 0) BloodFx.sound(world, body, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 4.0f, 0.5f + ticks / 160f)
        if (ticks == 50) {
            BloodFx.sound(world, body, BloodSounds.SPIKE_IMPALE, 3.0f, 0.6f)
            BloodFx.spawn(world, BloodFx.crumbs, body, 60, 0.5, 0.8, 0.5, 0.4)
            BloodFx.screenFlash(world, body, 70.0, 0xC00010, 0.5f, 10)
            BloodFx.shake(world, body, 70.0, 2.0f, 20)
        }
        if (ticks >= 80 && !isRemoved) {
            BloodFx.spawn(world, ParticleTypes.EXPLOSION_EMITTER, body, 1)
            BloodFx.spawn(world, BloodFx.crumbs, body, 150, 1.5, 1.5, 1.5, 0.6)
            BloodFx.sound(world, body, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 3.0f, 0.5f)
            bossBar.clearPlayers()
            HeartVaultArena.onPrinceFallen(world, entityPos)
            discard()
        }
    }

    private fun enrage(world: ServerWorld) {
        enraged = true
        BloodPrinceLines.say(world, "enrage")
        BloodFx.sound(world, entityPos, SoundEvents.ENTITY_RAVAGER_ROAR, 3.0f, 0.5f)
        BloodFx.screenFlash(world, entityPos, 40.0, 0x900010, 0.4f, 12)
        BloodFx.ring(world, entityPos.add(0.0, 0.2, 0.0), 8.0, BloodFx.flame, 60)
        getAttributeInstance(EntityAttributes.MOVEMENT_SPEED)?.baseValue = 0.36
        globalCd = 20
    }

    // ------------------------------------------------------------------------------------- helpers

    private fun attackDamage(): Float = (getAttributeValue(EntityAttributes.ATTACK_DAMAGE) * if (enraged) 1.2 else 1.0).toFloat()

    /** Damage a player as the Prince (bleeding for [bleedTicks]). */
    fun hurt(world: ServerWorld, p: PlayerEntity, amount: Float, bleedTicks: Int): Boolean {
        val ok = p.damage(world, world.damageSources.mobAttack(this), amount)
        if (ok && bleedTicks > 0) Bleed.apply(p, durationTicks = bleedTicks, source = this)
        return ok
    }

    fun push(p: PlayerEntity, v: Vec3d) {
        p.velocity = p.velocity.multiply(0.3).add(v)
        p.knockedBack = true
        (p as? ServerPlayerEntity)?.let { it.networkHandler.sendPacket(net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(it)) }
    }

    @Suppress("unused") private fun yawTo(v: Vec3d) = MathHelper.wrapDegrees((atan2(-v.x, v.z) * 180 / PI).toFloat())
    @Suppress("unused") private fun circle(r: Double, a: Double) = Vec3d(cos(a) * r, 0.0, sin(a) * r)
}
