package dev.henny.hha_blood.boss

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.HeartVaultPiece
import dev.henny.hha_blood.logic.BloodDive
import dev.henny.hha_blood.logic.BloodFx
import dev.henny.hha_blood.logic.BloodHud
import dev.henny.hha_blood.logic.EnduringEffects
import dev.henny.hha_blood.logic.TrueInvis
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.sqrt

/**
 * Stage 4, "Das Labyrinth": a maze covering the fighting floor, with three-block corridors and seven-block
 * walls. The Minotaur's lair is the only open chamber. The blood of the pit takes in every fighter:
 * the HHA key "Ability 2" (H) lets them sink
 * into it like Blood Trail - even without the Blood armour - and the Minotaur no longer sees them. He hunts by
 * ear and nose instead (sprinting is loud, walking quiet, sneaking silent), taunts them, and a hit from the
 * blood is a backstab (x[BACKSTAB]). Blood Trail stays available throughout phase V.
 */
object PitLabyrinth {

    const val CELL = 4
    const val WALL_H = 7
    private const val N = 9
    const val INVIS_TICKS = 200
    const val COOLDOWN = 380
    const val BACKSTAB = 3f
    /** How long the maze stands (it is torn down by the next cutscene anyway). */
    private const val WALL_LIFE = 20 * 60 * 30

    val ABILITY_ID = HhaBlood.id("labyrinth_trail")

    var active = false
        private set
    private var clock = 0
    private val cells = ArrayList<Vec3d>()
    private val routeLengths = HashMap<Vec3d, Int>()
    private val hiddenUntil = HashMap<UUID, Int>()
    private val readyAt = HashMap<UUID, Int>()
    private val heardHint = HashMap<UUID, Int>()

    // ------------------------------------------------------------------------------------- layout

    /** Connected maze topology and the distance to the lair along its corridors, in blocks. */
    class Layout(val waves: List<List<BlockPos>>, val glow: Set<BlockPos>, val cells: List<Vec3d>,
                 val routes: Map<Vec3d, Int>, val deadEnds: Int)

    fun layout(seed: Long): Layout? {
        val c = PitArena.center() ?: return null
        val rng = java.util.Random(seed)
        val size = 2 * N + 1
        fun idx(i: Int, j: Int) = (i + N) * size + j + N
        val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val low = -CELL / 2
        val high = low + CELL
        val usable = HashSet<Pair<Int, Int>>()
        // Fill the whole fighting floor. Entire corridor footprints must fit around real pillars/altar.
        for (i in -N..N) for (j in -N..N) {
            val x = CELL * i
            val z = CELL * j
            val outerX = kotlin.math.abs(x) + high + 1.0
            val outerZ = kotlin.math.abs(z) + high + 1.0
            if (outerX * outerX + outerZ * outerZ > HeartVaultPiece.FIGHT_R * HeartVaultPiece.FIGHT_R) continue
            var clear = true
            for (dx in low + 1 until high) for (dz in low + 1 until high) for (dy in 0..3) {
                if (HeartVaultPiece.design(x + dx, dy, z + dz)?.isAir == false) clear = false
            }
            if (clear) usable += i to j
        }
        fun inCell(i: Int, j: Int) = (i to j) in usable
        fun canLink(i: Int, j: Int, ni: Int, nj: Int): Boolean {
            if (!inCell(ni, nj)) return false
            for (s in low + 1 until high) for (dy in 0..3) {
                val x = if (ni != i) CELL * i + (if (ni > i) high else low) else CELL * i + s
                val z = if (nj != j) CELL * j + (if (nj > j) high else low) else CELL * j + s
                if (HeartVaultPiece.design(x, dy, z)?.isAir == false) return false
            }
            return true
        }
        val edges = HashSet<Long>()
        fun edge(i: Int, j: Int, ni: Int, nj: Int): Long {
            val a = idx(i, j)
            val b = idx(ni, nj)
            return minOf(a, b).toLong() * size * size + maxOf(a, b)
        }
        fun open(i: Int, j: Int, ni: Int, nj: Int) { edges += edge(i, j, ni, nj) }
        fun isOpen(i: Int, j: Int, ni: Int, nj: Int) = edge(i, j, ni, nj) in edges
        val visited = HashSet<Pair<Int, Int>>()
        val stack = ArrayList<Pair<Int, Int>>()
        // Only the 11x11 lair is open. No radial rooms, exits, or circular bypass around the maze.
        for (i in -1..1) for (j in -1..1) if (inCell(i, j)) {
            if (i < 1 && inCell(i + 1, j)) open(i, j, i + 1, j)
            if (j < 1 && inCell(i, j + 1)) open(i, j, i, j + 1)
            visited += i to j
            stack += i to j
        }
        java.util.Collections.shuffle(stack, rng)
        while (stack.isNotEmpty()) {
            val (i, j) = stack.last()
            val next = dirs.shuffled(rng).map { (di, dj) -> i + di to j + dj }
                .firstOrNull { it in usable && it !in visited && canLink(i, j, it.first, it.second) }
            if (next == null) { stack.removeAt(stack.lastIndex); continue }
            open(i, j, next.first, next.second)
            visited += next
            stack += next
        }
        // Any pocket cut off by existing architecture is sealed, never a spawn or a false destination.
        usable.retainAll(visited)
        // Just a handful of loops; long branching routes and genuine dead ends stay intact.
        for ((i, j) in usable.sortedWith(compareBy({ it.first }, { it.second }))) for ((di, dj) in listOf(1 to 0, 0 to 1)) {
            if (canLink(i, j, i + di, j + dj) && rng.nextFloat() < 0.015f) open(i, j, i + di, j + dj)
        }
        val cols = HashSet<BlockPos>()
        val posts = HashSet<BlockPos>()
        for ((i, j) in usable) for ((di, dj) in dirs) {
            val gap = inCell(i + di, j + dj) && isOpen(i, j, i + di, j + dj)
            for (s in low..high) {
                val corner = s == low || s == high
                if (gap && !corner) continue
                val p = if (di != 0) BlockPos(CELL * i + (if (di > 0) high else low), 0, CELL * j + s)
                        else BlockPos(CELL * i + s, 0, CELL * j + (if (dj > 0) high else low))
                cols += p
                if (corner) posts += p
            }
        }
        // No isolated posts in the central lair.
        for (x in listOf(low, high)) for (z in listOf(low, high)) {
            cols -= BlockPos(x, 0, z)
            posts -= BlockPos(x, 0, z)
        }
        val floorY = HeartVaultPiece.FLOOR
        fun world(p: BlockPos) = BlockPos(c.x + p.x, floorY, c.z + p.z)
        fun centre(i: Int, j: Int) = Vec3d(c.x + CELL * i + 0.5, floorY.toDouble(), c.z + CELL * j + 0.5)
        val waves = cols.map(::world).groupBy {
            (sqrt(((it.x - c.x) * (it.x - c.x) + (it.z - c.z) * (it.z - c.z)).toDouble()) / 7.0).toInt()
        }.toSortedMap().values.toList()
        val distance = HashMap<Pair<Int, Int>, Int>()
        val queue = java.util.ArrayDeque<Pair<Int, Int>>()
        distance[0 to 0] = 0
        queue.add(0 to 0)
        while (queue.isNotEmpty()) {
            val (i, j) = queue.removeFirst()
            for ((di, dj) in dirs) {
                val n = i + di to j + dj
                if (n !in usable || n in distance || !isOpen(i, j, n.first, n.second)) continue
                distance[n] = distance.getValue(i to j) + CELL
                queue.add(n)
            }
        }
        check(distance.size == usable.size) { "Disconnected labyrinth: ${distance.size}/${usable.size}" }
        val routes = distance.mapKeys { (cell, _) -> centre(cell.first, cell.second) }
        val deadEnds = usable.count { (i, j) -> dirs.count { (di, dj) -> inCell(i + di, j + dj) && isOpen(i, j, i + di, j + dj) } == 1 }
        return Layout(waves, posts.filter { it in cols }.map(::world).toSet(), routes.keys.toList(), routes, deadEnds)
    }
    // ------------------------------------------------------------------------------------- stage

    /** Raises the maze (waves from the middle; [instant] for a skipped cutscene). */
    fun raise(world: ServerWorld, instant: Boolean) {
        val l = layout(world.random.nextLong()) ?: return
        cells.clear()
        cells += l.cells
        routeLengths.clear()
        routeLengths.putAll(l.routes)
        PitHazards.raiseMaze(world, l.waves, l.glow, WALL_H, WALL_LIFE, instant)
        HhaBlood.LOGGER.info("Slaughter Pit: labyrinth raised ({} columns, {} cells)", l.waves.sumOf { it.size }, l.cells.size)
        HhaBlood.LOGGER.info("[labyrinth] longest route={} blocks, dead ends={}", l.routes.values.maxOrNull(), l.deadEnds)
    }

    fun hasMaze(): Boolean = cells.isNotEmpty()

    /** Free spots (cell centres) far from [from], at least [spacing] apart - where the players are put. */
    fun spreadSpots(from: Vec3d, count: Int, minDist: Double, spacing: Double, rng: net.minecraft.util.math.random.Random): List<Vec3d> {
        val out = ArrayList<Vec3d>()
        val pool = cells.filter { it.squaredDistanceTo(from.x, it.y, from.z) > minDist * minDist }.shuffled(java.util.Random(rng.nextLong())).sortedByDescending { routeLengths[it] ?: 0 }
        for (p in pool) {
            if (out.size >= count) break
            if (out.all { it.squaredDistanceTo(p) > spacing * spacing }) out += p
        }
        while (out.size < count && pool.isNotEmpty()) out += pool[out.size % pool.size]
        return out
    }

    fun randomCell(rng: net.minecraft.util.math.random.Random, near: Vec3d? = null, within: Double = 999.0): Vec3d? {
        val list = if (near == null) cells else cells.filter { it.squaredDistanceTo(near.x, it.y, near.z) < within * within }
        if (list.isEmpty()) return cells.randomOrNull()
        return list[rng.nextInt(list.size)]
    }

    fun start(world: ServerWorld) {
        if (!active) {
            clock = 0
            hiddenUntil.clear()
            readyAt.clear()
            heardHint.clear()
        }
        active = true
        if (PitFight.stage == PitFight.LABYRINTH) {
            if (cells.isEmpty()) raise(world, true)
            for (p in PitFight.players(world)) BloodHud.show(p, "boss.hha_blood.laby.intro", BloodHud.RED, KEY, ticks = 120)
        } else {
            cells.clear()
            routeLengths.clear()
            for (p in PitFight.players(world)) BloodHud.show(p, "boss.hha_blood.trail.final", BloodHud.RED, KEY, ticks = 120)
        }
    }

    /** Stage over (cutscene, reset): everyone comes out of the blood. */
    fun stop(world: ServerWorld?) {
        if (world != null) for (id in hiddenUntil.keys.toList()) {
            val p = PitFight.players(world).firstOrNull { it.uuid == id } ?: continue
            reveal(world, p, false)
        }
        active = false
        hiddenUntil.clear()
        readyAt.clear()
        cells.clear()
        routeLengths.clear()
    }

    /** Placeholder the client turns into the bound key of HHA's "Ability 2". */
    const val KEY = "@key:key.hha.ability_2"

    fun canUse(p: PlayerEntity): Boolean =
        active && PitFight.stage in PitFight.LABYRINTH..PitFight.LAST_BULL && !PitFight.sceneRunning() && PitFight.targets(p.entityWorld as? ServerWorld ?: return false).contains(p)

    fun isHidden(p: PlayerEntity): Boolean =
        active && (hiddenUntil[p.uuid] ?: -1) > clock && p.hasStatusEffect(StatusEffects.INVISIBILITY)

    /** HHA ability (Ability 2 / H) during the labyrinth: sink into the blood. */
    fun cast(player: ServerPlayerEntity) {
        if (!canUse(player)) return
        val world = player.entityWorld as? ServerWorld ?: return
        val ready = readyAt[player.uuid] ?: 0
        if (ready > clock) {
            BloodHud.show(player, "boss.hha_blood.laby.cooldown", BloodHud.GRAY, ((ready - clock + 19) / 20).toString(), ticks = 30)
            return
        }
        if (isHidden(player)) return
        readyAt[player.uuid] = clock + COOLDOWN
        hiddenUntil[player.uuid] = clock + INVIS_TICKS
        EnduringEffects.withoutEnduring {
            player.addStatusEffect(StatusEffectInstance(StatusEffects.INVISIBILITY, INVIS_TICKS + 2, 0, false, false, true))
            player.addStatusEffect(StatusEffectInstance(StatusEffects.SPEED, INVIS_TICKS, 0, false, false, true))
        }
        if (!PitFight.isFake(player)) TrueInvis.start(player, INVIS_TICKS + 2)
        BloodDive.start(world, player, 50, INVIS_TICKS)
        val at = player.entityPos
        for (i in 0..4) BloodFx.ring(world, at.add(0.0, 2.0 - i * 0.4, 0.0), 1.2 - i * 0.12, BloodFx.dust(BloodFx.BRIGHT, 1.3f), 16)
        BloodFx.sound(world, at, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.5f)
        BloodFx.sound(world, at, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.8f, 0.5f)
        BloodHud.show(player, "boss.hha_blood.laby.hidden", BloodHud.RED, (INVIS_TICKS / 20).toString(), ticks = 40)
        PitFight.minotaur(world)?.onVanish(world, player)
        HhaBlood.LOGGER.info("[labyrinth] {} sank into the blood", player.name.string)
    }

    /** Out of the blood (attacked, found, stage over). */
    fun reveal(world: ServerWorld, p: PlayerEntity, burst: Boolean = true) {
        if (hiddenUntil.remove(p.uuid) == null) return
        if (p is ServerPlayerEntity && !PitFight.isFake(p)) TrueInvis.stop(p)
        if (p.hasStatusEffect(StatusEffects.INVISIBILITY)) p.removeStatusEffect(StatusEffects.INVISIBILITY)
        if (burst) {
            BloodFx.spawn(world, BloodFx.crumbs, p.entityPos.add(0.0, 1.0, 0.0), 24, 0.3, 0.6, 0.3, 0.2)
            BloodFx.sound(world, p.entityPos, SoundEvents.ENTITY_PLAYER_SPLASH, 0.9f, 0.6f)
        }
    }

    /**
     * What he hears from [from]: the loudest player within earshot (sprinting 13 blocks, walking 4.5,
     * sneaking or standing nothing). Hidden players' positions are a little off.
     */
    fun listen(world: ServerWorld, from: Vec3d): Vec3d? {
        var best: Vec3d? = null
        var bestScore = 0.0
        for (p in PitFight.targets(world)) {
            val v = p.velocity
            val speed = sqrt(v.x * v.x + v.z * v.z)
            val radius = when {
                p.isSneaking -> 0.0
                p.isSprinting && speed > 0.12 -> 13.0
                speed > 0.08 -> 4.5
                !p.isOnGround && v.y < -0.5 -> 6.0   // landing from a jump
                else -> 0.0
            }
            if (radius <= 0) continue
            val d = sqrt(p.squaredDistanceTo(from))
            if (d > radius) continue
            val score = radius - d
            if (score > bestScore) {
                bestScore = score
                best = if (isHidden(p)) p.entityPos.add(world.random.nextDouble() * 4 - 2, 0.0, world.random.nextDouble() * 4 - 2) else p.entityPos
                if (isHidden(p) && p is ServerPlayerEntity && (heardHint[p.uuid] ?: -999) < clock - 160) {
                    heardHint[p.uuid] = clock
                    PitFight.hint(p, "boss.hha_blood.hint.heard")
                }
            }
        }
        return best
    }

    /** The nearest player he could smell from [from] (sniffing), any state. */
    fun scent(world: ServerWorld, from: Vec3d, range: Double): PlayerEntity? =
        PitFight.targets(world).filter { it.squaredDistanceTo(from) < range * range }.minByOrNull { it.squaredDistanceTo(from) }

    fun tick(world: ServerWorld) {
        if (!active) return
        clock++
        // invisibility over (time, milk): forget
        val it = hiddenUntil.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val p = PitFight.players(world).firstOrNull { pl -> pl.uuid == e.key }
            if (p == null || e.value <= clock || !p.hasStatusEffect(StatusEffects.INVISIBILITY)) {
                it.remove()
                if (p != null && p.hasStatusEffect(StatusEffects.INVISIBILITY) && e.value <= clock) p.removeStatusEffect(StatusEffects.INVISIBILITY)
            }
        }
        if (clock % 20 != 0 || PitFight.sceneRunning()) return
        for (p in PitFight.players(world)) {
            if (PitFight.isFake(p)) continue
            val hiddenLeft = (hiddenUntil[p.uuid] ?: 0) - clock
            val ready = (readyAt[p.uuid] ?: 0) - clock
            when {
                hiddenLeft > 0 -> BloodHud.show(p, "boss.hha_blood.laby.hidden", BloodHud.RED, ((hiddenLeft + 19) / 20).toString(), ticks = 30)
                ready > 0 -> BloodHud.show(p, "boss.hha_blood.laby.cooldown", BloodHud.GRAY, ((ready + 19) / 20).toString(), ticks = 30)
                else -> BloodHud.show(p, "boss.hha_blood.laby.ready", BloodHud.RED, KEY, ticks = 30)
            }
        }
    }
}
