package app.t4l.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class PaletteContents(
    val categoryColors: Map<String, String?> = emptyMap(),
    val order: List<String> = emptyList(),
) {
    fun colorsJson(): String = JsonObject(categoryColors.mapValues { JsonPrimitive(it.value) }).toString()
    fun orderJson(): String = JsonArray(order.map(::JsonPrimitive)).toString()

    companion object {
        fun from(row: PaletteRow): PaletteContents = runCatching {
            val colors = Json.parseToJsonElement(row.categoryColorsJson) as JsonObject
            val order = Json.parseToJsonElement(row.itemOrderJson) as JsonArray
            PaletteContents(colors.mapValues { it.value.jsonPrimitive.contentOrNull }, order.map { it.jsonPrimitive.content })
        }.getOrDefault(PaletteContents())
    }
}

internal fun validHexColor(value: String): Boolean = Regex("^#[0-9A-Fa-f]{6}$").matches(value)
