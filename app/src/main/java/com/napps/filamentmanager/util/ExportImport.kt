package com.napps.filamentmanager.util

import com.google.gson.Gson
import com.napps.filamentmanager.database.FilamentInventory
import com.napps.filamentmanager.database.LimitWithFilaments
import com.napps.filamentmanager.database.TrackerWithFilaments
import java.lang.reflect.Modifier

/**
 * Helper object for exporting and importing database entities in CSV and JSON formats.
 * 
 * Uses Kotlin reflection to dynamically generate CSV headers and rows, ensuring that
 * any new fields added to the entities are automatically included in exports without 
 * manual updates.
 */
object DynamicCsvHelper {

    /**
     * Robustly converts a list of objects into a CSV string using reflection.
     * Captures all fields currently in the class, excluding static and synthetic fields.
     *
     * @param data The list of objects to export.
     * @return A CSV formatted string.
     */
    fun <T : Any> toCsv(data: List<T>): String {
        if (data.isEmpty()) return ""
        val clazz = data[0].javaClass
        // Filter out synthetic fields (like $change or $jacocoData) and static fields
        val fields = clazz.declaredFields.filter { 
            !Modifier.isStatic(it.modifiers) && !it.name.startsWith("$") 
        }
        fields.forEach { it.isAccessible = true }

        val header = fields.joinToString(",") { it.name }
        val rows = data.joinToString("\n") { item ->
            fields.joinToString(",") { field ->
                val value = field.get(item)
                // Use ; as internal delimiter if string contains comma to avoid breaking CSV
                value?.toString()?.replace(",", ";") ?: ""
            }
        }
        return "$header\n$rows"
    }

    /**
     * Robustly parses CSV into a list of property maps.
     * Each map represents a row, where keys are column headers and values are cell contents.
     *
     * @param csv The raw CSV string to parse.
     * @return A list of maps representing the data rows.
     */
    fun fromCsv(csv: String): List<Map<String, String>> {
        val lines = csv.split("\n").filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val headers = lines[0].split(",")
        return lines.drop(1).map { line ->
            val values = line.split(",")
            headers.indices.associate { i ->
                headers[i] to (values.getOrNull(i) ?: "")
            }
        }
    }

    // --- Filaments (Inventory) ---

    /**
     * Export a list of [FilamentInventory] objects to a standard CSV string.
     */
    fun exportFilaments(filaments: List<FilamentInventory>): String = toCsv(filaments)
    
    /** 
     * Alias for `InventoryActivity.kt`'s export request.
     * Exports all provided filaments to CSV. 
     */
    fun exportAll(filaments: List<FilamentInventory>): String = exportFilaments(filaments)

    /**
     * Parses a CSV string back into a list of [FilamentInventory] objects.
     * Handles mapping of CSV columns to class properties, with fallback values for missing data.
     */
    fun parseFilaments(csv: String): List<FilamentInventory> {
        val data = fromCsv(csv)
        return data.mapNotNull { map ->
            try {
                FilamentInventory(
                    id = map["id"]?.toIntOrNull() ?: 0,
                    brand = map["brand"] ?: "Bambu Lab",
                    type = map["type"],
                    materialVariantID = map["materialVariantID"],
                    materialID = map["materialID"],
                    diameter = map["diameter"]?.replace(";", ",")?.toFloatOrNull(),
                    colorName = map["colorName"],
                    colorRgb = map["colorRgb"]?.toIntOrNull(),
                    trayUID = map["trayUID"],
                    timestamp = map["timestamp"]?.toLongOrNull() ?: 0L,
                    weight = map["weight"],
                    usedPercent = map["usedPercent"]?.toFloatOrNull() ?: 1.0f,
                    filamentLength = map["filamentLength"]?.toIntOrNull(),
                    availabilityStatus = map["availabilityStatus"]?.toIntOrNull() ?: 1,
                    status = map["status"]?.toIntOrNull() ?: 0,
                    error = map["error"]?.toIntOrNull() ?: 0
                )
            } catch (e: Exception) {
                null
            }
        }
    }
    

    fun parseDynamic(csv: String): List<FilamentInventory> = parseFilaments(csv)

    // --- Robust Packaging for Limits and Trackers ---

    /**
     * Wrapper for exporting all provided data to a robust JSON format.
     */
    data class RobustExportPackage(
        /** Map of table names to their CSV representations. */
        val tables: Map<String, String>,
        /** Version of the export schema to ensure compatibility. */
        val version: Int = 1
    )

    /**
     * Identifies a filament by its key properties for matching across different database instances.
     * Used during import to reconnect trackers/limits to existing filaments in the new DB.
     */
    data class FilamentIdentity(
        val exportId: Int,
        val brand: String?,
        val type: String?,
        val colorName: String?,
        val colorRgb: Int?,
        val packageType: String?,
        val sku: String?
    )

    /**
     * Exports limits and their associated filaments' identifying info into a JSON package.
     */
    fun exportLimitsToRobustJson(limits: List<LimitWithFilaments>): String {
        val limitsCsv = toCsv(limits.map { it.limit })
        
        // Extract unique referenced filaments
        val referencedFilaments = limits.flatMap { it.filaments }.distinctBy { 
            it.sku ?: "${it.brand}_${it.type}_${it.colorName}_${it.packageType}" 
        }
        
        val filamentIdentities = referencedFilaments.mapIndexed { index, f ->
            FilamentIdentity(index, f.brand, f.type, f.colorName, f.colorRgb, f.packageType, f.sku)
        }
        val filamentsCsv = toCsv(filamentIdentities)

        // Create links using limit name and filament exportId
        val linksHeader = "limitName,exportId"
        val linksRows = limits.flatMap { lwf ->
            lwf.filaments.map { f ->
                val eid = referencedFilaments.indexOfFirst { 
                    (it.sku == f.sku && !f.sku.isNullOrBlank()) || 
                    (it.brand == f.brand && it.type == f.type && it.colorName == f.colorName && it.packageType == f.packageType)
                }
                "${lwf.limit.name.replace(",", ";")},$eid"
            }
        }
        val linksCsv = "$linksHeader\n${linksRows.joinToString("\n")}"

        return Gson().toJson(RobustExportPackage(tables = mapOf(
            "limits" to limitsCsv,
            "filaments" to filamentsCsv,
            "links" to linksCsv
        )))
    }

    /**
     * Parses the robust limit package. 
     * Returns List of (Limit properties map, List of Filament properties maps).
     */
    fun importLimitsFromRobustJson(json: String): List<Pair<Map<String, String>, List<Map<String, String>>>> {
        return try {
            val pkg = Gson().fromJson(json, RobustExportPackage::class.java)
            val limitsData = fromCsv(pkg.tables["limits"] ?: "")
            val filamentsData = fromCsv(pkg.tables["filaments"] ?: "")
            val linksData = fromCsv(pkg.tables["links"] ?: "")
            
            limitsData.map { limitMap ->
                val name = limitMap["name"] ?: ""
                val exportIds = linksData.filter { it["limitName"] == name }.map { it["exportId"] }
                val filamentsInfo = exportIds.mapNotNull { eid ->
                    filamentsData.find { it["exportId"] == eid }
                }
                limitMap to filamentsInfo
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Exports trackers and their associated filaments' identifying info into a JSON package.
     * Excludes limit-managed trackers.
     */
    fun exportTrackersToRobustJson(trackers: List<TrackerWithFilaments>): String {
        // Exclude trackers created by limits
        val userTrackers = trackers.filterNot { it.tracker.name.startsWith("Limit Service: ") }
        val trackersCsv = toCsv(userTrackers.map { it.tracker })
        
        val referencedFilaments = userTrackers.flatMap { it.filaments }.distinctBy { 
            it.sku ?: "${it.brand}_${it.type}_${it.colorName}_${it.packageType}" 
        }
        
        val filamentIdentities = referencedFilaments.mapIndexed { index, f ->
            FilamentIdentity(index, f.brand, f.type, f.colorName, f.colorRgb, f.packageType, f.sku)
        }
        val filamentsCsv = toCsv(filamentIdentities)

        val linksHeader = "trackerName,exportId"
        val linksRows = userTrackers.flatMap { twf ->
            twf.filaments.map { f ->
                val eid = referencedFilaments.indexOfFirst { 
                    (it.sku == f.sku && !f.sku.isNullOrBlank()) || 
                    (it.brand == f.brand && it.type == f.type && it.colorName == f.colorName && it.packageType == f.packageType)
                }
                "${twf.tracker.name.replace(",", ";")},$eid"
            }
        }
        val linksCsv = "$linksHeader\n${linksRows.joinToString("\n")}"

        return Gson().toJson(RobustExportPackage(tables = mapOf(
            "trackers" to trackersCsv,
            "filaments" to filamentsCsv,
            "links" to linksCsv
        )))
    }

    /**
     * Parses the robust tracker package.
     * Returns List of (Tracker properties map, List of Filament properties maps).
     */
    fun importTrackersFromRobustJson(json: String): List<Pair<Map<String, String>, List<Map<String, String>>>> {
        return try {
            val pkg = Gson().fromJson(json, RobustExportPackage::class.java)
            val trackersData = fromCsv(pkg.tables["trackers"] ?: "")
            val filamentsData = fromCsv(pkg.tables["filaments"] ?: "")
            val linksData = fromCsv(pkg.tables["links"] ?: "")
            
            trackersData.map { trackerMap ->
                val name = trackerMap["name"] ?: ""
                val exportIds = linksData.filter { it["trackerName"] == name }.map { it["exportId"] }
                val filamentsInfo = exportIds.mapNotNull { eid ->
                    filamentsData.find { it["exportId"] == eid }
                }
                trackerMap to filamentsInfo
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
