package dev.henny.hha_blood.client.hollows

import dev.henny.hha_blood.HhaBlood
import dev.henny.hha_blood.hollows.HollowsGen
import dev.henny.hha_blood.hollows.WatchingEyeBlock
import dev.henny.hha_blood.hollows.WatchingEyeBlockEntity
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.render.block.entity.BlockEntityRenderer
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState
import net.minecraft.client.render.command.ModelCommandRenderer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class WatchingEyeRenderState : BlockEntityRenderState() {
    var facing: Direction = Direction.NORTH
    /** Iris offset in model pixels (16 per block) along the face's right / up axes. */
    var gx = 0f
    var gy = 0f
    /** 0 = wide open, 1 = shut. */
    var closed = 0f
    /** Contracted slit pupil (the viewer is close). */
    var slit = false
    var faceLight = 0
}

/**
 * The eye of a [WatchingEyeBlock]: the block model has a recessed, bloodshot eye; this renderer draws the
 * glowing iris (it always looks at the viewer - every player sees the eyes watching HIM), and the two lids
 * that blink now and then, squint when you come close and shut when you are behind the wall.
 * Everything is computed from the camera position and the world time, no server data.
 */
class WatchingEyeRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererFactory.Context) :
    BlockEntityRenderer<WatchingEyeBlockEntity, WatchingEyeRenderState> {

    override fun createRenderState() = WatchingEyeRenderState()

    override fun updateRenderState(
        be: WatchingEyeBlockEntity, state: WatchingEyeRenderState, tickProgress: Float, cameraPos: Vec3d,
        crumbling: ModelCommandRenderer.CrumblingOverlayCommand?,
    ) {
        super.updateRenderState(be, state, tickProgress, cameraPos, crumbling)
        val bs = be.cachedState
        val facing = if (bs.contains(WatchingEyeBlock.FACING)) bs.get(WatchingEyeBlock.FACING) else Direction.NORTH
        state.facing = facing
        val world = be.world
        val pos = be.pos
        state.faceLight = if (world != null) WorldRenderer.getLightmapCoordinates(world, pos.offset(facing)) else LightmapTextureManager.MAX_LIGHT_COORDINATE

        val n = facing.doubleVector
        val up = upOf(facing)
        val right = up.crossProduct(n)
        val eye = Vec3d.ofCenter(pos).add(n.multiply(0.5))
        val v = cameraPos.subtract(eye)
        val lx = v.dotProduct(right)
        val ly = v.dotProduct(up)
        val lz = v.dotProduct(n)
        val dist = v.length()

        val time = (world?.time ?: 0L) + tickProgress.toDouble()
        val seed = HollowsGen.hash(pos.x, pos.y, pos.z, 77)
        var closed = 0.0
        if (lz < 0.15 || dist > 40.0) {
            // nobody in front of it: half asleep, the iris drifts around
            val drift = time / 37.0 + (seed and 255) * 0.1
            state.gx = (sin(drift) * 1.6).toFloat()
            state.gy = (sin(drift * 1.7 + 1.3) * 0.4).toFloat()
            closed = if (lz < 0.15) 1.0 else 0.35
        } else {
            state.gx = (2.5 * lx / sqrt(lx * lx + lz * lz + 0.6)).toFloat()
            state.gy = (0.6 * ly / sqrt(ly * ly + lz * lz + 0.6)).toFloat()
            // twitching saccades when the viewer stands still
            val tw = ((time / 9.0).toLong() * 31 + seed).let { HollowsGen.rand(it.toInt(), 3, 5, 11) }
            if (tw > 0.93) state.gx += ((tw - 0.965) * 30).toFloat()
            if (dist < 2.6) closed = 0.5                     // too close - it squints
        }
        // blinking: every 3.5 - 10 s for ~6 ticks
        val period = 70 + (seed ushr 8 and 127).toInt()
        val m = Math.floorMod((time + (seed ushr 16 and 1023)).toLong(), period.toLong()) + (time % 1.0)
        if (m < 6.0) closed = maxOf(closed, sin(m / 6.0 * PI))
        state.closed = closed.toFloat().coerceIn(0f, 1f)
        state.slit = dist < 5.0
    }

    override fun render(state: WatchingEyeRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, camera: CameraRenderState) {
        val n = state.facing.doubleVector
        val up = upOf(state.facing)
        val right = up.crossProduct(n)
        val c = Vec3d(0.5, 0.5, 0.5).add(n.multiply(0.5))
        val faceLight = state.faceLight
        val closed = state.closed
        val gx = state.gx
        val gy = state.gy
        val slit = state.slit
        queue.submitCustom(matrices, LAYER) { entry, vc ->
            // iris (glows in the dark), 7 x 7 px, behind both lids
            val iu = if (slit) 14f else 0f
            quad(vc, entry, c, right, up, n, -0.75, gx - 3.5, gy - 3.5, gx + 3.5, gy + 3.5,
                iu / 32f, 0f, (iu + 14f) / 32f, 14f / 32f, LightmapTextureManager.MAX_LIGHT_COORDINATE)
            // lids: 12 x 8 px each, the almond edge is in the texture; they meet in the middle when closed
            val yU = -4.0 * closed         // bottom edge of the upper lid (relative to the eye centre)
            quad(vc, entry, c, right, up, n, -0.4, -6.0, yU, 6.0, yU + 8.0, 0f, 14f / 32f, 24f / 32f, 30f / 32f, faceLight)
            val yL = 4.0 * closed          // top edge of the lower lid
            quad(vc, entry, c, right, up, n, -0.5, -6.0, yL - 8.0, 6.0, yL, 0f, 30f / 32f, 24f / 32f, 14f / 32f, faceLight)
        }
    }

    override fun getRenderDistance(): Int = 48

    companion object {
        private val LAYER: RenderLayer = RenderLayers.entityCutoutNoCull(HhaBlood.id("textures/entity/watching_eye.png"))

        fun upOf(f: Direction): Vec3d = when (f) {
            Direction.UP -> Vec3d(0.0, 0.0, -1.0)
            Direction.DOWN -> Vec3d(0.0, 0.0, 1.0)
            else -> Vec3d(0.0, 1.0, 0.0)
        }

        /**
         * A quad on the eye face. Coordinates in model pixels relative to the eye centre: x along [right],
         * y along [up], depth along the face normal (negative = into the block). UV: u0/v0 = the texture at
         * (x0, y1) i.e. top left, u1/v1 bottom right.
         */
        private fun quad(
            vc: VertexConsumer, e: MatrixStack.Entry, c: Vec3d, right: Vec3d, up: Vec3d, n: Vec3d, depth: Double,
            x0: Double, y0: Double, x1: Double, y1: Double, u0: Float, v0: Float, u1: Float, v1: Float, light: Int,
        ) {
            val px = 1.0 / 16.0
            fun v(x: Double, y: Double, u: Float, vv: Float) {
                val p = c.add(right.multiply(x * px)).add(up.multiply(y * px)).add(n.multiply(depth * px))
                vc.vertex(e, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).color(-1).texture(u, vv)
                    .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
            }
            v(x0, y1, u0, v0)
            v(x0, y0, u0, v1)
            v(x1, y0, u1, v1)
            v(x1, y1, u1, v0)
        }
    }
}
