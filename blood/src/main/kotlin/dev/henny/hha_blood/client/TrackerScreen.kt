package dev.henny.hha_blood.client

import dev.henny.hha_blood.net.TrackerListPayload
import dev.henny.hha_blood.net.TrackerSelectPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.screen.ScreenTexts
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/** Blood Tracker target selection (opened with the tracker key, filled by the server). */
class TrackerScreen(private val data: TrackerListPayload) : Screen(Text.translatable("hha_blood.tracker.title")) {

    private var page = 0
    private val perPage = 6
    private val pages get() = maxOf(1, (data.candidates.size + perPage - 1) / perPage)

    override fun init() {
        val cx = width / 2
        var y = height / 2 - 62
        val bw = 220

        when {
            data.currentTarget != null -> {
                addDrawableChild(
                    ButtonWidget.builder(Text.translatable("hha_blood.tracker.stop").formatted(Formatting.RED)) {
                        ClientPlayNetworking.send(TrackerSelectPayload(null))
                        close()
                    }.dimensions(cx - bw / 2, y + 30, bw, 20).build(),
                )
            }
            data.cooldownTicks > 0 -> Unit // only the status text
            else -> {
                val slice = data.candidates.drop(page * perPage).take(perPage)
                for (c in slice) {
                    val label = Text.literal(c.name).formatted(Formatting.RED)
                        .append(Text.literal("  ·  ").formatted(Formatting.DARK_GRAY))
                        .append(Text.translatable("hha_blood.tracker.distance", c.distance).formatted(Formatting.GRAY))
                    addDrawableChild(
                        ButtonWidget.builder(label) {
                            ClientPlayNetworking.send(TrackerSelectPayload(c.uuid))
                            close()
                        }.dimensions(cx - bw / 2, y, bw, 20).build(),
                    )
                    y += 22
                }
                if (pages > 1) {
                    val py = height / 2 - 62 + perPage * 22 + 4
                    addDrawableChild(ButtonWidget.builder(Text.literal("<")) { page = (page - 1 + pages) % pages; clearAndInit() }
                        .dimensions(cx - bw / 2, py, 30, 20).build())
                    addDrawableChild(ButtonWidget.builder(Text.literal(">")) { page = (page + 1) % pages; clearAndInit() }
                        .dimensions(cx + bw / 2 - 30, py, 30, 20).build())
                }
            }
        }
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE) { close() }.dimensions(cx - 50, height / 2 + 90, 100, 20).build())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.render(context, mouseX, mouseY, deltaTicks)
        val cx = width / 2
        val top = height / 2 - 92
        context.drawCenteredTextWithShadow(textRenderer, title.copy().formatted(Formatting.DARK_RED, Formatting.BOLD), cx, top, -1)
        val status: Text = when {
            data.currentTarget != null ->
                Text.translatable("hha_blood.tracker.current", data.currentName, (data.remainingTicks + 19) / 20).formatted(Formatting.RED)
            data.cooldownTicks > 0 ->
                Text.translatable("hha_blood.tracker.cooldown", (data.cooldownTicks + 19) / 20).formatted(Formatting.GRAY)
            data.candidates.isEmpty() ->
                Text.translatable("hha_blood.tracker.nobody").formatted(Formatting.GRAY)
            else ->
                Text.translatable("hha_blood.tracker.pick", (data.durationTicks + 19) / 20).formatted(Formatting.GRAY)
        }
        context.drawCenteredTextWithShadow(textRenderer, status, cx, top + 14, -1)
        if (pages > 1 && data.currentTarget == null && data.cooldownTicks <= 0) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("${page + 1} / $pages").formatted(Formatting.DARK_GRAY), cx, height / 2 - 62 + perPage * 22 + 10, -1)
        }
    }

    override fun shouldPause(): Boolean = false
}
