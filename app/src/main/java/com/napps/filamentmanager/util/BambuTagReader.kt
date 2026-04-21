package com.napps.filamentmanager.util

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.util.Log
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for reading and parsing proprietary Bambu Lab RFID tags.
 *
 * Bambu Lab uses Mifare Classic 1K tags to store filament metadata. These tags 
 * are protected using keys derived from the tag's UID using a specific HKDF-SHA256 
 * derivation process.
 *
 * The tag layout includes:
 * - Material Identification (Brand, Type, Variant IDs)
 * - Physical Properties (Weight, Diameter, Spool Width)
 * - Print Settings (Temperatures, Nozzle Requirements)
 * - Manufacturing Data (Production Date)
 */
object BambuTagReader {


    /**
     * Structured data representing the content of a Bambu Lab RFID tag.
     * 
     * @property uid The unique identifier of the physical NFC tag.
     * @property materialVariantId Internal ID for the specific material variant.
     * @property materialId Internal ID for the material type.
     * @property filamentType Short name of the filament (e.g., PLA, PETG).
     * @property detailedType More specific name (e.g., PLA Basic, PETG CF).
     * @property colorHex The primary color of the filament in Hex format (RRGGBBAA).
     * @property totalWeightG Full weight of the spool in grams.
     * @property diameterMm Filament diameter (typically 1.75mm).
     * @property dryingTempC Recommended drying temperature in Celsius.
     * @property dryingTimeHours Recommended drying duration.
     * @property bedTempC Recommended bed temperature for printing.
     * @property maxHotendTempC Maximum safe printing temperature.
     * @property minHotendTempC Minimum recommended printing temperature.
     * @property nozzleDiameterMin Minimum required nozzle diameter for this material.
     * @property trayUid Unique ID for the spool's tray/RFID record.
     * @property spoolWidthMm Physical width of the spool in millimeters.
     * @property productionDate Manufacturing date string.
     * @property filamentLengthM Total length of filament on the spool in meters.
     */
    data class BambuSpoolData(
        val uid: String,
        val materialVariantId: String,
        val materialId: String,
        val filamentType: String,
        val detailedType: String,
        val colorHex: String,
        val totalWeightG: Int,
        val diameterMm: Float,
        val dryingTempC: Int,
        val dryingTimeHours: Int,
        val bedTempC: Int,
        val maxHotendTempC: Int,
        val minHotendTempC: Int,
        val nozzleDiameterMin: Float,
        val trayUid: String,
        val spoolWidthMm: Float,
        val productionDate: String,
        val filamentLengthM: Int
    )

    private val MASTER_KEY = byteArrayOf(
        0x9a.toByte(), 0x75.toByte(), 0x9c.toByte(), 0xf2.toByte(),
        0xc4.toByte(), 0xf7.toByte(), 0xca.toByte(), 0xff.toByte(),
        0x22.toByte(), 0x2c.toByte(), 0xb9.toByte(), 0x76.toByte(),
        0x9b.toByte(), 0x41.toByte(), 0xbc.toByte(), 0x96.toByte()
    )

    /**
     * Attempts to read a Bambu Lab RFID tag.
     * 
     * Process:
     * 1. Connect to the Mifare Classic tag.
     * 2. Derive sector keys (A and B) using the tag's unique ID (UID).
     * 3. Authenticate and read all available data sectors.
     * 4. Parse the raw bytes into a structured [BambuSpoolData] object.
     * 
     * @return A Pair containing a status/summary string and the parsed data (if successful).
     */
    fun readAndParseBambuTag(tag: Tag): Pair<String, BambuSpoolData?> {
        val mifare = MifareClassic.get(tag) ?: return Pair("Not a Mifare Classic tag", null)
        val uid = tag.id

        return try {
            mifare.connect()
            // Optional: Increase timeout for stability
            mifare.timeout = 2000

            val (keysA, keysB) = deriveAllBambuKeys(uid)
            val allBlocks = mutableMapOf<Int, ByteArray>()

            for (sectorIndex in 0 until 16) {
                val keyA = keysA[sectorIndex]
                var authenticated = mifare.authenticateSectorWithKeyA(sectorIndex, keyA)

                if (!authenticated) {
                    // Reconnect if auth fails to clear the tag's error state
                    mifare.close()
                    mifare.connect()

                    val keyB = keysB[sectorIndex]
                    authenticated = mifare.authenticateSectorWithKeyB(sectorIndex, keyB)
                }

                if (authenticated) {
                    val baseBlock = mifare.sectorToBlock(sectorIndex)
                    for (blockOffset in 0..2) {
                        val blockIndex = baseBlock + blockOffset
                        try {
                            allBlocks[blockIndex] = mifare.readBlock(blockIndex)
                        } catch (e: Exception) {
                            Log.e("napps_NFC", "Block $blockIndex read failed: ${e.message}")
                        }
                    }
                }
            }
            mifare.close()

            // Verify we have the most important blocks (4 and 5)
            if (allBlocks.containsKey(4) && allBlocks.containsKey(5)) {
                val spoolData = parseBambuBlocks(uid, allBlocks)
                val summary = "Successfully read ${spoolData.filamentType} (${spoolData.detailedType})"
                Pair(summary, spoolData)
            } else {
                Pair("Auth failed for data sectors. Found ${allBlocks.size} blocks.", null)
            }

        } catch (e: Exception) {
            Log.e("napps_NFC", "Error reading tag", e)
            Pair("Error: ${e.message}", null)
        }
    }
    /**
     * Derives the 16 sector keys (A and B) for a Mifare Classic tag.
     * 
     * Uses HKDF-SHA256 with the tag UID as the Input Keying Material (IKM) 
     * and a hardcoded Master Key as the Salt.
     */
    fun deriveAllBambuKeys(uid: ByteArray): Pair<List<ByteArray>, List<ByteArray>> {
        val hkdf = HKDFBytesGenerator(SHA256Digest())

        // RFID-A context
        val infoA = byteArrayOf(0x52, 0x46, 0x49, 0x44, 0x2d, 0x41, 0x00)
        // RFID-B context
        val infoB = byteArrayOf(0x52, 0x46, 0x49, 0x44, 0x2d, 0x42, 0x00)

        // Helper to generate a set of 16 keys
        fun getKeys(info: ByteArray): List<ByteArray> {
            // According to your script: IKM is UID, Salt is MASTER_KEY
            hkdf.init(HKDFParameters(uid, MASTER_KEY, info))
            val buffer = ByteArray(96)
            hkdf.generateBytes(buffer, 0, 96)
            return (0 until 16).map { buffer.sliceArray(it * 6 until (it + 1) * 6) }
        }

        val keysA = getKeys(infoA)
        val keysB = getKeys(infoB)

        return Pair(keysA, keysB)
    }

    /**
     * Formats the parsed spool data into a human-readable multi-line string.
     * Useful for logging or debugging NFC reads.
     *
     * @param data The [BambuSpoolData] object to format.
     * @return A formatted string summary of the spool details.
     */
    fun formatBambuData(data: BambuSpoolData): String {
        val sb = StringBuilder()

        sb.append("--- BAMBU SPOOL DATA ---\n")
        sb.append("Tag UID: ${data.uid}\n")
        sb.append("Material Variant ID: ${data.materialVariantId}\n")
        sb.append("Material ID: ${data.materialId}\n")
        sb.append("Filament Type: ${data.filamentType}\n")
        sb.append("Detailed Type: ${data.detailedType}\n")
        sb.append("Color Hex: #${data.colorHex}\n")
        sb.append("Total Weight: ${data.totalWeightG} g\n")
        sb.append("Diameter: ${"%.2f".format(data.diameterMm)}mm\n")
        sb.append("Drying Temp: ${data.dryingTempC}°C\n")
        sb.append("Drying Time: ${data.dryingTimeHours}h\n")
        sb.append("Bed Temp: ${data.bedTempC}°C\n")
        sb.append("Max Hotend Temp: ${data.maxHotendTempC}°C\n")
        sb.append("Min Hotend Temp: ${data.minHotendTempC}°C\n")
        sb.append("Min Nozzle Diameter: ${data.nozzleDiameterMin}mm\n")
        sb.append("Tray UID: ${data.trayUid}\n")
        sb.append("Spool Width: ${"%.2f".format(data.spoolWidthMm)}mm\n")
        sb.append("Production Date: ${data.productionDate}\n")
        sb.append("Filament Length: ${data.filamentLengthM}m\n")
        sb.append("------------------------")

        return sb.toString()
    }

    /**
     * Parses the raw sector blocks from a Mifare Classic tag into [BambuSpoolData].
     * 
     * This method implements the proprietary mapping of Bambu Lab's data structure
     * across different sectors and blocks of the tag.
     * 
     * @param uid The tag's hardware UID.
     * @param blocks A map of block indices to their 16-byte raw contents.
     */
    private fun parseBambuBlocks(uid: ByteArray, blocks: Map<Int, ByteArray>): BambuSpoolData {
        // Helper to safely extract strings
        fun getStr(blockIndex: Int, start: Int = 0, length: Int = 16): String {
            return blocks[blockIndex]?.sliceArray(start until start + length)
                ?.toString(Charsets.US_ASCII)
                ?.trim { it <= ' ' } ?: ""
        }

        // Helper for Little Endian Shorts (UInt16)
        fun getShort(blockIndex: Int, offset: Int): Int {
            val data = blocks[blockIndex] ?: return 0
            return ByteBuffer.wrap(data.sliceArray(offset..offset+1))
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        }

        // Helper for Little Endian Floats
        fun getFloat(blockIndex: Int, offset: Int): Float {
            val data = blocks[blockIndex] ?: return 0f
            return ByteBuffer.wrap(data.sliceArray(offset..offset+3))
                .order(ByteOrder.LITTLE_ENDIAN).float
        }
        // Helper to convert binary blocks to Hex strings (for IDs)
        fun toHex(blockIndex: Int): String {
            return blocks[blockIndex]?.joinToString("") { "%02X".format(it) } ?: ""
        }

        return BambuSpoolData(
            uid = uid.joinToString("") { "%02X".format(it) },
            materialVariantId = getStr(1, 0, 8),
            materialId = getStr(1, 8, 8),
            filamentType = getStr(2),
            detailedType = getStr(4),
            colorHex = blocks[5]?.sliceArray(0..3)?.joinToString("") { "%02X".format(it) } ?: "000000FF",
            totalWeightG = getShort(5, 4),
            diameterMm = getFloat(5, 8),
            dryingTempC = getShort(6, 0),
            dryingTimeHours = getShort(6, 2),
            bedTempC = getShort(6, 6),
            maxHotendTempC = getShort(6, 8),
            minHotendTempC = getShort(6, 10),
            nozzleDiameterMin = getFloat(8, 12),
            trayUid = toHex(9),
            spoolWidthMm = getShort(10, 4) / 10f,
            productionDate = getStr(12),
            filamentLengthM = getShort(14, 4)
        )
    }
}