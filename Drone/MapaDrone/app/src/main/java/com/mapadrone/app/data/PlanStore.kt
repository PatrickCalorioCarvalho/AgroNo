package com.mapadrone.app.data

import android.content.Context
import com.mapadrone.app.core.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plano de voo salvo para repetir: area marcada, ponto H e parametros. O plano em si (linhas e pontos)
 * e recalculado a partir disso, e como o planejador e deterministico a rota sai sempre igual.
 */
data class SavedPlan(
    val name: String,
    val corners: List<LatLon>,
    val home: LatLon?,
    val altitudeM: Double,
    val frontOverlap: Double,
    val sideOverlap: Double,
    val speedMs: Double,
    val endMarginM: Double,
    val savedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("savedAtMs", savedAtMs)
        put("altitudeM", altitudeM)
        put("frontOverlap", frontOverlap)
        put("sideOverlap", sideOverlap)
        put("speedMs", speedMs)
        put("endMarginM", endMarginM)
        put("corners", JSONArray().also { arr -> corners.forEach { arr.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) } })
        home?.let { put("home", JSONObject().put("lat", it.lat).put("lon", it.lon)) }
    }

    companion object {
        fun fromJson(o: JSONObject): SavedPlan {
            val c = o.getJSONArray("corners")
            return SavedPlan(
                name = o.getString("name"),
                corners = (0 until c.length()).map { c.getJSONObject(it).let { p -> LatLon(p.getDouble("lat"), p.getDouble("lon")) } },
                home = o.optJSONObject("home")?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) },
                altitudeM = o.getDouble("altitudeM"),
                frontOverlap = o.getDouble("frontOverlap"),
                sideOverlap = o.getDouble("sideOverlap"),
                speedMs = o.getDouble("speedMs"),
                endMarginM = o.getDouble("endMarginM"),
                savedAtMs = o.optLong("savedAtMs")
            )
        }
    }
}

/** Um arquivo JSON por plano salvo na pasta interna do app. Salvar com o mesmo nome substitui o plano. */
class PlanStore(context: Context) {
    private val dir = File(context.filesDir, "planos").apply { mkdirs() }

    private fun file(name: String): File {
        val slug = name.trim().lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').ifEmpty { "plano" }
        return File(dir, "$slug.json")
    }

    fun save(plan: SavedPlan) = file(plan.name).writeText(plan.toJson().toString(2))

    fun list(): List<SavedPlan> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { SavedPlan.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun delete(name: String) {
        file(name).delete()
    }
}
