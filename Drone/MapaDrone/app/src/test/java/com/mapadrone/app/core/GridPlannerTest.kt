package com.mapadrone.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GridPlannerTest {
    private val origin = LatLon(-15.0, -47.0)
    private val frame = LocalFrame(origin)

    private fun rect(width: Double, length: Double, angleDeg: Double = 0.0): List<LatLon> {
        val a = Math.toRadians(angleDeg)
        return listOf(0.0 to 0.0, length to 0.0, length to width, 0.0 to width).map { (u, v) ->
            frame.toLatLon(u * cos(a) - v * sin(a), u * sin(a) + v * cos(a))
        }
    }

    @Test
    fun footprintAt8mMatchesTheory() {
        val cam = CameraModel.MAVIC_PRO
        assertEquals(10.43, cam.footprintAcross(8.0), 0.05)
        assertEquals(7.70, cam.footprintAlong(8.0), 0.05)
        assertEquals(0.26, cam.gsdCm(8.0), 0.01)
    }

    @Test
    fun terrain12x50At8m() {
        val plan = GridPlanner.plan(rect(12.0, 50.0), PlanParams())
        assertEquals(50.0, plan.areaLengthM, 0.1)
        assertEquals(12.0, plan.areaWidthM, 0.1)
        assertEquals(4, plan.lineCount)
        assertEquals(124, plan.waypointCount)
        assertEquals(2, plan.parts.size)
        assertTrue(plan.parts.all { it.size <= 95 })
        assertTrue(plan.photoSpacingM <= 1.93)
        assertTrue(plan.lineSpacingM <= 3.13)
    }

    @Test
    fun sameCountWhenRectangleIsRotatedAndMarkedInOtherOrder() {
        val base = GridPlanner.plan(rect(12.0, 50.0), PlanParams())
        val rotated = GridPlanner.plan(rect(12.0, 50.0, angleDeg = 37.0).reversed(), PlanParams())
        assertEquals(base.lineCount, rotated.lineCount)
        assertEquals(base.waypointCount, rotated.waypointCount)
    }

    @Test
    fun marksShortSideFirstStillFliesAlongLongSide() {
        val corners = listOf(0.0 to 0.0, 0.0 to 50.0, 12.0 to 50.0, 12.0 to 0.0).map { (x, y) -> frame.toLatLon(x, y) }
        val plan = GridPlanner.plan(corners, PlanParams())
        assertEquals(50.0, plan.areaLengthM, 0.1)
    }

    @Test
    fun waypointsStayNearAreaAndKeepMinimumSpacing() {
        val plan = GridPlanner.plan(rect(12.0, 50.0), PlanParams())
        val all = plan.parts.flatten()
        all.zipWithNext().forEach { (a, b) ->
            assertTrue(Geo.distance(a.position, b.position) > 0.5)
        }
        all.forEach {
            val p = frame.toXY(it.position)
            assertTrue(p.x in -3.5..53.5)
            assertTrue(p.y in -0.5..12.5)
        }
    }

    @Test
    fun linesAlternateDirection() {
        val plan = GridPlanner.plan(rect(12.0, 50.0), PlanParams())
        val line0 = plan.parts.flatten().filter { it.lineIndex == 0 }
        val line1 = plan.parts.flatten().filter { it.lineIndex == 1 }
        assertEquals(line0.first().position.lon, line1.last().position.lon, 1e-5)
    }

    @Test
    fun rejectsUnsafeParams() {
        assertTrue(PlanParams(altitudeM = 1.0).validate() != null)
        assertTrue(PlanParams(speedMs = 9.0).validate() != null)
        assertTrue(PlanParams().validate() == null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun needsThreeCorners() {
        GridPlanner.plan(rect(12.0, 50.0).take(2), PlanParams())
    }
}
