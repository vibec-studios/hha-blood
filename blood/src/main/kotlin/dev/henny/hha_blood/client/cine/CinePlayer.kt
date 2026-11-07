package dev.henny.hha_blood.client.cine

import dev.henny.hha_blood.boss.BossSounds
import dev.henny.hha_blood.boss.Cine
import dev.henny.hha_blood.boss.CinePayload
import dev.henny.hha_blood.boss.CineSkipPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.Perspective
import net.minecraft.client.sound.MovingSoundInstance
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.entity.Entity
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plays the cutscenes the server sends (see boss/Cine): camera moves, letterbox, a dialogue box with
 * typewriter text and growling voice blips, fades, title cards, the boss music (crossfaded) and the
 * skip vote. While a scene runs the HUD is hidden and the input is locked (client mixins).
 */
object CinePlayer {
    private val HERMES_VOICE: net.minecraft.util.Identifier = net.minecraft.util.Identifier.of("hha", "boss.voice")

    private sealed class Shot(val ticks: Int, val fovFrom: Double, val fovTo: Double, val lookAtId: Int, val lookOffset: Double)

    private class LineShot(
        val from: Vec3d, val to: Vec3d, val lookFrom: Vec3d, val lookTo: Vec3d,
        ticks: Int, fovFrom: Double, fovTo: Double, lookAtId: Int, lookOffset: Double,
    ) : Shot(ticks, fovFrom, fovTo, lookAtId, lookOffset)

    private class OrbitShot(
        val center: Vec3d, val radiusFrom: Double, val radiusTo: Double, val heightFrom: Double, val heightTo: Double,
        val angleFrom: Double, val angleTo: Double,
        ticks: Int, fovFrom: Double, fovTo: Double, lookAtId: Int, lookOffset: Double,
    ) : Shot(ticks, fovFrom, fovTo, lookAtId, lookOffset)

    private class Line(val speaker: Int, val text: Text, val ticks: Int) {
        var age = 0
    }

    private class TitleCard(val title: Text, val subtitle: Text, val ticks: Int, val color: Int) {
        var age = 0
    }

    /** A cutscene is running: input locked, camera replaced, HUD hidden. */
    @JvmStatic
    var active = false
        private set

    private var skippable = false
    private var voted = false
    private var jumpWasDown = false
    /** Ticks the jump key has been held towards a skip vote (needs [HOLD_TO_SKIP]). */
    private var holdTicks = 0f
    private const val HOLD_TO_SKIP = 60f
    private var votes = 0
    private var votesNeeded = 0

    private var shot: Shot? = null
    private var shotAge = 0
    private var line: Line? = null
    private var title: TitleCard? = null
    private var previousPerspective: Perspective? = null
    private var bars = 0f

    private var fadeColor = 0
    private var fadeFrom = 0.0
    private var fadeTo = 0.0
    private var fadeTicks = 0
    private var fadeAge = 0

    private var paralyzedTicks = 0
    private var paralyzedMax = 1

    /** Paralysed by the Cerberus howl: movement input is frozen (the server holds the player too). */
    @JvmStatic
    fun isParalyzed(): Boolean = paralyzedTicks > 0

    fun handle(payload: CinePayload) {
        val n = payload.numbers
        when (payload.op) {
            Cine.OP_START -> begin(n.getOrElse(0) { 0.0 } > 0.5)
            Cine.OP_END -> finish()
            Cine.OP_RESET -> reset()
            Cine.OP_CAMERA -> {
                shot = LineShot(
                    Vec3d(n[0], n[1], n[2]), Vec3d(n[3], n[4], n[5]), Vec3d(n[6], n[7], n[8]), Vec3d(n[9], n[10], n[11]),
                    n[12].toInt(), n[13], n[14], n[15].toInt(), n[16],
                )
                shotAge = 0
            }
            Cine.OP_ORBIT -> {
                shot = OrbitShot(
                    Vec3d(n[0], n[1], n[2]), n[3], n[4], n[5], n[6], n[7], n[8],
                    n[9].toInt(), n[10], n[11], n[12].toInt(), n[13],
                )
                shotAge = 0
            }
            Cine.OP_DIALOG -> {
                val text = Text.translatable(payload.texts[0])
                // never close the box before the text is fully typed + a moment to read it
                val needed = (text.string.length / CHARS_PER_TICK).toInt() + 26
                line = Line(n[0].toInt(), text, maxOf(n[1].toInt(), needed))
            }
            Cine.OP_FADE -> {
                fadeColor = n[0].toInt()
                fadeFrom = n[1]
                fadeTo = n[2]
                fadeTicks = n[3].toInt().coerceAtLeast(1)
                fadeAge = 0
            }
            Cine.OP_TITLE -> title = TitleCard(
                Text.translatable(payload.texts[0]), Text.translatable(payload.texts[1]), n[0].toInt(), n.getOrElse(1) { 0xE0202A.toDouble() }.toInt(),
            )
            Cine.OP_MUSIC -> setTheme(payload.texts[0], n.getOrElse(0) { 1.0 } > 0.5)
            Cine.OP_SKIP_STATE -> { votes = n[0].toInt(); votesNeeded = n[1].toInt() }
            Cine.OP_PARALYZED -> { paralyzedTicks = n[0].toInt(); paralyzedMax = paralyzedTicks.coerceAtLeast(1) }
        }
    }

    // ------------------------------------------------------------------------------------- music

    /** Boss theme: a non-positional sound in the music category that fades in and out. */
    private class ThemeSound(event: SoundEvent, loop: Boolean) :
        MovingSoundInstance(event, SoundCategory.MUSIC, SoundInstance.createRandom()) {
        var target = 1f
        init {
            repeat = loop
            repeatDelay = 0
            relative = true
            attenuationType = SoundInstance.AttenuationType.NONE
            volume = 0.01f
        }

        override fun tick() {
            volume = if (volume < target) minOf(target, volume + 0.08f) else maxOf(target, volume - 0.035f)
            if (target <= 0f && volume <= 0.001f) setDone()
        }

        fun stop() { target = 0f }

        override fun shouldAlwaysPlay(): Boolean = true
    }

    private var theme: ThemeSound? = null
    private var themeId = ""

    private fun setTheme(id: String, loop: Boolean) {
        if (id == themeId && theme != null && id.isNotEmpty()) return
        val client = MinecraftClient.getInstance()
        theme?.stop()
        theme = null
        themeId = id
        if (id.isEmpty()) return
        val event = Registries.SOUND_EVENT.get(Identifier.of(id)) ?: return
        client.musicTracker.stop()
        theme = ThemeSound(event, loop).also { client.soundManager.play(it) }
    }

    // ------------------------------------------------------------------------------------- state

    private fun begin(canSkip: Boolean) {
        val client = MinecraftClient.getInstance()
        if (!active) previousPerspective = client.options.perspective
        client.options.perspective = Perspective.THIRD_PERSON_BACK
        active = true
        skippable = canSkip
        voted = false
        votes = 0
        votesNeeded = 0
        jumpWasDown = true   // a jump that is still held from the fight does not count
        holdTicks = 0f
        shot = null
    }

    private fun finish() {
        val client = MinecraftClient.getInstance()
        previousPerspective?.let { client.options.perspective = it }
        previousPerspective = null
        active = false
        skippable = false
        shot = null
        line = null
    }

    fun reset() {
        finish()
        setTheme("", true)
        title = null
        fadeTicks = 0
        fadeFrom = 0.0
        fadeTo = 0.0
        bars = 0f
        paralyzedTicks = 0
    }

    fun tick() {
        val client = MinecraftClient.getInstance()
        // vanilla (biome) music must not play over the boss theme
        if (theme != null) client.musicTracker.stop()
        if (theme?.isDone == true) theme = null
        bars = (bars + if (active) 0.08f else -0.08f).coerceIn(0f, 1f)
        if (shot != null) shotAge++
        line?.let {
            val before = visibleChars(it)
            it.age++
            val after = visibleChars(it)
            if (after > before && after < it.text.string.length && after % 3 == 0) blip(it.speaker, it.text.string[after - 1])
            if (it.age > it.ticks + 8) line = null
        }
        title?.let {
            it.age++
            if (it.age > it.ticks) title = null
        }
        if (fadeAge < fadeTicks) fadeAge++
        if (paralyzedTicks > 0) paralyzedTicks--
        // skip vote: hold jump for 3 seconds (a jump still held from the fight has to be released first)
        val jumpDown = client.options.jumpKey.isPressed
        if (!jumpDown) jumpWasDown = false
        if (active && skippable && !voted && client.player != null) {
            holdTicks = if (jumpDown && !jumpWasDown) holdTicks + 1f else (holdTicks - 4f).coerceAtLeast(0f)
            if (holdTicks >= HOLD_TO_SKIP) {
                voted = true
                holdTicks = 0f
                ClientPlayNetworking.send(CineSkipPayload)
            }
        } else holdTicks = 0f
    }

    private fun visibleChars(l: Line): Int = (l.age * CHARS_PER_TICK).toInt().coerceAtMost(l.text.string.length)

    /**
     * Syllable sounds while a line types out (letters only, never on spaces): the Minotaur growls, Hermes uses
     * HHA's own messenger voice (only if HHA ships that sound).
     */
    private fun blip(speaker: Int, c: Char) {
        if (!c.isLetter()) return
        val client = MinecraftClient.getInstance()
        if (speaker == Cine.SPEAKER_HERMES) {
            if (client.soundManager.get(HERMES_VOICE) == null) return
            val p = 1.05f + (System.nanoTime() % 5L) * 0.04f
            client.soundManager.play(PositionedSoundInstance.ui(net.minecraft.sound.SoundEvent.of(HERMES_VOICE), p, 0.45f))
            return
        }
        if (speaker != Cine.SPEAKER_MINOTAUR) return
        val pitch = 0.82f + (System.nanoTime() % 7L) * 0.035f
        client.soundManager.play(PositionedSoundInstance.ui(BossSounds.MINOTAUR_VOICE, pitch, 0.55f))
    }

    // ------------------------------------------------------------------------------------- camera

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun shotProgress(tickDelta: Float): Float {
        val s = shot ?: return 0f
        return ease((shotAge + tickDelta) / s.ticks.coerceAtLeast(1))
    }

    /** Called at the end of Camera.update; position + view of the cutscene camera, or null. */
    @JvmStatic
    fun cameraPose(tickDelta: Float): CameraPose? {
        val s = shot ?: return null
        if (!active) return null
        val world = MinecraftClient.getInstance().world ?: return null
        val t = shotProgress(tickDelta).toDouble()
        val lookEntity: Entity? = if (s.lookAtId >= 0) world.getEntityById(s.lookAtId) else null
        val pos: Vec3d
        val look: Vec3d
        when (s) {
            is LineShot -> {
                pos = s.from.lerp(s.to, t)
                look = lookEntity?.getLerpedPos(tickDelta)?.add(0.0, s.lookOffset, 0.0) ?: s.lookFrom.lerp(s.lookTo, t)
            }
            is OrbitShot -> {
                val a = Math.toRadians(MathHelper.lerp(t, s.angleFrom, s.angleTo))
                val r = MathHelper.lerp(t, s.radiusFrom, s.radiusTo)
                val h = MathHelper.lerp(t, s.heightFrom, s.heightTo)
                pos = s.center.add(cos(a) * r, h, sin(a) * r)
                look = lookEntity?.getLerpedPos(tickDelta)?.add(0.0, s.lookOffset, 0.0) ?: s.center.add(0.0, s.lookOffset, 0.0)
            }
        }
        val d = look.subtract(pos)
        val yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))).toFloat()
        return CameraPose(pos, yaw, pitch)
    }

    class CameraPose(@JvmField val pos: Vec3d, @JvmField val yaw: Float, @JvmField val pitch: Float)

    /** Field of view during a camera move. */
    @JvmStatic
    fun fov(vanilla: Float, tickDelta: Float): Float {
        val s = shot ?: return vanilla
        if (!active) return vanilla
        return MathHelper.lerp(shotProgress(tickDelta).toDouble(), s.fovFrom, s.fovTo).toFloat()
    }

    // ------------------------------------------------------------------------------------- overlay

    /** Letterbox, dialogue, fade, title card, skip hint, paralysis. */
    @JvmStatic
    fun renderOverlay(context: DrawContext, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        val width = client.window.scaledWidth
        val height = client.window.scaledHeight
        if (paralyzedTicks > 0) renderParalysis(context, width, height, tickDelta)
        val barHeight = (height * 0.11f * ease(bars)).toInt()
        if (bars > 0f) {
            context.fill(0, 0, width, barHeight, 0xFF000000.toInt())
            context.fill(0, height - barHeight, width, height, 0xFF000000.toInt())
        }
        line?.let { renderLine(context, it, width, height) }
        title?.let { renderTitle(context, it, width, height, tickDelta) }
        if (active && skippable && bars > 0.5f) renderSkipHint(context, width, height, barHeight)
        if (fadeTicks > 0) {
            val t = ((fadeAge + tickDelta) / fadeTicks).coerceIn(0f, 1f).toDouble()
            val alpha = MathHelper.lerp(t, fadeFrom, fadeTo).toFloat()
            if (alpha > 0.001f) {
                context.fill(0, 0, width, height, ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (fadeColor and 0xFFFFFF))
            }
        }
    }

    private fun renderSkipHint(context: DrawContext, width: Int, height: Int, barHeight: Int) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val key = client.options.jumpKey.boundKeyLocalizedText
        val text = when {
            voted && votesNeeded > 1 -> Text.translatable("cine.hha_blood.skip_votes", votes, votesNeeded)
            voted -> Text.translatable("cine.hha_blood.skipping")
            else -> Text.translatable("cine.hha_blood.skip", key)
        }
        val w = font.getWidth(text)
        val x = width - w - 10
        val y = height - barHeight / 2 - 6
        val holding = !voted && holdTicks > 0f
        context.drawTextWithShadow(font, text, x, y, if (holding) 0xF0E8D8D8.toInt() else 0xB0A8A8A8.toInt())
        // hold progress: thin bar under the hint, fills blood red
        val by = y + font.fontHeight + 2
        context.fill(x, by, x + w, by + 2, 0x60303030)
        val p = if (voted) 1f else (holdTicks / HOLD_TO_SKIP).coerceIn(0f, 1f)
        if (p > 0f) context.fill(x, by, x + (w * p).toInt().coerceAtLeast(1), by + 2, 0xE0C01818.toInt())
    }

    private fun renderLine(context: DrawContext, l: Line, width: Int, height: Int) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val fadeOut = ((l.ticks + 8 - l.age) / 8f).coerceIn(0f, 1f)
        val a = ((l.age / 4f).coerceAtMost(1f) * fadeOut).coerceIn(0.02f, 1f)
        val alpha = (a * 255f).toInt() shl 24
        val full = l.text.string
        val shown = full.substring(0, visibleChars(l))
        val boxWidth = (width * 0.7f).toInt().coerceAtMost(400)
        val lines = font.wrapLines(Text.literal(shown), boxWidth)
        val fullLines = font.wrapLines(l.text, boxWidth).size
        val speaker = when (l.speaker) {
            Cine.SPEAKER_MINOTAUR -> Text.translatable("entity.hha_blood.crimson_minotaur") to 0xE0202A
            Cine.SPEAKER_CERBERUS -> Text.translatable("entity.hha_blood.blood_cerberus") to 0xFF7A2A
            Cine.SPEAKER_HERMES -> Text.translatable("cine.hha_blood.speaker.hermes") to 0xF2C14E
            else -> null
        }
        val boxHeight = 10 * fullLines + if (speaker != null) 20 else 8
        val x = (width - boxWidth) / 2
        val bottom = if (active) height - (height * 0.11f).toInt() - 8 else height - 64
        val y = bottom - boxHeight
        val boxAlpha = ((a * 0.7f) * 255f).toInt() shl 24
        context.fill(x - 10, y - 5, x + boxWidth + 10, y + boxHeight, boxAlpha or 0x0A0204)
        val accent = speaker?.second ?: 0x8A8478
        context.fill(x - 10, y - 5, x - 8, y + boxHeight, alpha or accent)
        var textY = y
        if (speaker != null) {
            context.drawTextWithShadow(font, speaker.first, x, textY, alpha or speaker.second)
            textY += 12
        }
        val color = if (l.speaker == Cine.SPEAKER_NARRATOR) 0xC8C0B4 else 0xF2E8E4
        // the Minotaur's shouts tremble a little
        val shout = l.speaker == Cine.SPEAKER_MINOTAUR && full.isNotEmpty() && full.count { it.isUpperCase() } > full.count { it.isLetter() } / 2
        for (wrapped in lines) {
            val dx = if (shout) (l.age * 7 % 3) - 1 else 0
            context.drawTextWithShadow(font, wrapped, x + dx, textY, alpha or color)
            textY += 10
        }
    }

    private fun renderTitle(context: DrawContext, card: TitleCard, width: Int, height: Int, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val age = card.age + tickDelta
        val a = minOf(age / 10f, (card.ticks - age) / 14f).coerceIn(0f, 1f)
        val alpha = (a * 255f).toInt().coerceIn(4, 255) shl 24
        val scale = 3f
        val grow = 1f + (1f - (age / 14f).coerceIn(0f, 1f)) * 0.2f
        val cy = height * 0.36f
        val bandTop = (cy - 24f).toInt()
        context.fill(0, bandTop, width, bandTop + 62, ((a * 0.55f * 255f).toInt() shl 24))
        val matrices = context.matrices
        matrices.pushMatrix()
        matrices.translate(width / 2f, cy)
        matrices.scale(scale * grow, scale * grow)
        val titleWidth = font.getWidth(card.title)
        context.drawTextWithShadow(font, card.title, -titleWidth / 2, -4, alpha or (card.color and 0xFFFFFF))
        matrices.popMatrix()
        val lineWidth = (titleWidth * scale * 0.7f * (age / 16f).coerceIn(0f, 1f)).toInt()
        val lineY = (cy + 18f).toInt()
        context.fill(width / 2 - lineWidth, lineY, width / 2 + lineWidth, lineY + 1, alpha or (card.color and 0xFFFFFF))
        val subWidth = font.getWidth(card.subtitle)
        context.drawTextWithShadow(font, card.subtitle, (width - subWidth) / 2, lineY + 6, alpha or 0xE8DCD4)
    }

    /** Cerberus howl: dark red vignette that pulses and fades out with the paralysis. */
    private fun renderParalysis(context: DrawContext, width: Int, height: Int, tickDelta: Float) {
        val left = ((paralyzedTicks - tickDelta) / paralyzedMax).coerceIn(0f, 1f)
        val pulse = 0.75f + 0.25f * sin((paralyzedTicks - tickDelta) * 0.6f)
        val strength = (left.coerceAtMost(0.35f) / 0.35f) * pulse
        val edge = (height * 0.18f).toInt()
        for (i in 0 until 8) {
            val inset = edge * i / 8
            val a = (strength * 0.16f * (8 - i) / 8f * 255f).toInt().coerceIn(0, 255)
            val c = (a shl 24) or 0x3A0006
            context.fill(inset, inset, width - inset, inset + edge / 8 + 1, c)
            context.fill(inset, height - inset - edge / 8 - 1, width - inset, height - inset, c)
            context.fill(inset, inset, inset + edge / 8 + 1, height - inset, c)
            context.fill(width - inset - edge / 8 - 1, inset, width - inset, height - inset, c)
        }
        context.fill(0, 0, width, height, ((strength * 0.18f * 255f).toInt() shl 24) or 0x200008)
        val font = MinecraftClient.getInstance().textRenderer
        val text = Text.translatable("boss.hha_blood.paralyzed")
        val w = font.getWidth(text)
        context.drawTextWithShadow(font, text, (width - w) / 2, (height * 0.30f).toInt(), ((strength * 255f).toInt().coerceIn(4, 255) shl 24) or 0xFF3A3A)
    }

    private const val CHARS_PER_TICK = 1.1f
}
