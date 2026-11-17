package com.mapadrone.app.data

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Fala com o servidor AgroNo Fly. Os envios devolvem null se deu certo, ou a mensagem de erro. */
object SyncClient {
    private fun base(url: String) = url.trim().trimEnd('/')

    /** Envia o registro do voo (com a rota planejada). */
    fun postFlight(baseUrl: String, json: String): String? = try {
        val conn = URL(base(baseUrl) + "/api/voos").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        if (code in 200..299) null else "HTTP $code"
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Nomes das fotos que o servidor ja tem do voo, ou null se nao conseguiu consultar. */
    fun listPhotos(baseUrl: String, flightId: String): Set<String>? = try {
        val conn = URL(base(baseUrl) + "/api/voos/" + enc(flightId) + "/fotos").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        val ok = conn.responseCode in 200..299
        val body = if (ok) conn.inputStream.bufferedReader().use { it.readText() } else ""
        conn.disconnect()
        if (!ok) null else JSONArray(body).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    } catch (e: Exception) {
        null
    }

    /** Envia uma foto do voo (PUT com os bytes do arquivo, em streaming). */
    fun putPhoto(baseUrl: String, flightId: String, name: String, file: File): String? = try {
        val conn = URL(base(baseUrl) + "/api/voos/" + enc(flightId) + "/fotos/" + enc(name)).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.connectTimeout = 5000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(file.length())
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        file.inputStream().use { input -> conn.outputStream.use { input.copyTo(it) } }
        val code = conn.responseCode
        conn.disconnect()
        if (code in 200..299) null else "HTTP $code"
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
