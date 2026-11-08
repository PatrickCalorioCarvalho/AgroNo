package com.mapadrone.app.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class XY(val x: Double, val y: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6371000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }
}

/** Projecao plana local (metros) ao redor de uma origem: x = leste, y = norte. Boa para areas de centenas de metros. */
class LocalFrame(val origin: LatLon) {
    private val metersPerDegLat = Math.PI / 180.0 * 6371000.0
    private val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(origin.lat))

    fun toXY(p: LatLon) = XY((p.lon - origin.lon) * metersPerDegLon, (p.lat - origin.lat) * metersPerDegLat)

    fun toLatLon(x: Double, y: Double) = LatLon(origin.lat + y / metersPerDegLat, origin.lon + x / metersPerDegLon)
}
