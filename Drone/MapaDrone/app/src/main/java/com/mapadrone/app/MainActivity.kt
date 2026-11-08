package com.mapadrone.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.mapadrone.app.core.Geo
import com.mapadrone.app.core.GridPlanner
import com.mapadrone.app.core.LatLon
import com.mapadrone.app.core.Plan
import com.mapadrone.app.core.PlanParams
import com.mapadrone.app.data.FlightRecord
import com.mapadrone.app.data.FlightStore
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.roundToInt

class MainActivity : Activity(), DroneListener {

    private lateinit var map: MapView
    private lateinit var tvStatus: TextView
    private lateinit var tvPlan: TextView
    private lateinit var store: FlightStore

    private val corners = mutableListOf<LatLon>()
    private var plan: Plan? = null
    private var nextPart = 0
    private var record: FlightRecord? = null
    private var partPhotos = 0
    private var photosBeforePart = 0
    private var missionRunning = false

    private val dynamicOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()
    private lateinit var droneMarker: Marker
    private var centeredOnDrone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osm")
            osmdroidTileCache = File(filesDir, "osm/tiles")
        }
        setContentView(R.layout.activity_main)

        store = FlightStore(this)
        tvStatus = findViewById(R.id.tvStatus)
        tvPlan = findViewById(R.id.tvPlan)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(4.0)
        map.controller.setCenter(GeoPoint(-15.78, -47.93))
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) addCorner(LatLon(p.latitude, p.longitude))
                return true
            }
        }))
        droneMarker = Marker(map).apply {
            title = "Drone"
            icon = getDrawable(android.R.drawable.ic_menu_mylocation)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isEnabled = false
        }
        map.overlays.add(droneMarker)

        button(R.id.btnMark) { markCornerFromDrone() }
        button(R.id.btnUndo) { if (corners.isNotEmpty()) { corners.removeAt(corners.lastIndex); invalidatePlan() } }
        button(R.id.btnClear) { corners.clear(); invalidatePlan() }
        button(R.id.btnPlan) { generatePlan() }
        button(R.id.btnGimbal) {
            DroneManager.prepareCamera { err -> runOnUiThread { toast(err?.let { "Gimbal/camera: $it" } ?: "Camera apontada para baixo.") } }
        }
        button(R.id.btnCenter) { centerOnDrone(force = true) }
        button(R.id.btnHistory) { showHistory() }
        button(R.id.btnStart) { confirmStart() }
        button(R.id.btnPause) { DroneManager.pause { err -> runOnUiThread { toast(err ?: "Missao pausada.") } } }
        button(R.id.btnResume) { DroneManager.resume { err -> runOnUiThread { toast(err ?: "Missao retomada.") } } }
        button(R.id.btnStop) { stopMission() }

        DroneManager.listener = this
        onStatusChanged(DroneManager.status)
        requestPermissionsThenRegister()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        DroneManager.listener = this
        onStatusChanged(DroneManager.status)
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (DroneManager.listener === this) DroneManager.listener = null
        super.onDestroy()
    }

    private fun button(id: Int, action: () -> Unit) = findViewById<Button>(id).setOnClickListener { action() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- Permissoes e registro ----

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private fun requestPermissionsThenRegister() {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) DroneManager.register(this) else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        DroneManager.register(this)
    }

    // ---- Cantos e plano ----

    private fun markCornerFromDrone() {
        val s = DroneManager.status
        val pos = s.position
        when {
            !s.connected -> toast("Drone nao conectado.")
            pos == null || s.satellites < 6 -> toast("Sem GPS confiavel (satelites: ${s.satellites}). Aguarde fixar.")
            else -> addCorner(pos)
        }
    }

    private fun addCorner(p: LatLon) {
        corners.add(p)
        invalidatePlan()
    }

    private fun invalidatePlan() {
        plan = null
        nextPart = 0
        tvPlan.text = if (corners.isEmpty()) {
            "Marque os cantos da area (botao ou toque longo no mapa) e gere o plano."
        } else {
            "${corners.size} canto(s) marcado(s). Marque ao menos 3 e toque em Gerar plano."
        }
        redraw()
    }

    private fun num(id: Int): Double? = findViewById<EditText>(id).text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun readParams(): PlanParams? {
        val alt = num(R.id.etAlt)
        val front = num(R.id.etFront)
        val side = num(R.id.etSide)
        val speed = num(R.id.etSpeed)
        val margin = num(R.id.etMargin)
        if (alt == null || front == null || side == null || speed == null || margin == null) {
            toast("Preencha todos os parametros com numeros.")
            return null
        }
        return PlanParams(alt, front / 100.0, side / 100.0, speed, margin)
    }

    private fun generatePlan() {
        if (missionRunning) return toast("Pare a missao antes de gerar um novo plano.")
        val params = readParams() ?: return
        params.validate()?.let { return toast(it) }
        try {
            val p = GridPlanner.plan(corners, params)
            plan = p
            nextPart = 0
            val minutes = (p.estimatedSeconds / 60).roundToInt()
            tvPlan.text = buildString {
                append("Area ~%.0f x %.0f m  |  %d linhas  |  %d fotos\n".format(p.areaLengthM, p.areaWidthM, p.lineCount, p.waypointCount))
                append("Cada foto cobre %.1f x %.1f m  |  %.2f cm/pixel\n".format(p.footprintAcrossM, p.footprintAlongM, p.gsdCm))
                append("Foto a cada %.1f m  |  linhas a cada %.1f m  |  rumo %.0f graus\n".format(p.photoSpacingM, p.lineSpacingM, p.flightBearingDeg))
                append("Distancia %.0f m  |  tempo estimado ~%d min\n".format(p.totalDistanceM, minutes))
                append("Partes: ${p.parts.size} (limite de ${p.params.maxWaypointsPerPart} pontos por missao)")
                if (minutes > 15) append("\nATENCAO: tempo longo para uma bateria do Mavic Pro. Troque a bateria entre as partes.")
            }
            redraw()
            map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(corners.map { GeoPoint(it.lat, it.lon) }), true, 80)
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "Nao foi possivel gerar o plano.")
        }
    }

    private fun redraw() {
        map.overlays.removeAll(dynamicOverlays.toSet())
        dynamicOverlays.clear()

        if (corners.size >= 3) {
            dynamicOverlays += Polygon(map).apply {
                points = corners.map { GeoPoint(it.lat, it.lon) }
                fillPaint.color = Color.argb(40, 0, 150, 0)
                outlinePaint.color = Color.rgb(0, 120, 0)
                outlinePaint.strokeWidth = 4f
            }
        }
        val partColors = listOf(Color.rgb(25, 118, 210), Color.rgb(230, 81, 0), Color.rgb(123, 31, 162), Color.rgb(0, 121, 107))
        plan?.parts?.forEachIndexed { i, part ->
            dynamicOverlays += Polyline(map).apply {
                setPoints(part.map { GeoPoint(it.position.lat, it.position.lon) })
                outlinePaint.color = partColors[i % partColors.size]
                outlinePaint.strokeWidth = 5f
            }
        }
        corners.forEachIndexed { i, c ->
            dynamicOverlays += Marker(map).apply {
                position = GeoPoint(c.lat, c.lon)
                title = "Canto ${i + 1}"
            }
        }
        map.overlays.addAll(map.overlays.indexOf(droneMarker), dynamicOverlays)
        map.invalidate()
    }

    private fun centerOnDrone(force: Boolean) {
        val pos = DroneManager.status.position
        if (pos == null) {
            if (force) toast("Posicao do drone indisponivel.")
            return
        }
        map.controller.setZoom(19.0)
        map.controller.animateTo(GeoPoint(pos.lat, pos.lon))
    }

    // ---- Missao ----

    private fun confirmStart() {
        val p = plan ?: return toast("Gere o plano primeiro.")
        val s = DroneManager.status
        val params = p.params
        val part = p.parts.getOrNull(nextPart) ?: return toast("Todas as partes ja foram executadas. Gere um novo plano.")

        val block = when {
            missionRunning -> "Ja existe uma parte em execucao."
            !s.registered -> "SDK nao registrado: ${s.registrationMessage}"
            !s.connected -> "Drone nao conectado."
            !s.isFlying -> "Decole manualmente e deixe o drone estavel no ar antes de iniciar."
            s.satellites < 8 -> "GPS fraco (${s.satellites} satelites). Minimo: 8."
            s.batteryPercent in 0..29 -> "Bateria em ${s.batteryPercent}%. Minimo: 30%."
            s.position == null -> "Posicao do drone indisponivel."
            else -> {
                val d = Geo.distance(s.position, part.first().position)
                if (d > 150) "O primeiro ponto esta a %.0f m do drone (maximo 150 m).".format(d) else null
            }
        }
        if (block != null) return AlertDialog.Builder(this).setTitle("Nao e possivel iniciar").setMessage(block).setPositiveButton("OK", null).show().let {}

        val msg = "Parte ${nextPart + 1} de ${p.parts.size}  (${part.size} pontos)\n" +
            "Altura %.0f m  |  %.1f m/s\n\n".format(params.altitudeM, params.speedMs) +
            "Confira antes de iniciar:\n" +
            "- Trajeto e margem de 5 m livres de fios, postes, muros e arvores\n" +
            "- Nenhuma pessoa ou animal na area\n" +
            "- Voce esta com o controle remoto na mao e com o drone a vista\n" +
            "- Bateria suficiente; o drone fica parado no ar ao terminar a parte\n\n" +
            "Use PAUSAR ou PARAR a qualquer momento."
        AlertDialog.Builder(this)
            .setTitle("Iniciar missao?")
            .setMessage(msg)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Iniciar") { _, _ -> startPart(p, part) }
            .show()
    }

    private fun startPart(p: Plan, part: List<com.mapadrone.app.core.PlanWaypoint>) {
        if (nextPart == 0) {
            record = FlightRecord(
                id = store.newId(),
                startedAtMs = System.currentTimeMillis(),
                altitudeM = p.params.altitudeM,
                frontOverlap = p.params.frontOverlap,
                sideOverlap = p.params.sideOverlap,
                speedMs = p.params.speedMs,
                endMarginM = p.params.endMarginM,
                corners = corners.toList(),
                lineCount = p.lineCount,
                waypointCount = p.waypointCount,
                partsTotal = p.parts.size
            )
        }
        val rec = record ?: return
        addLog("Parte ${nextPart + 1}/${p.parts.size} enviada (${part.size} pontos).")
        photosBeforePart = rec.photosTriggered
        partPhotos = 0

        DroneManager.prepareCamera { err -> if (err != null) runOnUiThread { addLog("Gimbal/camera: $err") } }
        DroneManager.startPart(part, p.params) { err ->
            if (err != null) {
                addLog("ERRO: $err")
                toast(err)
            } else {
                missionRunning = true
                rec.status = "em andamento"
                store.save(rec)
                addLog("Parte ${nextPart + 1} iniciada.")
            }
        }
    }

    private fun stopMission() {
        DroneManager.stop { err ->
            runOnUiThread {
                missionRunning = false
                record?.let {
                    it.status = "interrompido"
                    it.endedAtMs = System.currentTimeMillis()
                    it.log += "${FlightRecord.format(System.currentTimeMillis())} Missao interrompida pelo usuario${err?.let { e -> " ($e)" } ?: ""}"
                    store.save(it)
                }
                toast(err ?: "Missao parada. O drone fica parado no ar: assuma o controle e pouse.")
                nextPart = 0
            }
        }
    }

    private fun addLog(msg: String) {
        record?.let {
            it.log += "${FlightRecord.format(System.currentTimeMillis())} $msg"
            store.save(it)
        }
    }

    // ---- Callbacks do drone ----

    override fun onStatusChanged(status: DroneStatus) {
        tvStatus.text = buildString {
            append(if (status.registered) "SDK ok" else status.registrationMessage)
            append("  |  ")
            append(if (status.connected) "Drone: ${status.model}" else "Drone desconectado")
            if (status.connected) {
                append("\nGPS ${status.satellites} sat  |  Bateria ")
                append(if (status.batteryPercent >= 0) "${status.batteryPercent}%" else "--")
                append("  |  Alt %.1f m  |  ".format(status.altitudeM))
                append(if (status.isFlying) "voando" else "no solo")
            }
        }
        val pos = status.position
        if (pos != null) {
            droneMarker.position = GeoPoint(pos.lat, pos.lon)
            droneMarker.isEnabled = true
            if (!centeredOnDrone && corners.isEmpty()) {
                centeredOnDrone = true
                centerOnDrone(force = false)
            }
        } else {
            droneMarker.isEnabled = false
        }
        map.invalidate()
    }

    override fun onMissionProgress(photosTriggered: Int, waypointIndex: Int, waypointTotal: Int) {
        partPhotos = photosTriggered
        record?.let { it.photosTriggered = photosBeforePart + photosTriggered }
        tvPlan.text = "Parte ${nextPart + 1}: ponto ${waypointIndex + 1} de $waypointTotal  |  fotos disparadas: $photosTriggered"
    }

    override fun onPartFinished(error: String?) {
        missionRunning = false
        val rec = record
        val p = plan
        if (rec == null || p == null) return
        rec.photosTriggered = photosBeforePart + partPhotos
        if (error != null) {
            rec.status = "erro"
            rec.endedAtMs = System.currentTimeMillis()
            addLog("Parte ${nextPart + 1} terminou com erro: $error")
            toast("Missao terminou com erro: $error")
            nextPart = 0
        } else {
            rec.partsDone = nextPart + 1
            nextPart += 1
            if (nextPart >= p.parts.size) {
                rec.status = "concluido"
                rec.endedAtMs = System.currentTimeMillis()
                addLog("Missao concluida: ${rec.photosTriggered} fotos disparadas.")
                tvPlan.text = "Missao concluida (${rec.id}): ${rec.photosTriggered} fotos disparadas. " +
                    "O drone esta parado no ar: pouse manualmente. Fotos no cartao SD."
                nextPart = 0
            } else {
                rec.status = "aguardando parte ${nextPart + 1}"
                addLog("Parte ${nextPart} concluida; aguardando parte ${nextPart + 1}.")
                tvPlan.text = "Parte $nextPart concluida (${rec.photosTriggered} fotos). " +
                    "Troque a bateria se preciso, decole e toque em Iniciar missao para a parte ${nextPart + 1}."
            }
        }
        store.save(rec)
    }

    override fun onLog(message: String) {
        addLog(message)
    }

    // ---- Historico ----

    private fun showHistory() {
        val records = store.list()
        if (records.isEmpty()) return toast("Nenhum voo registrado ainda.")
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, records.map { it.summary() })
        AlertDialog.Builder(this)
            .setTitle("Historico de voos")
            .setAdapter(adapter) { _, i -> showRecord(records[i]) }
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showRecord(r: FlightRecord) {
        val text = r.toJson().toString(2)
        AlertDialog.Builder(this)
            .setTitle(r.id)
            .setMessage(text)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Compartilhar") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Voo ${r.id}")
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Compartilhar registro"))
            }
            .setNegativeButton("Excluir") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Excluir o registro ${r.id}? As fotos no cartao SD nao sao afetadas.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ -> store.delete(r.id) }
                    .show()
            }
            .show()
    }
}
