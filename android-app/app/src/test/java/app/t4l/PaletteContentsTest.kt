package app.t4l

import app.t4l.data.PaletteContents
import app.t4l.data.PaletteRow
import app.t4l.data.validHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContentsTest {
    @Test fun categoryColorsAndMixedOrderRoundTrip() {
        val value = PaletteContents(mapOf("a" to null, "b" to "#123ABC"), listOf("t:task", "c:a", "c:b"))
        val row = PaletteRow("id", "workspace", "Palette", value.colorsJson(), value.orderJson(), updatedAtEpochMs = 1)
        assertEquals(value, PaletteContents.from(row))
    }

    @Test fun reorderKeepsHiddenTaskPosition() {
        val order = listOf("c:a", "t:hidden", "c:b", "t:visible")
        assertEquals(listOf("c:b", "c:a", "t:hidden", "t:visible"), movePaletteKey(order, "c:b", "c:a", false))
    }

    @Test fun reorderCanMoveAcrossSeveralVisibleItems() {
        val order = listOf("c:first", "c:second", "t:task", "c:third", "c:last")
        assertEquals(listOf("c:second", "t:task", "c:third", "c:last", "c:first"),
            movePaletteKey(order, "c:first", "c:last", true))
    }

    @Test fun colorsUseOnlyRgbHex() {
        assertTrue(validHexColor("#A0b1C2"))
        assertFalse(validHexColor("#A0B1C2FF"))
        assertFalse(validHexColor("red"))
    }
}
