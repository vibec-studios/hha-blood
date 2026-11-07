package dev.henny.hha_blood.boss

import com.google.gson.JsonParser
import dev.henny.hha_blood.HhaBlood
import java.io.InputStreamReader
import kotlin.math.max

/**
 * Dialogue lines of the Slaughter Pit (`boss.hha_blood.line.<key>` in the lang files). The server reads the
 * German and English texts from the mod jar once, so each line stays on screen long enough to be read in
 * either language (the scenes wait for it).
 */
object PitLines {
    private val lengths = HashMap<String, Int>()
    private var loaded = false

    fun key(k: String): String = "boss.hha_blood.line.$k"

    private fun load() {
        loaded = true
        for (lang in listOf("de_de", "en_us")) {
            try {
                val stream = PitLines::class.java.getResourceAsStream("/assets/hha_blood/lang/$lang.json") ?: continue
                InputStreamReader(stream, Charsets.UTF_8).use { r ->
                    val obj = JsonParser.parseReader(r).asJsonObject
                    for ((k, v) in obj.entrySet()) {
                        if (!k.startsWith("boss.hha_blood.line.")) continue
                        lengths[k] = max(lengths[k] ?: 0, v.asString.length)
                    }
                }
            } catch (t: Throwable) {
                HhaBlood.LOGGER.warn("Could not read the dialogue lengths from {}", lang, t)
            }
        }
    }

    /** Display time of a line: typing (1.1 chars/tick) + reading time. */
    fun ticks(k: String): Int {
        if (!loaded) load()
        val len = lengths[key(k)] ?: 60
        return (len / 1.1).toInt() + 28 + len / 4
    }
}
