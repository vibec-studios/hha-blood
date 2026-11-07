package dev.henny.hha_blood.client.hollows

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.PitHeartBlockEntity
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.block.entity.BlockEntityRenderer
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState
import net.minecraft.client.render.command.ModelCommandRenderer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PitHeartRenderState : BlockEntityRenderState() {
    /** Beat phase 0..1 (0 = the "lub"). */
    var phase = 0f
    var time = 0f
}

/**
 * The giant heart of the Slaughter Pit: a voxel sculpture (1/3-block voxels, built once from a signed distance
 * field: two ventricles, atria, apex, the aortic arch, great vessels that grow into the dome, coronary arteries
 * and loose dangling vessel ends). Every beat (see [Heartbeat], the same clock plays the sound) it contracts
 * in a "lub-dub", flushes from inside and its coronary arteries flare up; a bulge runs up the vessels to the
 * ceiling. It sways a little on its vessels.
 */
class PitHeartRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererFactory.Context) :
    BlockEntityRenderer<PitHeartBlockEntity, PitHeartRenderState> {

    override fun createRenderState() = PitHeartRenderState()

    override fun updateRenderState(
        be: PitHeartBlockEntity, state: PitHeartRenderState, tickProgress: Float, cameraPos: Vec3d,
        crumbling: ModelCommandRenderer.CrumblingOverlayCommand?,
    ) {
        super.updateRenderState(be, state, tickProgress, cameraPos, crumbling)
        val p = Heartbeat.phaseAt(tickProgress)
        state.phase = (p - floor(p)).toFloat()
        state.time = ((be.world?.time ?: 0L) % 24000L).toFloat() + tickProgress
    }

    override fun render(state: PitHeartRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, camera: CameraRenderState) {
        val mesh = HeartMesh.mesh
        val ph = state.phase.toDouble()
        // lub (strong) + dub (weaker) contraction
        val c = pulse(ph, 0.0) + 0.6 * pulse(ph, 0.17)
        val vesselWave = ph * 1.6            // the bulge runs from the heart to the ceiling during the beat
        val swayX = sin(state.time * 0.021) * 0.018
        val swayZ = cos(state.time * 0.017) * 0.014
        val flush = (10.5 + 4.5 * c).toInt().coerceIn(0, 15)
        val fleshLight = LightmapTextureManager.pack(flush, 0)
        val glow = (0.55 + 0.45 * min(1.0, c)).toFloat()
        matrices.push()
        matrices.translate(0.5f, 0.5f, 0.5f)
        queue.submitCustom(matrices, LAYER) { e, vc ->
            val v = mesh.verts
            val kk = HeartMesh.K
            val cx = HeartMesh.CENTER_X * kk; val cy = HeartMesh.CENTER_Y * kk; val cz = HeartMesh.CENTER_Z * kk
            var i = 0
            var q = 0
            while (q < mesh.quads) {
                val color = mesh.color[q]
                val emissive = mesh.emissive[q]
                val nx = mesh.nx[q]; val ny = mesh.ny[q]; val nz = mesh.nz[q]
                val tu = mesh.u[q]; val tv = mesh.v[q]
                val col = if (emissive) scale(color, glow) else color
                val light = if (emissive) LightmapTextureManager.MAX_LIGHT_COORDINATE else fleshLight
                for (k in 0 until 4) {
                    var x = v[i].toDouble(); var y = v[i + 1].toDouble(); var z = v[i + 2].toDouble()
                    i += 3
                    // contraction of the body (weight 1 below y -4.5, fading out towards the ceiling)
                    val w = smooth(-2.5 * kk, -5.0 * kk, y)
                    if (w > 0.0) {
                        val sh = 1.0 - 0.075 * c * w
                        val sv = 1.0 - 0.04 * c * w
                        x = cx + (x - cx) * sh
                        y = cy + (y - cy) * sv
                        z = cz + (z - cz) * sh
                    }
                    // pulse wave up the great vessels
                    var vc2 = col
                    if (y > -5.0 * kk) {
                        val along = (y + 5.0 * kk) / (6.5 * kk)
                        val d = along - vesselWave
                        val g = exp(-d * d * 30.0) * (1.0 - min(1.0, along))
                        val b = 0.09 * g
                        x += nx * b; y += ny * b; z += nz * b
                        if (mesh.vessel[q]) vc2 = scale(col, (1.0 + 2.2 * g).toFloat())
                    }
                    // swaying on the vessels (pivot at the ceiling)
                    val s = -y
                    x += swayX * s
                    z += swayZ * s
                    val uu = tu + (if (k == 1 || k == 2) 0.25f else 0f)
                    val vv = tv + (if (k >= 2) 0.25f else 0f)
                    vc.vertex(e, x.toFloat(), y.toFloat(), z.toFloat()).color(vc2).texture(uu, vv)
                        .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz)
                }
                q++
            }
        }
        matrices.pop()
    }

    override fun rendersOutsideBoundingBox(): Boolean = true
    override fun getRenderDistance(): Int = 160

    companion object {
        private val LAYER: RenderLayer = RenderLayers.entitySolid(HhaBlood.id("textures/entity/pit_heart.png"))

        private fun pulse(ph: Double, at: Double): Double {
            var d = ph - at
            if (d < -0.5) d += 1.0
            if (d > 0.5) d -= 1.0
            // fast attack, slower release
            return if (d < 0) exp(-d * d / (0.025 * 0.025)) else exp(-d * d / (0.07 * 0.07))
        }

        private fun smooth(e0: Double, e1: Double, x: Double): Double {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        private fun scale(argb: Int, f: Float): Int {
            val r = (((argb shr 16) and 255) * f).toInt().coerceIn(0, 255)
            val g = (((argb shr 8) and 255) * f).toInt().coerceIn(0, 255)
            val b = ((argb and 255) * f).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/** The voxel mesh of the heart (built once, lazily). Coordinates in blocks relative to the anchor block centre. */
object HeartMesh {
    const val CENTER_X = 0.1
    const val CENTER_Y = -8.6
    const val CENTER_Z = 0.1
    private const val S = 0.4
    /** Scale of the sculpture (the distance field is modelled at 1.0). */
    const val K = 1.4

    class Mesh(
        val quads: Int, val verts: FloatArray, val color: IntArray, val emissive: BooleanArray, val vessel: BooleanArray,
        val nx: FloatArray, val ny: FloatArray, val nz: FloatArray, val u: FloatArray, val v: FloatArray,
    )

    val mesh: Mesh by lazy { build() }

    // --------------------------------------------------------------------------------- distance field

    private class Tube(val pts: List<DoubleArray>, val r0: Double, val r1: Double)

    /** Quadratic bezier from a over b to c, sampled. */
    private fun bez(a: DoubleArray, b: DoubleArray, c: DoubleArray, n: Int = 10): List<DoubleArray> = (0..n).map { k ->
        val t = k.toDouble() / n
        val m = 1 - t
        DoubleArray(3) { i -> m * m * a[i] + 2 * m * t * b[i] + t * t * c[i] }
    }

    private fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

    private val TUBES = listOf(
        // aorta: up out of the heart, arching over to the right and down behind it
        Tube(bez(p(0.3, -6.0, 0.4), p(0.1, -2.2, 0.6), p(1.9, -2.3, 0.3)) + bez(p(1.9, -2.3, 0.3), p(3.2, -2.5, 0.0), p(3.0, -5.2, -1.0)), 0.95, 0.75),
        // arteries of the arch, into the dome
        Tube(bez(p(0.6, -2.7, 0.5), p(0.4, -0.8, 0.9), p(-0.4, 1.3, 1.6)), 0.5, 0.65),
        Tube(bez(p(1.5, -2.4, 0.4), p(2.0, -0.7, 0.8), p(2.9, 1.3, 1.9)), 0.45, 0.6),
        Tube(bez(p(2.4, -2.5, 0.2), p(3.4, -1.0, -0.2), p(4.1, 1.3, -0.8)), 0.4, 0.55),
        // vena cava (left back) and pulmonary trunk (front right), both grow into the ceiling
        Tube(bez(p(-2.0, -5.4, 1.2), p(-2.9, -2.0, 1.9), p(-3.3, 1.3, 3.1)), 0.8, 0.85),
        Tube(bez(p(1.0, -5.6, -1.1), p(1.4, -2.6, -2.4), p(1.0, 1.3, -3.6)), 0.85, 0.8),
        Tube(bez(p(-1.2, -5.8, -1.4), p(-2.6, -2.8, -2.4), p(-3.6, 1.3, -1.8)), 0.6, 0.7),
        // torn, dangling vessel ends
        Tube(bez(p(-2.6, -7.5, -1.2), p(-3.8, -8.6, -1.9), p(-3.6, -10.6, -2.4)), 0.42, 0.22),
        Tube(bez(p(2.9, -8.8, 1.1), p(4.0, -9.4, 1.8), p(3.7, -11.4, 1.7)), 0.38, 0.2),
    )

    private fun ell(x: Double, y: Double, z: Double, cx: Double, cy: Double, cz: Double, rx: Double, ry: Double, rz: Double): Double {
        val dx = (x - cx) / rx; val dy = (y - cy) / ry; val dz = (z - cz) / rz
        return (sqrt(dx * dx + dy * dy + dz * dz) - 1.0) * min(rx, min(ry, rz))
    }

    private fun smin(a: Double, b: Double, k: Double): Double {
        val h = (0.5 + 0.5 * (b - a) / k).coerceIn(0.0, 1.0)
        return b + (a - b) * h - k * h * (1 - h)
    }

    private fun tubeDist(t: Tube, x: Double, y: Double, z: Double): Double {
        var best = Double.MAX_VALUE
        val n = t.pts.size
        for (i in 0 until n - 1) {
            val a = t.pts[i]; val b = t.pts[i + 1]
            val abx = b[0] - a[0]; val aby = b[1] - a[1]; val abz = b[2] - a[2]
            val apx = x - a[0]; val apy = y - a[1]; val apz = z - a[2]
            val l2 = abx * abx + aby * aby + abz * abz
            val h = if (l2 == 0.0) 0.0 else ((apx * abx + apy * aby + apz * abz) / l2).coerceIn(0.0, 1.0)
            val dx = apx - abx * h; val dy = apy - aby * h; val dz = apz - abz * h
            val r = (t.r0 + (t.r1 - t.r0) * ((i + h) / (n - 1))) * 1.2
            best = min(best, sqrt(dx * dx + dy * dy + dz * dz) - r)
        }
        return best
    }

    /** The heart body (tilted 16 degrees, apex down and a little to the left). */
    private fun body(x0: Double, y0: Double, z0: Double): Double {
        val a = Math.toRadians(16.0)
        val ox = x0 - CENTER_X; val oy = y0 - CENTER_Y
        val x = CENTER_X + ox * cos(a) - oy * sin(a)
        val y = CENTER_Y + ox * sin(a) + oy * cos(a)
        val z = z0
        var d = smin(ell(x, y, z, -0.95, -8.4, 0.0, 2.9, 3.3, 2.6), ell(x, y, z, 1.05, -8.0, 0.25, 2.7, 3.0, 2.5), 1.3)
        d = smin(d, ell(x, y, z, 0.15, -11.2, 0.1, 1.5, 2.2, 1.4), 1.6)
        val atria = smin(ell(x, y, z, -1.9, -5.6, 0.9, 1.5, 1.25, 1.4), ell(x, y, z, 1.9, -5.7, -0.6, 1.4, 1.15, 1.3), 0.6)
        d = smin(d, atria, 0.9)
        // the groove between the ventricles
        val groove = abs((x - 0.1) * 0.9 + z * 0.35 - (y + 8.0) * 0.12)
        d += 0.18 * exp(-groove * groove * 6.0)
        return d
    }

    private fun field(x: Double, y: Double, z: Double): Double {
        var d = body(x, y, z)
        for (t in TUBES) d = smin(d, tubeDist(t, x, y, z), 0.35)
        return d
    }

    // --------------------------------------------------------------------------------- colours

    private fun hash(x: Int, y: Int, z: Int, s: Int): Double {
        var h = x * 73856093L xor (y * 19349663L) xor (z * 83492791L) xor (s * 2654435761L)
        h = h xor (h ushr 13); h *= 0x5bd1e995L; h = h xor (h ushr 15)
        return ((h and 0xffffff).toDouble()) / 0xffffff.toDouble()
    }

    private fun vnoise(x: Double, y: Double, z: Double, s: Int): Double {
        val ix = floor(x).toInt(); val iy = floor(y).toInt(); val iz = floor(z).toInt()
        val fx = x - ix; val fy = y - iy; val fz = z - iz
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy); val uz = fz * fz * (3 - 2 * fz)
        fun l(a: Double, b: Double, t: Double) = a + (b - a) * t
        return l(
            l(l(hash(ix, iy, iz, s), hash(ix + 1, iy, iz, s), ux), l(hash(ix, iy + 1, iz, s), hash(ix + 1, iy + 1, iz, s), ux), uy),
            l(l(hash(ix, iy, iz + 1, s), hash(ix + 1, iy, iz + 1, s), ux), l(hash(ix, iy + 1, iz + 1, s), hash(ix + 1, iy + 1, iz + 1, s), ux), uy),
            uz,
        )
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun mixc(a: Int, b: Int, t: Double): Int {
        val tt = t.coerceIn(0.0, 1.0)
        fun ch(s: Int) = (((a shr s) and 255) + ((((b shr s) and 255) - ((a shr s) and 255)) * tt)).toInt()
        return rgb(ch(16), ch(8), ch(0))
    }

    private const val FLESH_DK = 0x6a1018
    private const val FLESH = 0xa01f24
    private const val FLESH_HI = 0xc8423f
    private const val FAT = 0xc9a46c
    private const val VESSEL = 0x5a1022
    private const val VESSEL_HI = 0x7d2033
    private const val ARTERY = 0xff3a2e

    /** Colour of a surface voxel; emissive = glowing coronary artery. */
    private fun colour(x: Double, y: Double, z: Double, onBody: Boolean): Pair<Int, Boolean> {
        if (!onBody) {
            // vessels: dark, with lighter rings every half block
            val ring = abs(sin(y * 5.5 + x * 1.3)) > 0.93
            val n = vnoise(x * 2.0, y * 2.0, z * 2.0, 5)
            return (if (ring) mixc(VESSEL_HI, FLESH_HI, 0.3) else mixc(VESSEL, VESSEL_HI, n)) to false
        }
        // coronary arteries: thin ridges of a 3D noise, more of them on the top
        val a = vnoise(x * 0.9 + 3.1, y * 0.55, z * 0.9 - 1.7, 11)
        val b = vnoise(x * 1.7 - 2.0, y * 1.1 + 4.0, z * 1.7, 12)
        if (abs(a - 0.5) < 0.06 || (abs(b - 0.5) < 0.035 && y > -10.0)) return mixc(ARTERY, 0xffb07a, abs(b - 0.5) * 6) to true
        // fat on top of the heart (around the atria / the groove)
        val fat = vnoise(x * 1.3, y * 1.3, z * 1.3, 13)
        if (y > -7.0 && fat > 0.74 - (y + 7.0) * 0.05) return mixc(FAT, 0xe7cf98, vnoise(x * 4, y * 4, z * 4, 14)) to false
        val n = vnoise(x * 1.6, y * 1.6, z * 1.6, 15) * 0.65 + vnoise(x * 4.0, y * 4.0, z * 4.0, 16) * 0.35
        val base = if (n < 0.45) mixc(FLESH_DK, FLESH, n / 0.45) else mixc(FLESH, FLESH_HI, (n - 0.45) / 0.55)
        return base to false
    }

    // --------------------------------------------------------------------------------- voxels -> quads

    private fun build(): Mesh {
        val x0 = -6.0 * K; val y0 = -14.5 * K; val z0 = -5.5 * K
        val nx = Math.ceil(11.0 * K / S).toInt(); val ny = Math.ceil(16.4 * K / S).toInt(); val nz = Math.ceil(11.0 * K / S).toInt()
        val solid = Array(nx) { Array(ny) { BooleanArray(nz) } }
        val onBody = Array(nx) { Array(ny) { BooleanArray(nz) } }
        for (i in 0 until nx) for (j in 0 until ny) for (k in 0 until nz) {
            val x = x0 + (i + 0.5) * S; val y = y0 + (j + 0.5) * S; val z = z0 + (k + 0.5) * S
            val f = field(x / K, y / K, z / K)
            if (f < 0) {
                solid[i][j][k] = true
                onBody[i][j][k] = body(x / K, y / K, z / K) < 0.05
            }
        }
        fun filled(i: Int, j: Int, k: Int) = i in 0 until nx && j in 0 until ny && k in 0 until nz && solid[i][j][k]

        val verts = ArrayList<Float>()
        val colors = ArrayList<Int>(); val em = ArrayList<Boolean>(); val vessel = ArrayList<Boolean>()
        val nxs = ArrayList<Float>(); val nys = ArrayList<Float>(); val nzs = ArrayList<Float>()
        val us = ArrayList<Float>(); val vs = ArrayList<Float>()
        // faces: normal, then the 4 corners (unit cube offsets) counter-clockwise seen from outside
        val faces = arrayOf(
            intArrayOf(1, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1),
            intArrayOf(-1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0),
            intArrayOf(0, 1, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0),
            intArrayOf(0, -1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1),
            intArrayOf(0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 0, 0, 1),
            intArrayOf(0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0),
        )
        for (i in 0 until nx) for (j in 0 until ny) for (k in 0 until nz) {
            if (!solid[i][j][k]) continue
            val cx = x0 + (i + 0.5) * S; val cy = y0 + (j + 0.5) * S; val cz = z0 + (k + 0.5) * S
            // hidden inside the dome ceiling (the vessels grow into it)
            if (cy > 0.55) continue
            var col = -1
            var emissive = false
            for (f in faces) {
                if (filled(i + f[0], j + f[1], k + f[2])) continue
                if (f[1] == 1 && cy + S > 0.5) continue
                if (col == -1) {
                    val (c, e) = colour(cx / K, cy / K, cz / K, onBody[i][j][k])
                    col = c; emissive = e
                }
                for (q in 0 until 4) {
                    verts += (x0 + (i + f[3 + q * 3]) * S).toFloat()
                    verts += (y0 + (j + f[4 + q * 3]) * S).toFloat()
                    verts += (z0 + (k + f[5 + q * 3]) * S).toFloat()
                }
                colors += col; em += emissive; vessel += !onBody[i][j][k]
                nxs += f[0].toFloat(); nys += f[1].toFloat(); nzs += f[2].toFloat()
                val h = hash(i, j, k, f[0] * 3 + f[1] * 5 + f[2] * 7 + 20)
                us += ((h * 4).toInt() * 0.25f).coerceAtMost(0.75f)
                vs += ((hash(k, i, j, 31) * 4).toInt() * 0.25f).coerceAtMost(0.75f)
            }
        }
        val n = colors.size
        HhaBlood.LOGGER.info("Pit heart mesh: {} quads", n)
        return Mesh(n, verts.toFloatArray(), colors.toIntArray(), em.toBooleanArray(), vessel.toBooleanArray(), nxs.toFloatArray(), nys.toFloatArray(), nzs.toFloatArray(),
            us.toFloatArray(), vs.toFloatArray())
    }

    @Suppress("unused")
    private fun clampInt(v: Int, lo: Int, hi: Int) = max(lo, min(hi, v))
}
