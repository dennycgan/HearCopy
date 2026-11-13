package com.dennycgan.hearcopy.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-006 split rule extended to storage (ADR-017): only a drag end saves. */
class TouchPlacementTest {

    private val saved = mutableListOf<PanelPoint>()

    private fun placement() = TouchPlacement { saved += it }

    @Test
    fun `a drag end remembers the resting point and saves it once`() {
        val p = placement()
        p.settle(ClampTrigger.DragEnd, PanelPoint(40, 900))
        assertEquals(PanelPoint(40, 900), p.point)
        assertEquals(listOf(PanelPoint(40, 900)), saved)
    }

    @Test
    fun `the first layout without a restored position remembers but never saves`() {
        val p = placement()
        p.settle(ClampTrigger.Initial, PanelPoint(1000, 400))
        assertEquals(PanelPoint(1000, 400), p.point)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a configuration clamp neither changes the point nor saves`() {
        val p = placement()
        p.settle(ClampTrigger.DragEnd, PanelPoint(40, 900))
        saved.clear()
        p.settle(ClampTrigger.Configuration, PanelPoint(40, 850))
        assertEquals(PanelPoint(40, 900), p.point)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a restored point is placed without being changed or saved`() {
        val p = placement()
        p.restore(PanelPoint(40, 2300))
        p.settle(ClampTrigger.Restored, PanelPoint(40, 2200))
        p.settle(ClampTrigger.Configuration, PanelPoint(40, 2250))
        assertEquals(PanelPoint(40, 2300), p.point)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a drag after a restore saves the new point once`() {
        val p = placement()
        p.restore(PanelPoint(40, 2300))
        p.settle(ClampTrigger.DragEnd, PanelPoint(500, 600))
        assertEquals(PanelPoint(500, 600), p.point)
        assertEquals(listOf(PanelPoint(500, 600)), saved)
    }

    @Test
    fun `a restore never replaces a point already remembered`() {
        val p = placement()
        p.settle(ClampTrigger.DragEnd, PanelPoint(1, 2))
        p.restore(PanelPoint(40, 2300))
        assertEquals(PanelPoint(1, 2), p.point)
    }

    @Test
    fun `nothing is remembered before the first placement`() {
        assertNull(placement().point)
    }

    @Test
    fun `only a drag end saves, and the triggers placed from memory never store`() {
        assertEquals(listOf(ClampTrigger.DragEnd), ClampTrigger.entries.filter { it.savesPosition })
        ClampTrigger.entries.filter { it.fromMemory }.forEach { assertTrue("$it", !it.storesPosition && !it.savesPosition) }
        assertEquals(
            setOf(ClampTrigger.Restored, ClampTrigger.Configuration),
            ClampTrigger.entries.filter { it.fromMemory }.toSet(),
        )
    }
}
