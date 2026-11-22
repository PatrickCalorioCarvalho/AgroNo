package com.mapadrone.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.Toast
import com.mapadrone.app.data.FlightRecord
import com.mapadrone.app.data.FlightStore
import com.mapadrone.app.data.SyncClient
import dji.sdk.media.MediaFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Baixa as fotos de um voo do cartao SD do drone (pelo controle) e envia ao servidor, uma por vez.
 * Fluxo: escolhe o voo -> le o cartao -> separa as fotos pelo horario do voo -> baixa e envia cada uma.
 * Pode ser repetido: fotos que o servidor ja tem sao puladas.
 */
class PhotoTransfer(
    private val act: Activity,
    private val store: FlightStore,
    private val serverUrl: () -> String
) {
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false
    private var running = false

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_LONG).show()

    /** [blocked]: motivo para nao poder baixar agora (voando, missao em andamento), ou null. */
    fun start(blocked: String?) {
        if (blocked != null) return toast(blocked)
        if (running) return toast("Ja existe um envio de fotos em andamento.")
        if (serverUrl().isBlank()) return toast("Defina o servidor em Config.")
        if (!DroneManager.status.connected) return toast("Drone nao conectado (ligue o controle e o drone).")
        val records = store.list().filter { it.photosTriggered > 0 }
        if (records.isEmpty()) return toast("Nenhum voo com fotos disparadas.")
        val adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, records.map { it.summary() })
        AlertDialog.Builder(act)
            .setTitle("Enviar fotos de qual voo?")
            .setAdapter(adapter) { _, i -> readCard(records[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun readCard(rec: FlightRecord) {
        running = true
        toast("Lendo o cartao SD do drone...")
        DroneManager.loadSdPhotos { list, err ->
            if (list == null) { running = false; DroneManager.exitDownloadMode(); return@loadSdPhotos toast(err ?: "Falha ao ler o cartao.") }
            if (list.isEmpty()) { running = false; DroneManager.exitDownloadMode(); return@loadSdPhotos toast("Nenhuma foto no cartao SD.") }
            choose(rec, list)
        }
    }

    private fun choose(rec: FlightRecord, all: List<MediaFile>) {
        // Janela curta: com 15 min entrariam fotos de voos feitos logo antes ou depois (ex.: tentativas anteriores).
        val pad = 90_000L
        val from = rec.startedAtMs - pad
        val to = (if (rec.endedAtMs > 0) rec.endedAtMs else rec.startedAtMs + 3 * 3_600_000L) + pad
        val inWindow = all.filter { DroneManager.photoTimeMs(it) in from..to }
        val fmt = { f: MediaFile -> FlightRecord.format(DroneManager.photoTimeMs(f)) }
        val cancel = { running = false; DroneManager.exitDownloadMode() }

        val extra = inWindow.size - rec.photosTriggered
        if (inWindow.isNotEmpty() && extra > 2 && rec.photosTriggered > 0) {
            // Mais fotos que disparos: as sobras sao de outro voo. As mais antigas saem primeiro.
            val n = rec.photosTriggered
            AlertDialog.Builder(act)
                .setTitle("Voo ${rec.id}")
                .setMessage("${inWindow.size} foto(s) do cartao no horario do voo, mas o voo registrou so $n disparo(s).\n" +
                    "As $extra a mais devem ser de outro voo.\nEnviar so as ultimas $n?")
                .setPositiveButton("Enviar as ultimas $n") { _, _ -> run(rec, inWindow.takeLast(n)) }
                .setNeutralButton("Enviar todas (${inWindow.size})") { _, _ -> run(rec, inWindow) }
                .setNegativeButton("Cancelar") { _, _ -> cancel() }
                .setOnCancelListener { cancel() }
                .show()
        } else if (inWindow.isNotEmpty()) {
            AlertDialog.Builder(act)
                .setTitle("Voo ${rec.id}")
                .setMessage("${inWindow.size} foto(s) do cartao no horario do voo (${fmt(inWindow.first())} a ${fmt(inWindow.last())}).\n" +
                    "O voo registrou ${rec.photosTriggered} disparo(s).\nBaixar e enviar ao servidor?")
                .setPositiveButton("Enviar") { _, _ -> run(rec, inWindow) }
                .setNegativeButton("Cancelar") { _, _ -> cancel() }
                .setOnCancelListener { cancel() }
                .show()
        } else {
            // Relogio do drone diferente do tablet: oferece as ultimas N fotos, N = disparos do voo.
            val n = rec.photosTriggered.coerceIn(1, all.size)
            val last = all.takeLast(n)
            AlertDialog.Builder(act)
                .setTitle("Nenhuma foto no horario do voo")
                .setMessage("O cartao tem ${all.size} foto(s), de ${fmt(all.first())} ate ${fmt(all.last())}.\n" +
                    "O voo foi em ${FlightRecord.format(rec.startedAtMs)} (o relogio do drone pode estar diferente).\n" +
                    "Enviar as ultimas $n foto(s) do cartao (= disparos do voo)?")
                .setPositiveButton("Enviar as ultimas $n") { _, _ -> run(rec, last) }
                .setNegativeButton("Cancelar") { _, _ -> cancel() }
                .setOnCancelListener { cancel() }
                .show()
        }
    }

    private fun run(rec: FlightRecord, files: List<MediaFile>) {
        cancelled = false
        val dialog = AlertDialog.Builder(act)
            .setTitle("Fotos de ${rec.id}")
            .setMessage("Preparando...")
            .setCancelable(false)
            .setNegativeButton("Parar", null)
            .create()
        dialog.show()
        // "Parar" nao fecha o dialogo: so avisa a thread, que termina depois da foto atual.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            cancelled = true
            dialog.setMessage("Parando depois da foto atual...")
        }
        fun ui(msg: String) = act.runOnUiThread { dialog.setMessage(msg) }

        exec.execute {
            val url = serverUrl()
            var sent = 0; var skipped = 0; var failed = 0; var consecutive = 0
            var abort: String? = null
            val dir = File(act.cacheDir, "fotos").apply { mkdirs() }

            // O servidor so aceita fotos de um voo que ele ja conhece.
            SyncClient.postFlight(url, rec.toJson().toString())?.let { abort = "Falha ao falar com o servidor: $it" }
            val have = if (abort == null) SyncClient.listPhotos(url, rec.id) else null
            if (abort == null && have == null) abort = "Nao foi possivel consultar as fotos do voo no servidor."

            if (abort == null && have != null) {
                for ((i, f) in files.withIndex()) {
                    if (cancelled) break
                    val name = f.fileName
                    val head = "Foto ${i + 1}/${files.size}: $name\nEnviadas: $sent  Ja no servidor: $skipped  Falhas: $failed"
                    if (name in have) { skipped++; continue }

                    ui("$head\nBaixando do drone...")
                    var local: File? = null
                    var dlErr: String? = null
                    val latch = CountDownLatch(1)
                    DroneManager.downloadPhoto(f, dir,
                        onProgress = { total, cur -> if (total > 0) ui("$head\nBaixando do drone... ${cur * 100 / total}%") },
                        onDone = { file, e -> local = file; dlErr = e; latch.countDown() })
                    if (!latch.await(3, TimeUnit.MINUTES)) dlErr = "tempo esgotado no download"

                    val file = local
                    var err = dlErr
                    if (file != null && err == null) {
                        ui("$head\nEnviando ao servidor...")
                        err = SyncClient.putPhoto(url, rec.id, name, file)
                    }
                    file?.delete()
                    if (err == null) { sent++; consecutive = 0 }
                    else {
                        failed++; consecutive++
                        if (consecutive >= 3) { abort = "3 falhas seguidas (ultima: $err). Confira a conexao com o drone e com o servidor."; break }
                    }
                }
            }

            DroneManager.exitDownloadMode()
            val summary = buildString {
                append("Enviadas: $sent\nJa estavam no servidor: $skipped\nFalhas: $failed")
                if (cancelled) append("\n\nInterrompido por voce. Repita para continuar de onde parou.")
                abort?.let { append("\n\n$it") }
            }
            act.runOnUiThread {
                running = false
                dialog.dismiss()
                AlertDialog.Builder(act).setTitle("Fotos de ${rec.id}").setMessage(summary).setPositiveButton("OK", null).show()
            }
        }
    }
}
