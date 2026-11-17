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

    @Test
    fun resumeSkipsAlreadyPhotographedWaypoints() {
        val plan = GridPlanner.plan(rect(width = 30.0, length = 40.0), PlanParams(altitudeM = 15.0))
        val skip = 20
        val resumed = GridPlanner.resume(plan, skip)
        assertEquals(plan.waypointCount - skip, resumed.waypointCount)
        assertEquals(plan.parts.flatten()[skip].position, resumed.parts.first().first().position)
        assertTrue(resumed.totalDistanceM < plan.totalDistanceM)
    }

    // --- Recorte pelo poligono marcado ---

    private fun inside(poly: List<XY>, x: Double, y: Double): Boolean {
        var c = false
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
        }
        return c
    }

    private fun distToEdges(poly: List<XY>, x: Double, y: Double): Double =
        poly.indices.minOf { i ->
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val dx = b.x - a.x; val dy = b.y - a.y
            val t = (((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            Math.hypot(x - (a.x + t * dx), y - (a.y + t * dy))
        }

    private fun assertWaypointsHugArea(shape: List<Pair<Double, Double>>, params: PlanParams = PlanParams()) {
        val corners = shape.map { (x, y) -> frame.toLatLon(x, y) }
        val poly = shape.map { (x, y) -> XY(x, y) }
        val plan = GridPlanner.plan(corners, params)
        plan.parts.flatten().forEach {
            val p = frame.toXY(it.position)
            val d = if (inside(poly, p.x, p.y)) 0.0 else distToEdges(poly, p.x, p.y)
            assertTrue("ponto a ${"%.1f".format(d)} m da area", d <= params.endMarginM + 0.6)
        }
    }

    @Test
    fun triangleDoesNotFillItsBoundingRectangle() {
        val tri = listOf(0.0 to 0.0, 60.0 to 0.0, 0.0 to 40.0)
        assertWaypointsHugArea(tri)
        val plan = GridPlanner.plan(tri.map { (x, y) -> frame.toLatLon(x, y) }, PlanParams())
        val rectPlan = GridPlanner.plan(rect(40.0, 60.0), PlanParams())
        assertTrue(plan.waypointCount < rectPlan.waypointCount * 0.75)
    }

    @Test
    fun trapezoidStaysInsideMarkedArea() {
        assertWaypointsHugArea(listOf(0.0 to 0.0, 60.0 to 0.0, 45.0 to 25.0, 10.0 to 25.0))
    }

    @Test
    fun concaveLShapeSkipsTheNotch() {
        assertWaypointsHugArea(listOf(0.0 to 0.0, 40.0 to 0.0, 40.0 to 10.0, 10.0 to 10.0, 10.0 to 30.0, 0.0 to 30.0))
    }

    @Test
    fun rotatedSkewedQuadStaysInsideArea() {
        val a = Math.toRadians(25.0)
        val quad = listOf(0.0 to 0.0, 70.0 to 5.0, 55.0 to 30.0, 5.0 to 22.0).map { (x, y) -> (x * cos(a) - y * sin(a)) to (x * sin(a) + y * cos(a)) }
        assertWaypointsHugArea(quad)
        assertWaypointsHugArea(quad, PlanParams(altitudeM = 15.0))
    }
}
