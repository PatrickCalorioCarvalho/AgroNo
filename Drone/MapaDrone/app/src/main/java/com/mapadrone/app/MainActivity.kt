package com.mapadrone.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.drawable.BitmapDrawable
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.mapadrone.app.core.Geo
import com.mapadrone.app.core.GridPlanner
import com.mapadrone.app.core.LatLon
import com.mapadrone.app.core.Plan
import com.mapadrone.app.core.PlanParams
import com.mapadrone.app.core.PlanWaypoint
import com.mapadrone.app.data.FlightRecord
import com.mapadrone.app.data.FlightStore
import com.mapadrone.app.data.SyncClient
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
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

private const val BATTERY_PAUSE_PERCENT = 35

class MainActivity : Activity(), DroneListener {

    private lateinit var map: MapView
    private lateinit var tvStatus: TextView
    private lateinit var tvPlan: TextView
    private lateinit var store: FlightStore
    private val photoTransfer by lazy { PhotoTransfer(this, store) { serverUrl } }

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
    private var centeredOnDevice = false
    private var params = PlanParams()
    private var home: LatLon? = null
    private var batteryPaused = false
    private var areaName: String
        get() = getSharedPreferences("cfg", MODE_PRIVATE).getString("area", "") ?: ""
        set(v) { getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("area", v.trim()).apply() }
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private var serverUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(v) { prefs.edit().putString("server", v.trim()).apply() }
    private var resumeNote = ""
    private lateinit var rowEdit: View
    private lateinit var rowMission: View
    private lateinit var videoView: TextureView
    private var codec: DJICodecManager? = null
    private var videoOn = false
    private val videoListener = VideoFeeder.VideoDataListener { data, size -> codec?.sendDataToDecoder(data, size) }
    private var deviceLocation: Location? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            deviceLocation = loc
            if (!centeredOnDevice && !centeredOnDrone && corners.isEmpty()) {
                centeredOnDevice = true
                map.controller.setZoom(18.0)
                map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
            }
        }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

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
        // Mapa escuro: inverte as cores e aumenta o contraste (sobe o fundo, ruas ficam mais escuras que ele).
        val k = 2.2f
        map.overlayManager.tilesOverlay.setColorFilter(
            android.graphics.ColorMatrixColorFilter(
                floatArrayOf(
                    -k, 0f, 0f, 0f, 255f * k + 6f,
                    0f, -k, 0f, 0f, 255f * k + 6f,
                    0f, 0f, -k, 0f, 255f * k + 6f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
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
        rowEdit = findViewById(R.id.rowEdit)
        rowMission = findViewById(R.id.rowMission)
        tvPlan.setOnClickListener { tvPlan.maxLines = if (tvPlan.maxLines <= 4) 16 else 4 }
        button(R.id.btnConfig) { showConfig() }
        button(R.id.btnHome) { setHome() }
        button(R.id.btnMore) { v ->
            PopupMenu(this, v).apply {
                menu.add(0, 1, 0, "Camera para baixo")
                menu.add(0, 2, 1, "Historico de voos")
                menu.add(0, 3, 2, "Remover ponto H")
                menu.add(0, 4, 3, "Continuar voo anterior")
                menu.add(0, 5, 4, "Sincronizar voos com o servidor")
                menu.add(0, 6, 5, "Enviar fotos do voo (pelo drone)")
                setOnMenuItemClickListener {
                    if (it.itemId == 1) DroneManager.prepareCamera { err -> runOnUiThread { toast(err?.let { e -> "Gimbal/camera: $e" } ?: "Camera apontada para baixo.") } }
                    else if (it.itemId == 4) resumePrevious()
                    else if (it.itemId == 5) syncPending(manual = true)
                    else if (it.itemId == 6) photoTransfer.start(
                        if (missionRunning) "Pare a missao antes de baixar as fotos."
                        else if (DroneManager.status.isFlying) "Pouse o drone antes de baixar as fotos."
                        else null)
                    else if (it.itemId == 3) { home = null; redraw(); toast("Ponto H removido.") }
                    else showHistory()
                    true
                }
            }.show()
        }
        videoView = findViewById(R.id.videoView)
        videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (codec == null) codec = DJICodecManager(this@MainActivity, st, w, h)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { codec?.onSurfaceSizeChanged(w, h, 0) }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { codec?.cleanSurface(); codec = null; return false }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        button(R.id.btnVideo) { toggleVideo() }
        button(R.id.btnCenter) { centerOnDrone(force = true) }
        button(R.id.btnStart) { confirmStart() }
        button(R.id.btnPause) { DroneManager.pause { err -> runOnUiThread { toast(err ?: "Missao pausada.") } } }
        button(R.id.btnResume) { DroneManager.resume { err -> runOnUiThread { toast(err ?: "Missao retomada.") } } }
        button(R.id.btnStop) { stopMission() }

        DroneManager.listener = this
        onStatusChanged(DroneManager.status)
        invalidatePlan()
        requestPermissionsThenRegister()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        syncPending()
        startDeviceLocation()
        DroneManager.listener = this
        onStatusChanged(DroneManager.status)
    }

    override fun onPause() {
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(videoListener)
        codec?.destroyCodec(); codec = null
        if (DroneManager.listener === this) DroneManager.listener = null
        super.onDestroy()
    }

    private fun toggleVideo() {
        if (!DroneManager.status.connected) { toast("Drone nao conectado."); return }
        val feed = VideoFeeder.getInstance()?.primaryVideoFeed
        if (feed == null) { toast("Video indisponivel neste drone."); return }
        videoOn = !videoOn
        if (videoOn) {
            videoView.visibility = View.VISIBLE
            feed.addVideoDataListener(videoListener)
        } else {
            feed.removeVideoDataListener(videoListener)
            videoView.visibility = View.GONE
        }
    }

    private fun button(id: Int, action: (View) -> Unit) = findViewById<Button>(id).setOnClickListener { action(it) }

    /** Mostra so as acoes que fazem sentido: edicao da area, ou controle da missao quando ha plano. */
    private fun updateBars() {
        rowEdit.visibility = if (missionRunning) View.GONE else View.VISIBLE
        rowMission.visibility = if (plan != null || missionRunning) View.VISIBLE else View.GONE
        // Com plano gerado o painel fica compacto: so o que ainda faz sentido antes de voar.
        val hasPlan = plan != null
        for (id in intArrayOf(R.id.btnMark, R.id.btnUndo, R.id.btnPlan)) findViewById<View>(id).visibility = if (hasPlan) View.GONE else View.VISIBLE
    }

    /** Envia ao servidor os voos ainda nao sincronizados (rota + registro). Sem URL configurada, nao faz nada. */
    private fun syncPending(manual: Boolean = false) {
        val url = serverUrl
        if (url.isBlank()) { if (manual) toast("Defina o servidor em Config."); return }
        val active = if (missionRunning) record?.id else null
        val pending = store.list().filter { !it.synced && it.id != active }
        if (pending.isEmpty()) { if (manual) toast("Todos os voos ja estao sincronizados."); return }
        Thread {
            var ok = 0
            var fail: String? = null
            for (r in pending) {
                val err = SyncClient.postFlight(url, r.toJson().toString())
                if (err != null) { fail = err; break }
                r.synced = true
                store.save(r, keepSynced = true)
                ok++
            }
            runOnUiThread {
                if (ok > 0) toast("$ok voo(s) enviado(s) ao servidor.")
                else if (fail != null && manual) toast("Falha ao enviar ao servidor: $fail")
            }
        }.start()
    }

    /** Recria o plano do ultimo voo interrompido e pula os pontos que ja foram fotografados. */
    private fun resumePrevious() {
        if (missionRunning) return toast("Pare a missao atual primeiro.")
        val open = setOf("interrompido", "incompleto", "erro", "em andamento", "pausado por bateria")
        val rec = store.list().firstOrNull { it.status in open && (it.photosTriggered > 0 || it.resumeIndex > 0) && it.photosTriggered < it.waypointCount }
            ?: return toast("Nenhum voo interrompido para continuar.")
        // Voos sem rota gravada foram gerados pelo planejador antigo (retangulo): reproduz o mesmo plano para pular os pontos certos.
        val prm = PlanParams(rec.altitudeM, rec.frontOverlap, rec.sideOverlap, rec.speedMs, rec.endMarginM, clipToPolygon = rec.route.isNotEmpty())
        val full = try { GridPlanner.plan(rec.corners, prm) } catch (e: IllegalArgumentException) { return toast(e.message ?: "Nao foi possivel recriar o plano.") }
        // Volta 2 pontos para garantir sobreposicao com o que ja foi fotografado.
        val skip = (maxOf(rec.resumeIndex, rec.photosTriggered) - 2).coerceAtLeast(0)
        val resumed = GridPlanner.resume(full, skip)
        AlertDialog.Builder(this)
            .setTitle("Continuar voo ${rec.id}?")
            .setMessage("Foram ${rec.photosTriggered} fotos de ${full.waypointCount}.\nO plano recomeca no ponto ${skip + 1} (${resumed.waypointCount} pontos restantes, ~${(resumed.estimatedSeconds / 60).roundToInt()} min).\nUse bateria cheia. As fotos novas vao se somar as do voo anterior no cartao SD.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Carregar plano") { _, _ ->
                corners.clear(); corners.addAll(rec.corners)
                params = prm
                plan = resumed
                nextPart = 0
                resumeNote = "Continuacao de ${rec.id} a partir do ponto ${skip + 1}"
                areaName = rec.areaName.ifBlank { areaName }
                tvPlan.text = "$resumeNote. ${resumed.waypointCount} pontos, ${resumed.parts.size} parte(s). Decole e toque em Iniciar missao."
                updateBars(); redraw()
                map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(corners.map { GeoPoint(it.lat, it.lon) }), true, 80)
            }
            .show()
    }

    /** Define o ponto H (decolagem e pouso): posicao do drone se tiver GPS, senao a do tablet, senao o centro do mapa. */
    private fun setHome() {
        if (missionRunning) return toast("Pare a missao antes de mudar o ponto H.")
        val s = DroneManager.status
        val dev = deviceLocation
        val (pos, origem) = when {
            s.connected && s.position != null && s.satellites >= 6 -> s.position!! to "do drone"
            dev != null -> LatLon(dev.latitude, dev.longitude) to "do tablet"
            else -> (map.mapCenter as GeoPoint).let { LatLon(it.latitude, it.longitude) } to "do centro do mapa"
        }
        home = pos
        redraw()
        toast("Ponto H definido na posicao $origem. Segure o marcador H no mapa e arraste para ajustar.")
    }

    private fun homeIcon(): BitmapDrawable {
        val size = 84
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(13, 71, 161); c.drawCircle(size / 2f, size / 2f, size / 2f - 2, p)
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 5f; c.drawCircle(size / 2f, size / 2f, size / 2f - 5, p)
        p.style = Paint.Style.FILL; p.textSize = 52f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        c.drawText("H", size / 2f, size / 2f + 18f, p)
        return BitmapDrawable(resources, bmp)
    }

    private fun showConfig() {
        if (missionRunning) return toast("Pare a missao antes de mudar a configuracao.")
        val v = layoutInflater.inflate(R.layout.dialog_config, null)
        fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        v.findViewById<EditText>(R.id.etArea).setText(areaName)
        v.findViewById<EditText>(R.id.etAlt).setText(fmt(params.altitudeM))
        v.findViewById<EditText>(R.id.etFront).setText(fmt(params.frontOverlap * 100))
        v.findViewById<EditText>(R.id.etSide).setText(fmt(params.sideOverlap * 100))
        v.findViewById<EditText>(R.id.etSpeed).setText(fmt(params.speedMs))
        v.findViewById<EditText>(R.id.etMargin).setText(fmt(params.endMarginM))
        v.findViewById<EditText>(R.id.etServer).setText(serverUrl)
        fun n(id: Int) = v.findViewById<EditText>(id).text.toString().trim().replace(',', '.').toDoubleOrNull()
        AlertDialog.Builder(this)
            .setTitle("Configuracao do voo")
            .setView(v)
            .setNeutralButton("Padrao") { _, _ -> params = PlanParams(); invalidatePlan(); toast("Valores padrao restaurados.") }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val a = n(R.id.etAlt); val f = n(R.id.etFront); val sd = n(R.id.etSide); val sp = n(R.id.etSpeed); val m = n(R.id.etMargin)
                if (a == null || f == null || sd == null || sp == null || m == null) return@setPositiveButton toast("Preencha todos os campos com numeros.")
                serverUrl = v.findViewById<EditText>(R.id.etServer).text.toString()
                areaName = v.findViewById<EditText>(R.id.etArea).text.toString()
                val np = PlanParams(a, f / 100.0, sd / 100.0, sp, m)
                np.validate()?.let { return@setPositiveButton toast(it) }
                params = np
                if (plan != null) { invalidatePlan(); toast("Configuracao salva. Gere o plano de novo.") } else toast("Configuracao salva.")
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- Permissoes e registro ----

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private fun requestPermissionsThenRegister() {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { DroneManager.register(this); startDeviceLocation() } else requestPermissions(missing.toTypedArray(), 1)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startDeviceLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!lm.isProviderEnabled(prov)) continue
                lm.getLastKnownLocation(prov)?.let { if (deviceLocation == null) locationListener.onLocationChanged(it) }
                lm.requestLocationUpdates(prov, 3000L, 1f, locationListener)
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        DroneManager.register(this)
        startDeviceLocation()
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
        updateBars()
        redraw()
    }

    private fun generatePlan() {
        if (missionRunning) return toast("Pare a missao antes de gerar um novo plano.")
        val params = this.params
        try {
            val p = GridPlanner.plan(corners, params)
            plan = p
            nextPart = 0
            val minutes = (p.estimatedSeconds / 60).roundToInt()
            tvPlan.text = buildString {
                if (areaName.isNotBlank()) { append("Area: $areaName"); append('\n') }
                append("Area ~%.0f x %.0f m  |  %d linhas  |  %d fotos\n".format(p.areaLengthM, p.areaWidthM, p.lineCount, p.waypointCount))
                append("Cada foto cobre %.1f x %.1f m  |  %.2f cm/pixel\n".format(p.footprintAcrossM, p.footprintAlongM, p.gsdCm))
                append("Foto a cada %.1f m  |  linhas a cada %.1f m  |  rumo %.0f graus\n".format(p.photoSpacingM, p.lineSpacingM, p.flightBearingDeg))
                append("Distancia %.0f m  |  tempo estimado ~%d min\n".format(p.totalDistanceM, minutes))
                append("Partes: ${p.parts.size} (limite de ${p.params.maxWaypointsPerPart} pontos por missao)")
                if (minutes > 15) append("\nATENCAO: tempo longo para uma bateria do Mavic Pro. Troque a bateria entre as partes.")
            }
            updateBars()
            redraw()
            map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(corners.map { GeoPoint(it.lat, it.lon) }), true, 80)
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: "Nao foi possivel gerar o plano."
            tvPlan.text = "Plano NAO gerado: $msg" + "\n" + "Marque uma area menor (zoom no mapa), aumente a altura ou diminua as sobreposicoes em Config."
            tvPlan.maxLines = 4
            toast(msg)
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
        plan?.parts?.forEachIndexed { i, part ->
            dynamicOverlays += WaypointsOverlay(part.map { GeoPoint(it.position.lat, it.position.lon) }, partColors[i % partColors.size], first = i == 0)
        }
        val h = home
        val pl = plan
        if (h != null && pl != null) {
            fun leg(a: LatLon, b: LatLon) = Polyline(map).apply {
                setPoints(listOf(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)))
                outlinePaint.color = Color.rgb(13, 71, 161)
                outlinePaint.strokeWidth = 4f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
            }
            dynamicOverlays += leg(h, pl.parts.first().first().position)
            dynamicOverlays += leg(pl.parts.last().last().position, h)
        }
        if (h != null) {
            dynamicOverlays += Marker(map).apply {
                position = GeoPoint(h.lat, h.lon)
                title = "Decolagem / pouso (H)"
                icon = homeIcon()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(m: Marker) {}
                    override fun onMarkerDrag(m: Marker) {}
                    override fun onMarkerDragEnd(m: Marker) { home = LatLon(m.position.latitude, m.position.longitude); map.post { redraw() } }
                })
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
            val dev = deviceLocation
            if (dev != null) {
                map.controller.setZoom(18.0)
                map.controller.animateTo(GeoPoint(dev.latitude, dev.longitude))
            } else if (force) toast("Posicao indisponivel (drone sem GPS e tablet sem localizacao).")
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
            "- Bateria suficiente; o drone fica parado no ar ao terminar a parte\n" +
            (if (home != null && nextPart == p.parts.lastIndex) "- Ao final o drone vai ao ponto H (pouso) e fica parado no ar; voce pousa manualmente\n" else "") + "\n" +
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
                partsTotal = p.parts.size,
                notes = resumeNote
            ).also { r ->
                r.route = p.parts.flatten().map { it.position }
                r.areaName = areaName
                r.home = home
            }
        }
        val rec = record ?: return
        addLog("Parte ${nextPart + 1}/${p.parts.size} enviada (${part.size} pontos).")
        photosBeforePart = rec.photosTriggered
        partPhotos = 0

        DroneManager.prepareCamera { err -> if (err != null) runOnUiThread { addLog("Gimbal/camera: $err") } }
        val sendPart: List<PlanWaypoint> = home?.takeIf { nextPart == p.parts.lastIndex }?.let { part + PlanWaypoint(it, -1, photo = false) } ?: part
        DroneManager.startPart(sendPart, p.params) { err ->
            if (err != null) {
                addLog("ERRO: $err")
                toast(err)
            } else {
                missionRunning = true
                runOnUiThread { updateBars() }
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
                updateBars()
                record?.let {
                    it.status = "interrompido"
                    it.endedAtMs = System.currentTimeMillis()
                    it.log += "${FlightRecord.format(System.currentTimeMillis())} Missao interrompida pelo usuario${err?.let { e -> " ($e)" } ?: ""}"
                    store.save(it)
                }
                toast(err ?: "Missao parada. O drone fica parado no ar: assuma o controle e pouse.")
                nextPart = 0
                syncPending()
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
        if (!missionRunning) batteryPaused = false
        if (missionRunning && !batteryPaused && status.batteryPercent in 0..BATTERY_PAUSE_PERCENT) {
            batteryPaused = true
            DroneManager.pause { }
            record?.let { it.status = "pausado por bateria"; addLog("Bateria em ${status.batteryPercent}%: missao pausada automaticamente."); store.save(it) }
            AlertDialog.Builder(this)
                .setTitle("Bateria baixa: ${status.batteryPercent}%")
                .setMessage("A missao foi PAUSADA automaticamente.\n\nVolte ao ponto H e pouse, troque a bateria e use Mais > Continuar voo anterior. Se o drone comecar o retorno automatico da DJI, deixe-o terminar.")
                .setPositiveButton("OK", null)
                .show()
        }
        tvStatus.text = buildString {
            append(if (status.registered) "SDK ok" else status.registrationMessage)
            append('\n')
            append(if (status.connected) "Drone: ${status.model}" else "Drone desconectado")
            if (status.connected) {
                append('\n')
                append("GPS ${status.satellites} sat  |  Bateria ")
                append(if (status.batteryPercent >= 0) "${status.batteryPercent}%" else "--")
                append('\n')
                append("Alt %.1f m  |  ".format(status.altitudeM))
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
        record?.let {
            it.photosTriggered = photosBeforePart + photosTriggered
            it.resumeIndex = (plan?.parts?.take(nextPart)?.sumOf { part -> part.size } ?: 0) + photosTriggered
        }
        tvPlan.text = "Parte ${nextPart + 1}: ponto ${waypointIndex + 1} de $waypointTotal  |  fotos disparadas: $photosTriggered"
    }

    override fun onPartFinished(error: String?) {
        handlePartFinished(error)
        syncPending()
    }

    private fun handlePartFinished(error: String?) {
        missionRunning = false
        updateBars()
        val rec = record
        val p = plan
        if (rec == null || p == null) return
        rec.photosTriggered = photosBeforePart + partPhotos
        val expected = p.parts.getOrNull(nextPart)?.count { it.photo } ?: 0
        if (error == null && partPhotos < expected - 1) {
            rec.status = "incompleto"
            rec.endedAtMs = System.currentTimeMillis()
            addLog("Parte ${nextPart + 1} terminou antes do fim: $partPhotos de $expected fotos.")
            store.save(rec)
            toast("A missao terminou antes do fim ($partPhotos de $expected fotos). Troque a bateria e use Mais > Continuar voo anterior.")
            tvPlan.text = "INCOMPLETA: $partPhotos de $expected fotos. Troque a bateria e use Mais > Continuar voo anterior."
            nextPart = 0
            return
        }
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

/** Desenha um ponto por waypoint (cada um e uma foto); o primeiro da missao fica maior e verde. */
private class WaypointsOverlay(private val pts: List<GeoPoint>, color: Int, private val first: Boolean) : org.osmdroid.views.overlay.Overlay() {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val start = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.rgb(46, 125, 50); style = Paint.Style.FILL }
    private val tmp = Point()

    override fun draw(canvas: Canvas, mapView: org.osmdroid.views.MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        pts.forEachIndexed { i, g ->
            proj.toPixels(g, tmp)
            if (first && i == 0) {
                canvas.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), 16f, start)
                canvas.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), 16f, ring)
            } else {
                canvas.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), 7f, dot)
                canvas.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), 7f, ring)
            }
        }
    }
}
