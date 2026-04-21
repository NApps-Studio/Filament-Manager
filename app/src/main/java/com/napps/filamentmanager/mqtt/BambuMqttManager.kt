package com.napps.filamentmanager.mqtt

import android.content.Context
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAckReturnCode
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * HMS (Health Management System) Error Code Definitions for Bambu Lab printers.
 * These codes are part of the 'hms' array in the printer's JSON telemetry.
 */
object HMSCodes {
    /** Error code triggered when filament runs out during a print. */
    const val FILAMENT_RUNOUT = 50332171L
    /** Error code triggered when the AMS fails to feed filament to the toolhead. */
    const val AMS_FAILED_TO_FEED = 50332167L
    /** Error code for AMS motor overload, often due to a stuck spool. */
    const val MOTOR_OVERLOAD = 50332161L
    
    /** Specific HMS code for filament runout: 0x07012300 */
    const val HMS_RUNOUT_CODE = 0x07012300L
    /** Specific Print Error code for filament runout: 117538833 */
    const val PRINT_ERROR_RUNOUT = 117538833L

    /**
     * Checks if the provided list of HMS codes contains any filament-related runout or feed failures.
     */
    fun isRunout(hmsList: List<Long>, printError: Long = 0L): Boolean {
        return hmsList.contains(FILAMENT_RUNOUT) || 
               hmsList.contains(AMS_FAILED_TO_FEED) || 
               hmsList.contains(HMS_RUNOUT_CODE) ||
               printError == PRINT_ERROR_RUNOUT
    }
}

/**
 * Comprehensive state representation of a Bambu Lab printer.
 * This data class aggregates telemetry from the 'print', 'ams', and 'vt_tray' sections
 * of the MQTT JSON payload.
 */
data class BambuState(
    val serial: String = "",
    val printerName: String = "My Printer",
    val devName: String = "",
    val wifiSignal: String = "0dBm",
    val nozzleTemp: Double = 0.0,
    val nozzleTarget: Double = 0.0,
    val bedTemp: Double = 0.0,
    val bedTarget: Double = 0.0,
    val chamberTemp: Double = 0.0,
    /** Print progress percentage (0-100). */
    val progress: Int = 0,
    /** Remaining print time in minutes. */
    val remainingTime: Int = 0,
    /** List of active HMS error codes. */
    val hmsList: List<Long> = emptyList(),
    /** Current G-code execution state (e.g., "RUNNING", "PAUSE", "IDLE"). */
    val gcodeState: String = "IDLE",
    /** Detailed printing stage index/string. */
    val printStage: String = "",
    val currentLayer: Int = 0,
    val totalLayers: Int = 0,
    /** Name of the current print task. */
    val subtaskName: String = "",
    /** Filename of the current G-code being printed. */
    val gcodeFile: String = "",
    val nozzleDiameter: String = "",
    val nozzleType: String = "",
    /** Binary status of the AMS system. */
    val amsStatus: Int = 0,
    /** Speed multiplier percentage (e.g., 100 for normal, 124 for sport). */
    val speedMag: Int = 100,
    /** Speed level index (1: Silent, 2: Normal, 3: Sport, 4: Ludicrous). */
    val speedLvl: Int = 2,
    /** List of AMS units attached to the printer. */
    val amsUnits: List<AmsUnitReport> = emptyList(),
    // Bitmask strings representing hardware status
    val amsExistBits: String = "",
    val trayExistBits: String = "",
    val trayIsBblBits: String = "",
    val trayTar: String = "",
    val trayNow: String = "",
    val trayPre: String = "",
    val trayReadDoneBits: String = "",
    val trayReadingBits: String = "",
    val amsSystemVersion: Int = 0,
    val amsInsertFlag: Boolean = false,
    val amsPowerOnFlag: Boolean = false,
    /** External/Manual spool details if no AMS is used or for the bypass. */
    val vtTray: VirtualTrayDetail? = null,
    val lightsReport: List<LightReport> = emptyList(),
    /** The last command processed by the printer. */
    val command: String = "",
    val msg: Int = 0,
    /** The sequence ID of the last received message, used for request/response tracking. */
    val sequenceId: String = "",
    /** Timestamp of the last time AMS-specific data was updated. */
    val lastAmsUpdate: Long? = null,
    val lastWorkerSync: Long? = null,
    /** MQTT connection status. */
    val isConnected: Boolean = false,
    val connectionStatus: String = "Disconnected",
    val errorMessage: String? = null,
    /** Current print error code from telemetry. */
    val printError: Long = 0L,
    /** Timestamp of the last received MQTT message. */
    val lastUpdate: Long? = null
)

/**
 * Represents a single AMS (Automatic Material System) unit, which can contain up to 4 trays.
 *
 * @property amsId The unique ID of the AMS unit as reported by the printer.
 * @property amsIndex The index of the AMS unit (e.g., "0", "1").
 * @property temperature The internal temperature of the AMS unit.
 * @property humidity The internal humidity of the AMS unit.
 * @property trays The list of [AmsTrayDetail] objects representing the 4 trays in this unit.
 */
data class AmsUnitReport(
    val amsId: String,
    val amsIndex: String,
    val temperature: String,
    val humidity: String,
    val trays: List<AmsTrayDetail>
)

/**
 * Detailed information for a single filament tray within an AMS.
 *
 * @property trayIndex The index of the tray within the AMS unit (0-3).
 * @property type The type of filament (e.g., PLA, ABS, PETG).
 * @property subBrand The specific sub-brand or material line (e.g., "Bambu PLA Basic").
 * @property trayIdName The human-readable name of the filament.
 * @property trayInfoIdx The internal index used by the printer to identify the filament profile.
 * @property colorHex The hex color code of the filament.
 * @property remain Estimated remaining filament percentage (0-100).
 * @property weight The estimated weight of the remaining filament.
 * @property trayDiameter The diameter of the filament tray/spool.
 * @property trayTemp The recommended printing temperature for this tray.
 * @property trayTime The time remaining for this tray's current operation.
 * @property tagUid The unique UID read from the filament's RFID tag (if BBL filament).
 * @property uuid Unique identifier for the filament, often matching [tagUid] or derived.
 * @property nozzleTempMax The maximum recommended nozzle temperature for this filament.
 * @property nozzleTempMin The minimum recommended nozzle temperature for this filament.
 * @property state Status state of the tray (e.g., feeding, empty, ready).
 */
data class AmsTrayDetail(
    val trayIndex: String,
    val type: String,
    val subBrand: String,
    val trayIdName: String,
    val trayInfoIdx: String,
    val colorHex: String,
    val remain: Int,
    val weight: String,
    val trayDiameter: String,
    val trayTemp: String,
    val trayTime: String,
    val tagUid: String,
    val uuid: String,
    val nozzleTempMax: Int,
    val nozzleTempMin: Int,
    val state: Int = 0
)

/**
 * Details for the 'virtual tray' (manual spool holder or external feed).
 * Used when the AMS is bypassed or not present.
 */
data class VirtualTrayDetail(
    val id: String,
    val tagUid: String,
    val trayIdName: String,
    val trayInfoIdx: String,
    val trayType: String,
    val traySubBrands: String,
    val trayColor: String,
    val trayWeight: String,
    val trayDiameter: String,
    val trayTemp: String,
    val trayTime: String,
    val nozzleTempMax: Int,
    val nozzleTempMin: Int,
    val remain: Int,
    val uuid: String
)

/**
 * Status of the printer's lighting components (e.g., chamber light, toolhead light).
 */
data class LightReport(
    val node: String,
    val mode: String
)

/**
 * Manages MQTT communication with a Bambu Lab printer.
 * Handles authentication, automatic reconnection, and JSON telemetry parsing.
 *
 * @property host The IP address or hostname of the printer.
 * @property userName The MQTT username (usually 'bblp').
 * @property accessToken The printer's access code.
 * @property serialNumber The printer's unique serial number, used for topic filtering.
 * @property onStateUpdate Callback invoked whenever the printer's state is updated.
 */
class BambuMqttManager(
    private val context: Context,
    private val host: String,
    private val userName: String,
    private val accessToken: String,
    private val serialNumber: String,
    private val initialState: BambuState? = null,
    private val onStateUpdate: (BambuState, String) -> Unit,
    private val onConnectionError: (String) -> Unit,
) {
    private var lastKnownState = (initialState ?: BambuState(serial = serialNumber)).copy(
        isConnected = false,
        connectionStatus = "Disconnected"
    )
    private var client: Mqtt3AsyncClient? = null
    private var lastSequenceId: Int = initialState?.sequenceId?.toIntOrNull() ?: -1

    private val maskedSerial = if (serialNumber.length > 6) {
        "${serialNumber.take(3)}...${serialNumber.takeLast(3)}"
    } else serialNumber

    fun isConnected(): Boolean = client?.state == MqttClientState.CONNECTED

    companion object {
        /**
         * Parses the raw JSON telemetry string from the printer into a [BambuState] object.
         * The parser is additive; if a field is missing in the current JSON, it retains
         * the value from [currentState].
         *
         * @param jsonStr The raw JSON payload received via MQTT.
         * @param serial The printer's serial number.
         * @param currentState The existing state to merge with the new data.
         * @return An updated [BambuState] or null if parsing fails fundamentally.
         */
        fun parseRawJson(jsonStr: String, serial: String, currentState: BambuState? = null): BambuState? {
            try {
                val baseState = currentState ?: BambuState(serial = serial)
                if (jsonStr == "{}" || jsonStr.isBlank()) return baseState

                val root = try {
                    JSONObject(jsonStr)
                } catch (e: Exception) { return null }
                val print = root.optJSONObject("print")
                if (print == null) return baseState

                val subName = print.optString("subtask_name", baseState.subtaskName)
                val devName = print.optString("dev_name", baseState.devName)
                val gFile = print.optString("gcode_file", baseState.gcodeFile)
                val nTemp = print.optDouble("nozzle_temper", baseState.nozzleTemp)
                print.optDouble("nozzle_target_temper", baseState.nozzleTarget)
                val bTemp = print.optDouble("bed_temper", baseState.bedTemp)
                val bTarget = print.optDouble("bed_target_temper", baseState.bedTarget)
                val cTemp = print.optDouble("chamber_temper", baseState.chamberTemp)
                val layer = print.optInt("layer_num", baseState.currentLayer)
                val totalL = print.optInt("total_layer_num", baseState.totalLayers)
                val stage = print.optString("mc_print_stage", baseState.printStage)
                val remain = print.optInt("mc_remaining_time", baseState.remainingTime)
                val nDiam = print.optString("nozzle_diameter", baseState.nozzleDiameter)
                val nType = print.optString("nozzle_type", baseState.nozzleType)
                val amsStat = print.optInt("ams_status", baseState.amsStatus)
                val sMag = print.optInt("spd_mag", baseState.speedMag)
                val sLvl = print.optInt("spd_lvl", baseState.speedLvl)
                val cmd = print.optString("command", baseState.command)
                val messageId = print.optInt("msg", baseState.msg)
                val seqId = print.optString("sequence_id", baseState.sequenceId)

                val hmsList = parseHmsList(print.optJSONArray("hms"), baseState.hmsList)
                val pError = print.optLong("print_error", baseState.printError)

                val updatedProgress = try {
                    val progressStr = print.optString("mc_percent", baseState.progress.toString())
                    progressStr.toDoubleOrNull()?.toInt() ?: baseState.progress
                } catch (e: Exception) {
                    baseState.progress
                }

                var updatedAmsUnits = baseState.amsUnits
                var amsUpdateTimestamp = baseState.lastAmsUpdate

                var existBits = baseState.amsExistBits
                var tExistBits = baseState.trayExistBits
                var tBblBits = baseState.trayIsBblBits
                var trayTar = baseState.trayTar
                var trayNow = baseState.trayNow
                var trayPre = baseState.trayPre
                var trayReadDoneBits = baseState.trayReadDoneBits
                var trayReadingBits = baseState.trayReadingBits
                var aVer = baseState.amsSystemVersion
                var iFlag = baseState.amsInsertFlag
                var pFlag = baseState.amsPowerOnFlag

                val amsRoot = print.optJSONObject("ams")
                if (amsRoot != null) {
                    updatedAmsUnits = parseAmsUnits(amsRoot, updatedAmsUnits)
                    amsUpdateTimestamp = System.currentTimeMillis()

                    existBits = amsRoot.optString("ams_exist_bits", existBits)
                    tExistBits = amsRoot.optString("tray_exist_bits", tExistBits)
                    tBblBits = amsRoot.optString("tray_is_bbl_bits", tBblBits)
                    trayTar = amsRoot.optString("tray_tar", trayTar)
                    trayNow = amsRoot.optString("tray_now", trayNow)
                    trayPre = amsRoot.optString("tray_pre", trayPre)
                    trayReadDoneBits = amsRoot.optString("tray_read_done_bits", trayReadDoneBits)
                    trayReadingBits = amsRoot.optString("tray_reading_bits", trayReadingBits)
                    aVer = amsRoot.optInt("version", aVer)
                    iFlag = amsRoot.optBoolean("insert_flag", iFlag)
                    pFlag = amsRoot.optBoolean("power_on_flag", pFlag)
                }

                val vtTrayJson = print.optJSONObject("vt_tray")
                val updatedVtTray = if (vtTrayJson != null) {
                    VirtualTrayDetail(
                        id = vtTrayJson.optString("id"),
                        tagUid = vtTrayJson.optString("tag_uid"),
                        trayIdName = vtTrayJson.optString("tray_id_name"),
                        trayInfoIdx = vtTrayJson.optString("tray_info_idx"),
                        trayType = vtTrayJson.optString("tray_type"),
                        traySubBrands = vtTrayJson.optString("tray_sub_brands"),
                        trayColor = vtTrayJson.optString("tray_color"),
                        trayWeight = vtTrayJson.optString("tray_weight"),
                        trayDiameter = vtTrayJson.optString("tray_diameter"),
                        trayTemp = vtTrayJson.optString("tray_temp"),
                        trayTime = vtTrayJson.optString("tray_time"),
                        nozzleTempMax = vtTrayJson.optInt("nozzle_temp_max"),
                        nozzleTempMin = vtTrayJson.optInt("nozzle_temp_min"),
                        remain = vtTrayJson.optInt("remain"),
                        uuid = vtTrayJson.optString("tray_uuid")
                    )
                } else baseState.vtTray

                val lightsArray = print.optJSONArray("lights_report")
                val updatedLights = if (lightsArray != null) {
                    val list = mutableListOf<LightReport>()
                    for (i in 0 until lightsArray.length()) {
                        val obj = lightsArray.getJSONObject(i)
                        list.add(LightReport(obj.optString("node"), obj.optString("mode")))
                    }
                    list
                } else baseState.lightsReport

                return baseState.copy(
                    printerName = devName.ifEmpty { baseState.printerName.ifEmpty { "Printer $serial" } },
                    subtaskName = subName,
                    devName = devName,
                    gcodeFile = gFile,
                    nozzleTemp = nTemp,
                    nozzleTarget = nTemp, // Assuming target might follow current if missing
                    bedTemp = bTemp,
                    bedTarget = bTarget,
                    chamberTemp = cTemp,
                    wifiSignal = print.optString("wifi_signal", baseState.wifiSignal),
                    hmsList = hmsList,
                    gcodeState = print.optString("gcode_state", baseState.gcodeState),
                    printStage = stage,
                    progress = updatedProgress,
                    remainingTime = remain,
                    currentLayer = layer,
                    totalLayers = totalL,
                    nozzleDiameter = nDiam,
                    nozzleType = nType,
                    amsStatus = amsStat,
                    speedMag = sMag,
                    speedLvl = sLvl,
                    amsUnits = updatedAmsUnits,
                    amsExistBits = existBits,
                    trayExistBits = tExistBits,
                    trayIsBblBits = tBblBits,
                    trayTar = trayTar,
                    trayNow = trayNow,
                    trayPre = trayPre,
                    trayReadDoneBits = trayReadDoneBits,
                    trayReadingBits = trayReadingBits,
                    amsSystemVersion = aVer,
                    amsInsertFlag = iFlag,
                    amsPowerOnFlag = pFlag,
                    vtTray = updatedVtTray,
                    lightsReport = updatedLights,
                    command = cmd,
                    msg = messageId,
                    sequenceId = seqId,
                    printError = pError,
                    lastAmsUpdate = amsUpdateTimestamp,
                    lastUpdate = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e("BambuParser", "Parse Error: ${e.message}")
                return null
            }
        }

        /**
         * Extracts and parses the HMS (Health Management System) error list from a JSON array.
         *
         * @param hmsArray The JSONArray containing HMS data from the telemetry.
         * @param current The current list of HMS codes to fallback to if the array is null.
         * @return A list of Long values representing the active HMS error codes.
         */
        private fun parseHmsList(hmsArray: JSONArray?, current: List<Long>): List<Long> {
            if (hmsArray == null) return current
            val list = mutableListOf<Long>()
            for (i in 0 until hmsArray.length()) {
                val attr = hmsArray.optJSONObject(i)?.optLong("attr") ?: continue
                list.add(attr)
            }
            return list
        }

        /**
         * Parses AMS-specific telemetry data into a list of [AmsUnitReport]s.
         * This function merges new AMS data with existing state to ensure continuity.
         *
         * @param amsRoot The root JSONObject for the 'ams' section of the telemetry.
         * @param currentAms The existing list of AMS units to merge with.
         * @return An updated list of [AmsUnitReport] objects.
         */
        private fun parseAmsUnits(amsRoot: JSONObject, currentAms: List<AmsUnitReport>): List<AmsUnitReport> {
            val amsArray = amsRoot.optJSONArray("ams") ?: return currentAms
            val newAmsList = currentAms.toMutableList()

            for (i in 0 until amsArray.length()) {
                val amsObj = amsArray.getJSONObject(i)
                val amsIndex = amsObj.optString("id")

                val existingUnitIndex = newAmsList.indexOfFirst { it.amsIndex == amsIndex }
                val currentUnit = if (existingUnitIndex != -1) newAmsList[existingUnitIndex] else null
                val trayArray = amsObj.optJSONArray("tray") ?: continue
                val trays = currentUnit?.trays?.toMutableList() ?: mutableListOf()

                for (j in 0 until trayArray.length()) {
                    val t = trayArray.getJSONObject(j)
                    val trayIndex = t.optString("id")
                    val existingTrayIndex = trays.indexOfFirst { it.trayIndex == trayIndex }
                    val currentTray = if (existingTrayIndex != -1) trays[existingTrayIndex] else null

                    val updatedTray = if (t.length() <= 1) {
                        AmsTrayDetail(trayIndex = trayIndex, type = "", subBrand = "Empty", trayIdName = "", trayInfoIdx = "", colorHex = "#FFFFFF", remain = 0, weight = "", trayDiameter = "", trayTemp = "", trayTime = "", tagUid = "", uuid = "", nozzleTempMax = 0, nozzleTempMin = 0, state = 0)
                    } else {
                        val rawColor = t.optString("tray_color", "")
                        val tagUid = t.optString("tag_uid", "")
                        val trayUuid = t.optString("tray_uuid", "")
                        val subBrand = if (tagUid == "0000000000000000") "Non-Bambu Spool" else t.optString("tray_sub_brands", currentTray?.subBrand ?: "Generic")
                        val finalUuid = if (trayUuid.isNotBlank()) trayUuid else if (tagUid.isNotBlank()) tagUid else (currentTray?.uuid ?: "")

                        AmsTrayDetail(
                            trayIndex = trayIndex,
                            type = t.optString("tray_type", currentTray?.type ?: ""),
                            subBrand = subBrand,
                            trayIdName = t.optString("tray_id_name", currentTray?.trayIdName ?: ""),
                            trayInfoIdx = t.optString("tray_info_idx", currentTray?.trayInfoIdx ?: ""),
                            colorHex = if (rawColor.length >= 6) "#${rawColor.take(6)}" else (currentTray?.colorHex ?: "#FFFFFF"),
                            remain = t.optInt("remain", currentTray?.remain ?: -1),
                            weight = t.optString("tray_weight", currentTray?.weight ?: ""),
                            trayDiameter = t.optString("tray_diameter", currentTray?.trayDiameter ?: ""),
                            trayTemp = t.optString("tray_temp", currentTray?.trayTemp ?: ""),
                            trayTime = t.optString("tray_time", currentTray?.trayTime ?: ""),
                            tagUid = tagUid,
                            uuid = finalUuid,
                            nozzleTempMax = t.optInt("nozzle_temp_max", currentTray?.nozzleTempMax ?: 0),
                            nozzleTempMin = t.optInt("nozzle_temp_min", currentTray?.nozzleTempMin ?: 0),
                            state = t.optInt("state", currentTray?.state ?: 0)
                        )
                    }
                    if (existingTrayIndex != -1) trays[existingTrayIndex] = updatedTray else trays.add(updatedTray)
                }
                trays.sortBy { it.trayIndex }
                val updatedUnit = AmsUnitReport(amsId = amsObj.optString("ams_id", currentUnit?.amsId ?: ""), amsIndex = amsIndex, temperature = amsObj.optString("temp", currentUnit?.temperature ?: ""), humidity = amsObj.optString("humidity", currentUnit?.humidity ?: ""), trays = trays)
                if (existingUnitIndex != -1) newAmsList[existingUnitIndex] = updatedUnit else newAmsList.add(updatedUnit)
            }
            newAmsList.sortBy { it.amsIndex }
            return newAmsList
        }
    }

        /**
         * Initiates a connection to the printer's MQTT broker.
         * Uses TLS on port 8883 with the printer's access code as the password.
         */
        fun connect() {
        val currentState = client?.state ?: MqttClientState.DISCONNECTED
        if (currentState == MqttClientState.CONNECTED || currentState == MqttClientState.CONNECTING) {
            Log.d("BambuMqtt", "Already $currentState for $maskedSerial. Skipping fresh connect.")
            return
        }

        // Clean up old dead client
        client?.disconnect()
        client = null

        val cleanToken = accessToken.trim().removeSuffix(";")
        val clientId = "android-${UUID.randomUUID().toString().take(8)}"

        client = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(8883)
            .sslWithDefaultConfig()
            .automaticReconnect()
                .initialDelay(3, TimeUnit.SECONDS)
                .maxDelay(30, TimeUnit.SECONDS)
                .applyAutomaticReconnect()
            .addConnectedListener {
                Log.d("BambuMqtt", "Connected to $maskedSerial")
                lastKnownState = lastKnownState.copy(isConnected = true, connectionStatus = "Connected", errorMessage = null)
                onStateUpdate(lastKnownState, "{}")
            }
            .addDisconnectedListener {
                Log.d("BambuMqtt", "Disconnected from $maskedSerial")
                lastKnownState = lastKnownState.copy(isConnected = false, connectionStatus = "Disconnected")
                onStateUpdate(lastKnownState, "{}")
            }
            .buildAsync()

        lastKnownState = lastKnownState.copy(connectionStatus = "Connecting...")
        onStateUpdate(lastKnownState, "{}")

        client?.connectWith()
            ?.simpleAuth()
                ?.username(userName)
                ?.password(cleanToken.toByteArray(StandardCharsets.UTF_8))
            ?.applySimpleAuth()
            ?.cleanSession(true)
            ?.keepAlive(60)
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable != null) {
                    onConnectionError(throwable.message ?: "Connection Failed")
                    lastKnownState = lastKnownState.copy(isConnected = false, connectionStatus = "Error")
                    onStateUpdate(lastKnownState, "{}")
                } else {
                    subscribeToPrinter()
                }
            }
    }

    /**
     * Removes sensitive information from the JSON string for logging purposes.
     * Scrubs serial numbers and access codes to prevent accidental disclosure.
     *
     * @param json The raw JSON telemetry string.
     * @return A scrubbed JSON string with sensitive fields removed.
     */
    private fun scrubJson(json: String): String {
        return try {
            val obj = JSONObject(json)
            val print = obj.optJSONObject("print")
            if (print != null) {
                print.remove("sn")
                print.remove("device_id")
                print.remove("access_code")
            }
            obj.toString()
        } catch (e: Exception) { json }
    }

    /**
     * Subscribes to the printer's report topic to receive real-time telemetry updates.
     */
    private fun subscribeToPrinter() {
        val topic = "device/$serialNumber/report"
        client?.subscribeWith()
            ?.topicFilter(topic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.callback { publish ->
                val json = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
                val hasAms = json.contains("\"ams\":")
                Log.d("BambuMqtt", "Received JSON for $maskedSerial (AMS: $hasAms): ${scrubJson(json)}")
                val newState = parseRawJson(json, serialNumber, lastKnownState)
                if (newState != null) {
                    lastSequenceId = newState.sequenceId.toIntOrNull() ?: lastSequenceId
                    lastKnownState = newState.copy(isConnected = true, connectionStatus = "Connected", errorMessage = null)
                    onStateUpdate(lastKnownState, json)
                }
            }
            ?.send()
            ?.whenComplete { subAck, throwable ->
                if (throwable != null) {
                    Log.e("BambuMqtt", "Sub failed for $maskedSerial: ${throwable.message}")
                } else if (subAck != null) {
                    if (subAck.returnCodes.any { it == Mqtt3SubAckReturnCode.FAILURE }) {
                        lastKnownState = lastKnownState.copy(isConnected = false, connectionStatus = "Error", errorMessage = "Subscription Failed")
                        onStateUpdate(lastKnownState, "{}")
                    } else {
                        requestFullUpdate()
                    }
                }
            }
    }

    /**
     * Requests a full state update from the printer using the 'pushall' command.
     * Bambu printers typically only send incremental updates; this forces a complete data dump.
     * Increments [lastSequenceId] to ensure the printer processes the request.
     */
    fun requestFullUpdate() {
        if (client?.state != MqttClientState.CONNECTED) return
        val topic = "device/$serialNumber/request"

        // Synchronized sequence tracking logic
        val nextSeq = lastSequenceId + 1
        val payload = """
            {
                "pushing": {
                    "sequence_id": "$nextSeq",
                    "command": "pushall",
                    "version": 1,
                    "push_target": 1
                }
            }
        """.trimIndent()

        Log.d("BambuMqtt", "Requesting full update for $maskedSerial (Next Seq: $nextSeq)")
        client?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.send()
    }

    /**
     * Disconnects from the MQTT broker and releases the client resources.
     */
    fun disconnect() {
        client?.disconnect()
    }
}