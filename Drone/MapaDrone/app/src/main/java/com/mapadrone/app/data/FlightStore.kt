package com.mapadrone.app.data

import android.content.Context
import com.mapadrone.app.core.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de um voo de mapeamento. As fotos ficam no cartao SD do drone; aqui fica o historico. */
data class FlightRecord(
    val id: String,
    val startedAtMs: Long,
    var endedAtMs: Long = 0,
    var status: String = "em andamento",
    val altitudeM: Double,
    val frontOverlap: Double,
    val sideOverlap: Double,
    val speedMs: Double,
    val endMarginM: Double,
    val corners: List<LatLon>,
    val lineCount: Int,
    val waypointCount: Int,
    val partsTotal: Int,
    var partsDone: Int = 0,
    var photosTriggered: Int = 0,
    var notes: String = "",
    val log: MutableList<String> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startedAtMs", startedAtMs)
        put("endedAtMs", endedAtMs)
        put("startedAt", format(startedAtMs))
        put("endedAt", if (endedAtMs > 0) format(endedAtMs) else "")
        put("status", status)
        put("altitudeM", altitudeM)
        put("frontOverlap", frontOverlap)
        put("sideOverlap", sideOverlap)
        put("speedMs", speedMs)
        put("endMarginM", endMarginM)
        put("corners", JSONArray().also { arr ->
            corners.forEach { arr.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        })
        put("lineCount", lineCount)
        put("waypointCount", waypointCount)
        put("partsTotal", partsTotal)
        put("partsDone", partsDone)
        put("photosTriggered", photosTriggered)
        put("notes", notes)
        put("log", JSONArray(log))
    }

    fun summary(): String =
        "$id  |  $status  |  $photosTriggered fotos  |  ${format(startedAtMs)}"

    companion object {
        fun format(ms: Long): String =
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(ms))

        fun fromJson(o: JSONObject): FlightRecord {
            val corners = o.optJSONArray("corners") ?: JSONArray()
            val log = o.optJSONArray("log") ?: JSONArray()
            return FlightRecord(
                id = o.getString("id"),
                startedAtMs = o.getLong("startedAtMs"),
                endedAtMs = o.optLong("endedAtMs"),
                status = o.optString("status"),
                altitudeM = o.optDouble("altitudeM"),
                frontOverlap = o.optDouble("frontOverlap"),
                sideOverlap = o.optDouble("sideOverlap"),
                speedMs = o.optDouble("speedMs"),
                endMarginM = o.optDouble("endMarginM"),
                corners = (0 until corners.length()).map {
                    corners.getJSONObject(it).let { c -> LatLon(c.getDouble("lat"), c.getDouble("lon")) }
                },
                lineCount = o.optInt("lineCount"),
                waypointCount = o.optInt("waypointCount"),
                partsTotal = o.optInt("partsTotal"),
                partsDone = o.optInt("partsDone"),
                photosTriggered = o.optInt("photosTriggered"),
                notes = o.optString("notes"),
                log = (0 until log.length()).map { log.getString(it) }.toMutableList()
            )
        }
    }
}

/** Guarda um arquivo JSON por voo na pasta interna do app. */
class FlightStore(context: Context) {
    private val dir = File(context.filesDir, "voos").apply { mkdirs() }

    /** ID no formato AAAA-MM-DD_NNN, sequencial no dia. */
    fun newId(nowMs: Long = System.currentTimeMillis()): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        val count = dir.listFiles { f -> f.name.startsWith(day) }?.size ?: 0
        return "%s_%03d".format(day, count + 1)
    }

    fun save(record: FlightRecord) {
        File(dir, "${record.id}.json").writeText(record.toJson().toString(2))
    }

    fun list(): List<FlightRecord> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { FlightRecord.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.startedAtMs }
            ?: emptyList()

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
