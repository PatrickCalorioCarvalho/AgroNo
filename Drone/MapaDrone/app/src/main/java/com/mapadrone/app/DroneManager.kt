package com.mapadrone.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mapadrone.app.core.LatLon
import com.mapadrone.app.core.PlanParams
import com.mapadrone.app.core.PlanWaypoint
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointAction
import dji.common.mission.waypoint.WaypointActionType
import dji.common.mission.waypoint.WaypointExecutionProgress
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionDownloadEvent
import dji.common.mission.waypoint.WaypointMissionExecuteState
import dji.common.mission.waypoint.WaypointMissionExecutionEvent
import dji.common.mission.waypoint.WaypointMissionFinishedAction
import dji.common.mission.waypoint.WaypointMissionFlightPathMode
import dji.common.mission.waypoint.WaypointMissionGotoWaypointMode
import dji.common.mission.waypoint.WaypointMissionHeadingMode
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.mission.waypoint.WaypointMissionUploadEvent
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import dji.common.camera.SettingsDefinitions

/** Estado do drone que a tela mostra. */
data class DroneStatus(
    val registered: Boolean = false,
    val registrationMessage: String = "SDK nao iniciado",
    val connected: Boolean = false,
    val model: String = "",
    val satellites: Int = 0,
    val batteryPercent: Int = -1,
    val position: LatLon? = null,
    val altitudeM: Float = 0f,
    val isFlying: Boolean = false
)

interface DroneListener {
    fun onStatusChanged(status: DroneStatus)
    fun onMissionProgress(photosTriggered: Int, waypointIndex: Int, waypointTotal: Int)
    fun onPartFinished(error: String?)
    fun onLog(message: String)
}

/** Isola todo o uso do DJI Mobile SDK v4 (registro, telemetria, camera/gimbal e missao de waypoints). */
object DroneManager {
    var status = DroneStatus()
        private set
    var listener: DroneListener? = null

    private val main = Handler(Looper.getMainLooper())
    private var registering = false
    private var missionListenerAdded = false
    private var pendingStart = false
    private var startCallback: ((String?) -> Unit)? = null
    private val photoWaypoints = mutableSetOf<Int>()
    private var partTotal = 0

    private fun update(block: (DroneStatus) -> DroneStatus) {
        status = block(status)
        val s = status
        main.post { listener?.onStatusChanged(s) }
    }

    private fun log(msg: String) = main.post { listener?.onLog(msg) }

    private fun done(f: (String?) -> Unit) = CommonCallbacks.CompletionCallback<DJIError> { e -> f(e?.description) }

    private fun aircraft(): Aircraft? = DJISDKManager.getInstance().product as? Aircraft

    // ---- Registro e conexao ----

    fun register(context: Context) {
        if (registering || status.registered) return
        registering = true
        update { it.copy(registrationMessage = "Registrando SDK (precisa de internet)...") }
        DJISDKManager.getInstance().registerApp(context.applicationContext, object : DJISDKManager.SDKManagerCallback {
            override fun onRegister(error: DJIError?) {
                registering = false
                if (error == DJISDKError.REGISTRATION_SUCCESS) {
                    update { it.copy(registered = true, registrationMessage = "SDK registrado") }
                    DJISDKManager.getInstance().startConnectionToProduct()
                } else {
                    update { it.copy(registered = false, registrationMessage = "Falha no registro: ${error?.description}") }
                }
            }

            override fun onProductDisconnect() {
                update { it.copy(connected = false, model = "", satellites = 0, batteryPercent = -1, position = null, isFlying = false) }
            }

            override fun onProductConnect(product: BaseProduct?) = onProduct(product)
            override fun onProductChanged(product: BaseProduct?) = onProduct(product)
            override fun onComponentChange(key: BaseProduct.ComponentKey?, old: BaseComponent?, new: BaseComponent?) {}
            override fun onInitProcess(event: DJISDKInitEvent?, total: Int) {}
            override fun onDatabaseDownloadProgress(current: Long, total: Long) {}
        })
    }

    private fun onProduct(product: BaseProduct?) {
        val air = product as? Aircraft
        if (air == null) {
            update { it.copy(connected = false) }
            return
        }
        update { it.copy(connected = true, model = air.model?.displayName ?: "Drone") }

        air.flightController?.setStateCallback { st ->
            val loc = st.aircraftLocation
            val valid = loc != null && !loc.latitude.isNaN() && !loc.longitude.isNaN() &&
                (loc.latitude != 0.0 || loc.longitude != 0.0)
            update {
                it.copy(
                    satellites = st.satelliteCount,
                    position = if (valid) LatLon(loc.latitude, loc.longitude) else null,
                    altitudeM = if (loc != null && !loc.altitude.isNaN()) loc.altitude else 0f,
                    isFlying = st.isFlying
                )
            }
        }
        air.battery?.setStateCallback { b -> update { it.copy(batteryPercent = b.chargeRemainingInPercent) } }
    }

    // ---- Camera e gimbal ----

    /** Camera em modo foto unica e gimbal apontado para baixo (-90 graus). */
    fun prepareCamera(onResult: (String?) -> Unit) {
        val air = aircraft() ?: return onResult("Drone nao conectado.")
        air.camera?.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, done { err ->
            if (err != null) log("Camera (modo foto): $err")
            air.camera?.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, done { err2 ->
                if (err2 != null) log("Camera (foto unica): $err2")
            })
        })
        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
        air.gimbal?.rotate(rotation, done { onResult(it) }) ?: onResult("Gimbal indisponivel.")
    }

    // ---- Missao ----

    private val operator: WaypointMissionOperator?
        get() = DJISDKManager.getInstance().missionControl?.waypointMissionOperator

    private val missionListener = object : WaypointMissionOperatorListener {
        override fun onDownloadUpdate(event: WaypointMissionDownloadEvent) {}

        override fun onUploadUpdate(event: WaypointMissionUploadEvent) {
            val err = event.error
            if (err != null && pendingStart) {
                pendingStart = false
                finishStart("Falha no envio da missao: ${err.description}")
                return
            }
            if (pendingStart && event.currentState == WaypointMissionState.READY_TO_EXECUTE) {
                pendingStart = false
                operator?.startMission(done { e ->
                    finishStart(e?.let { "Falha ao iniciar: $it" })
                })
            }
        }

        override fun onExecutionUpdate(event: WaypointMissionExecutionEvent) {
            val p: WaypointExecutionProgress = event.progress ?: return
            if (p.executeState == WaypointMissionExecuteState.FINISHED_ACTION) {
                photoWaypoints.add(p.targetWaypointIndex)
            }
            val photos = photoWaypoints.size
            val index = p.targetWaypointIndex
            val total = partTotal
            main.post { listener?.onMissionProgress(photos, index, total) }
        }

        override fun onExecutionStart() {
            log("Missao em execucao.")
        }

        override fun onExecutionFinish(error: DJIError?) {
            val msg = error?.description
            main.post { listener?.onPartFinished(msg) }
        }
    }

    private fun finishStart(error: String?) {
        val cb = startCallback
        startCallback = null
        main.post { cb?.invoke(error) }
    }

    /** Envia e inicia uma parte da missao (ate 99 waypoints). O drone deve ja estar voando. */
    fun startPart(part: List<PlanWaypoint>, params: PlanParams, onStarted: (String?) -> Unit) {
        val op = operator ?: return onStarted("Operador de missao indisponivel (drone conectado?).")
        if (!missionListenerAdded) {
            op.addListener(missionListener)
            missionListenerAdded = true
        }

        val altitude = params.altitudeM.toFloat()
        val waypoints = part.map { wp ->
            Waypoint(wp.position.lat, wp.position.lon, altitude).also {
                it.gimbalPitch = -90f
                // Para, estabiliza e dispara a foto.
                it.addAction(WaypointAction(WaypointActionType.STAY, 700))
                it.addAction(WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0))
            }
        }

        val mission = WaypointMission.Builder()
            .autoFlightSpeed(params.speedMs.toFloat())
            .maxFlightSpeed(10f)
            .setExitMissionOnRCSignalLostEnabled(false)
            // Ao terminar o drone fica parado no ar: voce decide quando voltar, sem sobrevoo automatico surpresa.
            .finishedAction(WaypointMissionFinishedAction.NO_ACTION)
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .headingMode(WaypointMissionHeadingMode.AUTO)
            .setGimbalPitchRotationEnabled(true)
            .repeatTimes(1)
            .waypointList(waypoints)
            .waypointCount(waypoints.size)
            .build()

        val loadError = op.loadMission(mission)
        if (loadError != null) return onStarted("Missao invalida: ${loadError.description}")

        photoWaypoints.clear()
        partTotal = part.size
        startCallback = onStarted
        pendingStart = true
        op.uploadMission(done { e ->
            if (e != null) {
                pendingStart = false
                finishStart("Falha ao enviar missao: $e")
            }
        })
    }

    fun pause(onResult: (String?) -> Unit) {
        operator?.pauseMission(done { onResult(it) }) ?: onResult("Operador indisponivel.")
    }

    fun resume(onResult: (String?) -> Unit) {
        operator?.resumeMission(done { onResult(it) }) ?: onResult("Operador indisponivel.")
    }

    fun stop(onResult: (String?) -> Unit) {
        pendingStart = false
        operator?.stopMission(done { onResult(it) }) ?: onResult("Operador indisponivel.")
    }
}
