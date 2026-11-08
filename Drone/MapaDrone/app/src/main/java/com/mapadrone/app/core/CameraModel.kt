package com.mapadrone.app.core

/** Geometria da camera para calcular a area vista no chao (camera apontada a -90 graus, foto em paisagem). */
data class CameraModel(
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalMm: Double,
    val imageWidthPx: Int,
    val imageHeightPx: Int
) {
    /** Largura vista no chao, na direcao perpendicular ao voo (lado longo da foto). */
    fun footprintAcross(altitudeM: Double) = altitudeM * sensorWidthMm / focalMm

    /** Comprimento visto no chao ao longo do voo (lado curto da foto). */
    fun footprintAlong(altitudeM: Double) = altitudeM * sensorHeightMm / focalMm

    /** Tamanho de um pixel no chao, em cm. */
    fun gsdCm(altitudeM: Double) = footprintAcross(altitudeM) / imageWidthPx * 100.0

    companion object {
        /** Mavic Pro (1a geracao): sensor 1/2.3", 12 MP, focal real aproximada de 4,73 mm. */
        val MAVIC_PRO = CameraModel(6.17, 4.55, 4.73, 4000, 3000)
    }
}
