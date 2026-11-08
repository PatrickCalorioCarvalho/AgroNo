package com.mapadrone.app.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class PlanParams(
    val altitudeM: Double = 8.0,
    val frontOverlap: Double = 0.75,
    val sideOverlap: Double = 0.70,
    val speedMs: Double = 2.0,
    val endMarginM: Double = 3.0,
    val camera: CameraModel = CameraModel.MAVIC_PRO,
    /** O Mavic Pro aceita ate 99 waypoints por missao; deixamos folga. */
    val maxWaypointsPerPart: Int = 95,
    val maxTotalWaypoints: Int = 600,
    /** Tempo medio parado em cada waypoint (estabilizar + foto), usado so na estimativa. */
    val secondsPerWaypoint: Double = 3.5
) {
    /** Retorna uma mensagem de erro, ou null se os parametros estao dentro dos limites seguros. */
    fun validate(): String? = when {
        altitudeM < 3.0 || altitudeM > 50.0 -> "Altura deve ficar entre 3 e 50 m."
        frontOverlap < 0.5 || frontOverlap > 0.9 -> "Sobreposicao frontal deve ficar entre 50% e 90%."
        sideOverlap < 0.5 || sideOverlap > 0.9 -> "Sobreposicao lateral deve ficar entre 50% e 90%."
        speedMs < 0.5 || speedMs > 5.0 -> "Velocidade deve ficar entre 0,5 e 5 m/s."
        endMarginM < 0.0 || endMarginM > 20.0 -> "Margem das pontas deve ficar entre 0 e 20 m."
        else -> null
    }
}

data class PlanWaypoint(val position: LatLon, val lineIndex: Int)

data class Plan(
    val params: PlanParams,
    val lineCount: Int,
    val waypointsPerLine: Int,
    val areaLengthM: Double,
    val areaWidthM: Double,
    val flightBearingDeg: Double,
    val photoSpacingM: Double,
    val lineSpacingM: Double,
    val footprintAcrossM: Double,
    val footprintAlongM: Double,
    val gsdCm: Double,
    val totalDistanceM: Double,
    val estimatedSeconds: Double,
    val parts: List<List<PlanWaypoint>>
) {
    val waypointCount: Int get() = parts.sumOf { it.size }
}

object GridPlanner {

    /**
     * Gera linhas paralelas (serpentina) cobrindo o retangulo orientado que envolve os cantos marcados.
     * As linhas voam ao longo do lado mais comprido; cada waypoint e um ponto de foto.
     */
    fun plan(corners: List<LatLon>, params: PlanParams): Plan {
        params.validate()?.let { throw IllegalArgumentException(it) }
        require(corners.size >= 3) { "Marque pelo menos 3 cantos da area." }

        val frame = LocalFrame(corners[0])
        val pts = corners.map { frame.toXY(it) }

        var theta = bestAngle(pts)
        var box = bounds(pts, theta)
        if (box.vRange > box.uRange) {
            theta += Math.PI / 2
            box = bounds(pts, theta)
        }

        val cam = params.camera
        val across = cam.footprintAcross(params.altitudeM)
        val along = cam.footprintAlong(params.altitudeM)
        val lineSpacingMax = across * (1 - params.sideOverlap)
        val photoSpacingMax = along * (1 - params.frontOverlap)

        // As linhas externas ficam um pouco para dentro da area: todo ponto da area e visto por >= 2 linhas.
        val edgeInset = max(0.0, across / 2 - lineSpacingMax)
        val span = box.vRange - 2 * edgeInset
        val lineCount = if (span <= 0.01) 1 else ceil(span / lineSpacingMax - 1e-9).toInt() + 1
        val vPositions = if (lineCount == 1) {
            listOf(box.v0 + box.vRange / 2)
        } else {
            (0 until lineCount).map { box.v0 + edgeInset + it * span / (lineCount - 1) }
        }
        val lineSpacing = if (lineCount == 1) 0.0 else span / (lineCount - 1)

        val uStart = box.u0 - params.endMarginM
        val uEnd = box.u1 + params.endMarginM
        val lineLen = uEnd - uStart
        val perLine = ceil(lineLen / photoSpacingMax - 1e-9).toInt() + 1
        val photoSpacing = lineLen / (perLine - 1)

        require(lineCount * perLine <= params.maxTotalWaypoints) {
            "Area grande demais para o MVP (${lineCount * perLine} pontos, maximo ${params.maxTotalWaypoints})."
        }

        val cosT = cos(theta)
        val sinT = sin(theta)
        fun point(u: Double, v: Double) = frame.toLatLon(u * cosT - v * sinT, u * sinT + v * cosT)

        val lines = vPositions.mapIndexed { i, v ->
            val ordered = (0 until perLine).map { k -> uStart + k * photoSpacing }.let { if (i % 2 == 0) it else it.reversed() }
            ordered.map { u -> PlanWaypoint(point(u, v), i) }
        }

        val parts = splitIntoParts(lines, params.maxWaypointsPerPart)
        val all = lines.flatten()
        val distance = all.zipWithNext().sumOf { (a, b) -> Geo.distance(a.position, b.position) }
        val seconds = distance / params.speedMs + all.size * params.secondsPerWaypoint

        val bearing = ((90.0 - Math.toDegrees(theta)) % 360.0 + 360.0) % 360.0

        return Plan(
            params = params,
            lineCount = lineCount,
            waypointsPerLine = perLine,
            areaLengthM = box.uRange,
            areaWidthM = box.vRange,
            flightBearingDeg = bearing,
            photoSpacingM = photoSpacing,
            lineSpacingM = lineSpacing,
            footprintAcrossM = across,
            footprintAlongM = along,
            gsdCm = cam.gsdCm(params.altitudeM),
            totalDistanceM = distance,
            estimatedSeconds = seconds,
            parts = parts
        )
    }

    private fun splitIntoParts(lines: List<List<PlanWaypoint>>, maxPerPart: Int): List<List<PlanWaypoint>> {
        val perLine = lines.first().size
        if (perLine > maxPerPart) {
            // Linha maior que o limite de uma missao: corta em pedacos.
            return lines.flatten().chunked(maxPerPart)
        }
        val maxLinesPerPart = max(1, maxPerPart / perLine)
        val partsNeeded = ceil(lines.size.toDouble() * perLine / maxPerPart).toInt()
        val balanced = ceil(lines.size.toDouble() / partsNeeded).toInt()
        val linesPerPart = min(balanced, maxLinesPerPart)
        return lines.chunked(linesPerPart).map { it.flatten() }
    }

    private class Box(val u0: Double, val u1: Double, val v0: Double, val v1: Double) {
        val uRange get() = u1 - u0
        val vRange get() = v1 - v0
    }

    private fun bounds(pts: List<XY>, theta: Double): Box {
        val c = cos(theta)
        val s = sin(theta)
        val us = pts.map { it.x * c + it.y * s }
        val vs = pts.map { -it.x * s + it.y * c }
        return Box(us.min(), us.max(), vs.min(), vs.max())
    }

    /** Escolhe a direcao (entre as arestas do poligono) que da o menor retangulo envolvente. */
    private fun bestAngle(pts: List<XY>): Double {
        var best = Double.NaN
        var bestArea = Double.MAX_VALUE
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            if (abs(b.x - a.x) + abs(b.y - a.y) < 0.01) continue
            val theta = atan2(b.y - a.y, b.x - a.x)
            val box = bounds(pts, theta)
            val area = box.uRange * box.vRange
            if (area < bestArea) {
                bestArea = area
                best = theta
            }
        }
        require(!best.isNaN() && bestArea > 0.01) { "Os cantos marcados formam uma area vazia." }
        return best
    }
}
