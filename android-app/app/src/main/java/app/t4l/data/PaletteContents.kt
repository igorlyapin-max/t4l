package app.t4l.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

data class PaletteLayoutRow(val id: String, val slots: List<String?>)

data class PaletteContents(
    val categoryColors: Map<String, String?> = emptyMap(),
    val order: List<String> = emptyList(),
    val rows: List<PaletteLayoutRow> = emptyList(),
) {
    fun colorsJson(): String = JsonObject(categoryColors.mapValues { JsonPrimitive(it.value) }).toString()
    fun orderJson(): String = JsonArray(order.map(::JsonPrimitive)).toString()
    fun rowsJson(): String = JsonArray(rows.map { row -> JsonObject(mapOf(
        "id" to JsonPrimitive(row.id),
        "slots" to JsonArray(row.slots.map { JsonPrimitive(it) }),
    )) }).toString()

    fun moved(moving: String, destinationRowId: String, destinationSlot: Int): PaletteContents {
        val source = rows.indexOfFirst { moving in it.slots }
        val target = rows.indexOfFirst { it.id == destinationRowId }
        if (source < 0 || target < 0 || destinationSlot < 0 || destinationSlot > rows[target].slots.size) return this
        val next = rows.map { it.copy(slots = it.slots.toMutableList()) }.toMutableList()
        val sourceSlot = next[source].slots.indexOf(moving)
        next[source] = next[source].copy(slots = next[source].slots.toMutableList().also { it[sourceSlot] = null })
        val targetSlots = next[target].slots.toMutableList()
        if (destinationSlot < targetSlots.size && targetSlots[destinationSlot] == null) targetSlots[destinationSlot] = moving
        else targetSlots.add(destinationSlot, moving)
        next[target] = next[target].copy(slots = targetSlots)
        return copy(rows = next, order = next.flatMap { it.slots.filterNotNull() })
    }

    companion object {
        fun from(row: PaletteRow): PaletteContents = runCatching {
            val colors = Json.parseToJsonElement(row.categoryColorsJson) as JsonObject
            val order = Json.parseToJsonElement(row.itemOrderJson) as JsonArray
            val keys = order.map { it.jsonPrimitive.content }
            val storedRows = (Json.parseToJsonElement(row.rowsJson) as JsonArray).map { value ->
                val item = value.jsonObject
                PaletteLayoutRow(item.getValue("id").jsonPrimitive.content,
                    item.getValue("slots").jsonArray.map { it.jsonPrimitive.contentOrNull })
            }
            val rows = if (storedRows.isEmpty() && keys.isNotEmpty()) keys.chunked(3).mapIndexed { index, slots ->
                PaletteLayoutRow(UUID.nameUUIDFromBytes("${row.id}:$index".toByteArray()).toString(), slots)
            } else storedRows
            PaletteContents(colors.mapValues { it.value.jsonPrimitive.contentOrNull }, keys, rows)
        }.getOrDefault(PaletteContents())
    }
}

internal fun validHexColor(value: String): Boolean = Regex("^#[0-9A-Fa-f]{6}$").matches(value)
