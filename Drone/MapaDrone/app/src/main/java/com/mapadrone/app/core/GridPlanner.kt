package com.mapadrone.app.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
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
    /** Tempo medio gasto em cada waypoint (parar, estabilizar, foto e acelerar), medido em voo real; usado so na estimativa. */
    val secondsPerWaypoint: Double = 4.0,
    /** false reproduz o planejador antigo (retangulo envolvente), usado so para continuar voos antigos. */
    val clipToPolygon: Boolean = true
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

/** [photo] = false para pontos de passagem sem foto (ex.: retorno ao ponto de pouso). */
data class PlanWaypoint(val position: LatLon, val lineIndex: Int, val photo: Boolean = true)

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
        val lineCountPlanned = if (span <= 0.01) 1 else ceil(span / lineSpacingMax - 1e-9).toInt() + 1
        val vPositions = if (lineCountPlanned == 1) {
            listOf(box.v0 + box.vRange / 2)
        } else {
            // eps: evita linha exatamente sobre a borda do poligono
            (0 until lineCountPlanned).map { (box.v0 + edgeInset + it * span / (lineCountPlanned - 1)).coerceIn(box.v0 + 0.01, box.v1 - 0.01) }
        }
        val lineSpacing = if (lineCountPlanned == 1) 0.0 else span / (lineCountPlanned - 1)

        val cosT = cos(theta)
        val sinT = sin(theta)
        fun point(u: Double, v: Double) = frame.toLatLon(u * cosT - v * sinT, u * sinT + v * cosT)

        // Poligono marcado no referencial girado (u ao longo do voo, v entre as linhas).
        val poly = pts.map { XY(it.x * cosT + it.y * sinT, -it.x * sinT + it.y * cosT) }

        // Cada linha de voo e recortada pelo poligono: so ha fotos dentro da area marcada (mais a margem das pontas).
        var spacingUsed = 0.0
        val built = vPositions.mapNotNull { v ->
            val segments = if (params.clipToPolygon) clipLine(poly, v, params.endMarginM)
                else listOf((box.u0 - params.endMarginM) to (box.u1 + params.endMarginM))
            if (segments.isEmpty()) return@mapNotNull null
            segments.flatMap { (u0, u1) ->
                val len = u1 - u0
                val n = if (len < 1e-6) 1 else ceil(len / photoSpacingMax - 1e-9).toInt() + 1
                val step = if (n == 1) 0.0 else len / (n - 1)
                if (step > spacingUsed) spacingUsed = step
                (0 until n).map { k -> point(u0 + k * step, v) }
            }
        }
        require(built.isNotEmpty()) { "Area pequena demais para gerar linhas de voo." }
        val lines = built.mapIndexed { i, ptsLine ->
            val ordered = if (i % 2 == 0) ptsLine else ptsLine.reversed()
            ordered.map { PlanWaypoint(it, i) }
        }
        val perLine = lines.maxOf { it.size }
        val photoSpacing = spacingUsed
        val lineCount = lines.size

        require(lines.sumOf { it.size } <= params.maxTotalWaypoints) {
            "Area grande demais para o MVP (${lines.sumOf { it.size }} pontos, maximo ${params.maxTotalWaypoints})."
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

    /** Plano para continuar um voo: descarta os [skip] primeiros pontos e reagrupa o resto em partes. */
    fun resume(plan: Plan, skip: Int): Plan {
        val remaining = plan.parts.flatten().drop(skip.coerceIn(0, plan.waypointCount - 1))
        val distance = remaining.zipWithNext().sumOf { (a, b) -> Geo.distance(a.position, b.position) }
        return plan.copy(
            parts = remaining.chunked(plan.params.maxWaypointsPerPart),
            totalDistanceM = distance,
            estimatedSeconds = distance / plan.params.speedMs + remaining.size * plan.params.secondsPerWaypoint
        )
    }

    /**
     * Divide a rota no menor numero de partes de ate [maxPerPart] pontos, com tamanhos parecidos.
     * Pode cortar no meio de uma linha: a parte seguinte continua do ponto vizinho.
     */
    private fun splitIntoParts(lines: List<List<PlanWaypoint>>, maxPerPart: Int): List<List<PlanWaypoint>> {
        val all = lines.flatten()
        val partsNeeded = ceil(all.size.toDouble() / maxPerPart).toInt().coerceAtLeast(1)
        return all.chunked(ceil(all.size.toDouble() / partsNeeded).toInt().coerceAtLeast(1))
    }

    /** Trechos [u0, u1] da linha v dentro do poligono (pares de cruzamentos), cada um estendido pela margem. */
    private fun clipLine(poly: List<XY>, v: Double, margin: Double): List<Pair<Double, Double>> {
        val us = mutableListOf<Double>()
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            // regra meio-aberta: um vertice exatamente sobre a linha conta uma vez so
            if ((a.y <= v) != (b.y <= v)) us += a.x + (v - a.y) / (b.y - a.y) * (b.x - a.x)
        }
        us.sort()
        val raw = us.chunked(2).filter { it.size == 2 }.map { (it[0] - margin) to (it[1] + margin) }
        // junta trechos que se encostam depois de aplicar a margem
        val merged = mutableListOf<Pair<Double, Double>>()
        for (seg in raw) {
            val last = merged.lastOrNull()
            if (last != null && seg.first <= last.second) merged[merged.lastIndex] = last.first to max(last.second, seg.second)
            else merged += seg
        }
        return merged
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
