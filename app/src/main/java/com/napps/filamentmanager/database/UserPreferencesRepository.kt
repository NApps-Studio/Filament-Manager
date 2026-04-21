package com.napps.filamentmanager.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.napps.filamentmanager.FabAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/**
 * Repository for managing user preferences using Jetpack DataStore.
 * Handles persistence for UI settings, region selection, and onboarding states.
 */
class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val FAB_MODE = stringPreferencesKey("fab_mode")
        val HAS_FIRST_SYNC_FINISHED = booleanPreferencesKey("has_first_sync_finished")
        val SHOW_EMPTY_SPOOLS = booleanPreferencesKey("show_empty_spools")
        val STORE_REGION = stringPreferencesKey("store_region")
        val IS_CHINA_MQTT = booleanPreferencesKey("is_china_mqtt")
        val HAS_SET_REGION = booleanPreferencesKey("has_set_region")
        val HAS_SHOWN_BATTERY_OPTIMIZATION = booleanPreferencesKey("has_shown_battery_optimization")
        val PRINTER_UPDATE_INTERVAL = intPreferencesKey("printer_update_interval")
        val STOCK_UPDATE_INTERVAL = intPreferencesKey("stock_update_interval")
        val DEFAULT_FIRST_PAGE = stringPreferencesKey("default_first_page")
        val LOW_FILAMENT_THRESHOLD_G = intPreferencesKey("low_filament_threshold_g")
        val AUTO_DETECT_RUNOUT = booleanPreferencesKey("auto_detect_runout")
        val TOUR_NAPPS_SCREEN = booleanPreferencesKey("tour_napps_screen")
        val TOUR_AVAILABILITY = booleanPreferencesKey("tour_availability")
        val TOUR_INVENTORY = booleanPreferencesKey("tour_inventory")
        val TOUR_BAMBU_ACCOUNT_BEFORE_LOGIN = booleanPreferencesKey("tour_bambu_account_before_login")
        val TOUR_INVENTORY_CARD = booleanPreferencesKey("tour_inventory_card")
        val TOUR_AVAILABILITY_CARD = booleanPreferencesKey("tour_availability_card")
        val TOUR_BAMBU_ACCOUNT_CARD = booleanPreferencesKey("tour_bambu_account_card")
        val TOUR_SETTINGS = booleanPreferencesKey("tour_settings")
        val TOUR_LIMITS = booleanPreferencesKey("tour_limits")
        val TOUR_LIMITS_CARD = booleanPreferencesKey("tour_limits_card")
        val TOUR_LOW_STOCK = booleanPreferencesKey("tour_low_stock")
        val TOUR_UNMAPPED = booleanPreferencesKey("tour_unmapped")
        val TOUR_SYNC_REPORTS = booleanPreferencesKey("tour_sync_reports")
        val HAS_SHOWN_TOUR_DECISION = booleanPreferencesKey("has_shown_tour_decision")
        val SHOW_ADD_TO_CART_TRACKERS = booleanPreferencesKey("show_add_to_cart_trackers")
        val HAS_ADDED_FILAMENT = booleanPreferencesKey("has_added_filament")
        val HAS_ADDED_PRINTER = booleanPreferencesKey("has_added_printer")
        val HAS_ADDED_TRACKER = booleanPreferencesKey("has_added_tracker")
        val IGNORE_SYNC_WARNING = booleanPreferencesKey("ignore_sync_warning")
        val TOUR_BAMBU_ACCOUNT_AFTER_LOGIN = booleanPreferencesKey("tour_bambu_account_after_login")
        val TOUR_BAMBU_ACCOUNT_TOKEN_EXPIRED = booleanPreferencesKey("tour_bambu_account_token_expired")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val DEBUG_FORCE_OOS = booleanPreferencesKey("debug_force_oos")
        val DEBUG_FORCE_UNMAPPED = booleanPreferencesKey("debug_force_unmapped")
        val DEBUG_FORCE_TOKEN_EXPIRED = booleanPreferencesKey("debug_force_token_expired")

        // Last used manual entry fields
        val LAST_USED_BRAND = stringPreferencesKey("last_used_brand")
        val LAST_USED_TYPE = stringPreferencesKey("last_used_type")
        val LAST_USED_COLOR_NAME = stringPreferencesKey("last_used_color_name")
        val LAST_USED_COLOR_RGB = intPreferencesKey("last_used_color_rgb")
    }

    /**
     * Flow providing the last manually entered brand.
     */
    val lastUsedBrandFlow: Flow<String?> = context.dataStore.data
        .map { it[PreferencesKeys.LAST_USED_BRAND] }

    /**
     * Flow providing the last manually entered material type.
     */
    val lastUsedTypeFlow: Flow<String?> = context.dataStore.data
        .map { it[PreferencesKeys.LAST_USED_TYPE] }

    /**
     * Flow providing the last manually entered color name.
     */
    val lastUsedColorNameFlow: Flow<String?> = context.dataStore.data
        .map { it[PreferencesKeys.LAST_USED_COLOR_NAME] }

    /**
     * Flow providing the last manually entered color RGB value.
     */
    val lastUsedColorRgbFlow: Flow<Int?> = context.dataStore.data
        .map { it[PreferencesKeys.LAST_USED_COLOR_RGB] }

    /**
     * Saves the properties of a manually added filament spool to facilitate 
     * faster repetitive entry.
     */
    suspend fun saveLastUsedManualFilament(brand: String, type: String, colorName: String, colorRgb: Int?) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.LAST_USED_BRAND] = brand
            prefs[PreferencesKeys.LAST_USED_TYPE] = type
            prefs[PreferencesKeys.LAST_USED_COLOR_NAME] = colorName
            if (colorRgb != null) {
                prefs[PreferencesKeys.LAST_USED_COLOR_RGB] = colorRgb
            } else {
                prefs.remove(PreferencesKeys.LAST_USED_COLOR_RGB)
            }
        }
    }

    /**
     * Indicates if the user has successfully added at least one filament spool 
     * to their inventory.
     */
    val hasAddedFilamentFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.HAS_ADDED_FILAMENT] ?: false }

    /**
     * Records that a filament has been added and potentially enables onboarding tours.
     */
    suspend fun setHasAddedFilament(added: Boolean) {
        context.dataStore.edit { prefs ->
            val alreadyAdded = prefs[PreferencesKeys.HAS_ADDED_FILAMENT] ?: false
            prefs[PreferencesKeys.HAS_ADDED_FILAMENT] = added
            // Only auto-enable the card tour if the user hasn't made a global tour decision yet.
            // If they said "No" to tours, we respect that. If they said "Yes", it's already enabled.
            if (added && !alreadyAdded && !(prefs[PreferencesKeys.HAS_SHOWN_TOUR_DECISION] ?: false)) {
                prefs[PreferencesKeys.TOUR_INVENTORY_CARD] = true
            }
        }
    }

    /**
     * Indicates if the user has added a printer for MQTT monitoring.
     */
    val hasAddedPrinterFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.HAS_ADDED_PRINTER] ?: false }

    /**
     * Records that a printer has been added and potentially enables onboarding tours.
     */
    suspend fun setHasAddedPrinter(added: Boolean) {
        context.dataStore.edit { prefs ->
            val alreadyAdded = prefs[PreferencesKeys.HAS_ADDED_PRINTER] ?: false
            prefs[PreferencesKeys.HAS_ADDED_PRINTER] = added
            if (added && !alreadyAdded && !(prefs[PreferencesKeys.HAS_SHOWN_TOUR_DECISION] ?: false)) {
                prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_CARD] = true
            }
        }
    }

    /**
     * Indicates if the user has added an inventory stock limit tracker.
     */
    val hasAddedTrackerFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.HAS_ADDED_TRACKER] ?: false }

    /**
     * Whether the user has opted to ignore the incomplete sync warning.
     */
    val ignoreSyncWarningFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.IGNORE_SYNC_WARNING] ?: false }

    /** Sets the preference to ignore sync warnings. */
    suspend fun setIgnoreSyncWarning(ignore: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.IGNORE_SYNC_WARNING] = ignore }
    }

    /** Records that a tracker has been added and potentially enables onboarding tours. */
    suspend fun setHasAddedTracker(added: Boolean) {
        context.dataStore.edit { prefs ->
            val alreadyAdded = prefs[PreferencesKeys.HAS_ADDED_TRACKER] ?: false
            prefs[PreferencesKeys.HAS_ADDED_TRACKER] = added
            if (added && !alreadyAdded && !(prefs[PreferencesKeys.HAS_SHOWN_TOUR_DECISION] ?: false)) {
                prefs[PreferencesKeys.TOUR_AVAILABILITY_CARD] = true
            }
        }
    }

    /** Indicates if the user has already seen or skipped the onboarding tour prompt. */
    val hasShownTourDecisionFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.HAS_SHOWN_TOUR_DECISION] ?: false }

    /** Records that the tour prompt has been displayed. */
    suspend fun setTourDecisionShown(shown: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.HAS_SHOWN_TOUR_DECISION] = shown }
    }

    /**
     * Provides a map of all feature tour flags and their current display status.
     */
    val tourFlagsFlow: Flow<Map<String, Boolean>> = context.dataStore.data
        .map { prefs ->
            mapOf(
                "NAPPS_SCREEN" to (prefs[PreferencesKeys.TOUR_NAPPS_SCREEN] ?: false),
                "AVAILABILITY" to (prefs[PreferencesKeys.TOUR_AVAILABILITY] ?: false),
                "INVENTORY" to (prefs[PreferencesKeys.TOUR_INVENTORY] ?: false),
                "BAMBU_ACCOUNT_BEFORE_LOGIN" to (prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_BEFORE_LOGIN] ?: false),
                "INVENTORY_CARD" to (prefs[PreferencesKeys.TOUR_INVENTORY_CARD] ?: false),
                "AVAILABILITY_CARD" to (prefs[PreferencesKeys.TOUR_AVAILABILITY_CARD] ?: false),
                "BAMBU_ACCOUNT_CARD" to (prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_CARD] ?: false),
                "SETTINGS" to (prefs[PreferencesKeys.TOUR_SETTINGS] ?: false),
                "LIMITS" to (prefs[PreferencesKeys.TOUR_LIMITS] ?: false),
                "LIMITS_CARD" to (prefs[PreferencesKeys.TOUR_LIMITS_CARD] ?: false),
                "LOW_STOCK" to (prefs[PreferencesKeys.TOUR_LOW_STOCK] ?: false),
                "UNMAPPED" to (prefs[PreferencesKeys.TOUR_UNMAPPED] ?: false),
                "SYNC_REPORTS" to (prefs[PreferencesKeys.TOUR_SYNC_REPORTS] ?: false),
                "BAMBU_ACCOUNT_AFTER_LOGIN" to (prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_AFTER_LOGIN] ?: false),
                "BAMBU_ACCOUNT_TOKEN_EXPIRED" to (prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_TOKEN_EXPIRED] ?: false)
            )
        }

    /**
     * Updates the status of a specific feature tour flag.
     * @param screen The screen or feature identifier.
     * @param enabled Whether the tour should be shown next time.
     */
    suspend fun updateTourFlag(screen: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            when (screen) {
                "NAPPS_SCREEN" -> prefs[PreferencesKeys.TOUR_NAPPS_SCREEN] = enabled
                "AVAILABILITY" -> prefs[PreferencesKeys.TOUR_AVAILABILITY] = enabled
                "INVENTORY" -> prefs[PreferencesKeys.TOUR_INVENTORY] = enabled
                "BAMBU_ACCOUNT_BEFORE_LOGIN" -> prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_BEFORE_LOGIN] = enabled
                "INVENTORY_CARD" -> prefs[PreferencesKeys.TOUR_INVENTORY_CARD] = enabled
                "AVAILABILITY_CARD" -> prefs[PreferencesKeys.TOUR_AVAILABILITY_CARD] = enabled
                "BAMBU_ACCOUNT_CARD" -> prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_CARD] = enabled
                "SETTINGS" -> prefs[PreferencesKeys.TOUR_SETTINGS] = enabled
                "LIMITS" -> prefs[PreferencesKeys.TOUR_LIMITS] = enabled
                "LIMITS_CARD" -> prefs[PreferencesKeys.TOUR_LIMITS_CARD] = enabled
                "LOW_STOCK" -> prefs[PreferencesKeys.TOUR_LOW_STOCK] = enabled
                "UNMAPPED" -> prefs[PreferencesKeys.TOUR_UNMAPPED] = enabled
                "SYNC_REPORTS" -> prefs[PreferencesKeys.TOUR_SYNC_REPORTS] = enabled
                "BAMBU_ACCOUNT_AFTER_LOGIN" -> prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_AFTER_LOGIN] = enabled
                "BAMBU_ACCOUNT_TOKEN_EXPIRED" -> prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_TOKEN_EXPIRED] = enabled
            }
        }
    }

    /**
     * Enables or disables all feature tours at once.
     */
    suspend fun setAllToursEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.LOW_FILAMENT_THRESHOLD_G] = 25
            prefs[PreferencesKeys.TOUR_SYNC_REPORTS] = enabled
            prefs[PreferencesKeys.TOUR_NAPPS_SCREEN] = enabled
            prefs[PreferencesKeys.TOUR_AVAILABILITY] = enabled
            prefs[PreferencesKeys.TOUR_INVENTORY] = enabled
            prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_BEFORE_LOGIN] = enabled
            prefs[PreferencesKeys.TOUR_INVENTORY_CARD] = enabled
            prefs[PreferencesKeys.TOUR_AVAILABILITY_CARD] = enabled
            prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_CARD] = enabled
            prefs[PreferencesKeys.TOUR_SETTINGS] = enabled
            prefs[PreferencesKeys.TOUR_LIMITS] = enabled
            prefs[PreferencesKeys.TOUR_LIMITS_CARD] = enabled
            prefs[PreferencesKeys.TOUR_LOW_STOCK] = enabled
            prefs[PreferencesKeys.TOUR_UNMAPPED] = enabled
            prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_AFTER_LOGIN] = enabled
            prefs[PreferencesKeys.TOUR_BAMBU_ACCOUNT_TOKEN_EXPIRED] = enabled
        }
    }



    /**
     * Whether to automatically detect runout errors and mark them as potential out of stock.
     */
    val autoDetectRunoutFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.AUTO_DETECT_RUNOUT] ?: true }

    /**
     * Threshold in grams to mark a filament as potentially out of stock.
     */
    val lowFilamentThresholdGFlow: Flow<Int> = context.dataStore.data
        .map { it[PreferencesKeys.LOW_FILAMENT_THRESHOLD_G] ?: 25 }

    /**
     * Interval for printer MQTT updates in minutes (minimum 15).
     */
    val printerUpdateIntervalFlow: Flow<Int> = context.dataStore.data
        .map { it[PreferencesKeys.PRINTER_UPDATE_INTERVAL] ?: 15 }

    /**
     * Interval for stock updates in minutes (minimum 15).
     */
    val stockUpdateIntervalFlow: Flow<Int> = context.dataStore.data
        .map { it[PreferencesKeys.STOCK_UPDATE_INTERVAL] ?: 30 }

    /**
     * The default page to show when the app starts.
     */
    val defaultFirstPageFlow: Flow<String> = context.dataStore.data
        .map { it[PreferencesKeys.DEFAULT_FIRST_PAGE] ?: "NAPPS" }

    /**
     * Flag indicating if the battery optimization dialog has been shown.
     */
    val hasShownBatteryOptimizationFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.HAS_SHOWN_BATTERY_OPTIMIZATION] ?: false }

    /**
     * The selected Bambu Lab store region (e.g., US, EU, Global).
     */
    val storeRegionFlow: Flow<SyncRegion> = context.dataStore.data
        .map { it[PreferencesKeys.STORE_REGION] ?: SyncRegion.EU.name }
        .map { SyncRegion.valueOf(it) }

    /**
     * Indicates whether the app should use China-specific MQTT servers.
     */
    val isChinaMqttFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.IS_CHINA_MQTT] ?: false }

    /**
     * Flag indicating if the user has completed the initial region setup.
     */
    val hasSetRegionFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.HAS_SET_REGION] ?: false }

    /**
     * Current action assigned to the Floating Action Button (Add vs Scan).
     */
    val fabModeFlow: Flow<FabAction> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.FAB_MODE] ?: FabAction.SCAN_RF_ID.name
            try { FabAction.valueOf(modeName) } catch (e: Exception) { FabAction.SCAN_RF_ID }
        }

    /**
     * Flag indicating if the first-time sync of the vendor catalog is complete.
     */
    val hasFirstSyncFinishedFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.HAS_FIRST_SYNC_FINISHED] ?: false }

    /**
     * Setting to control the visibility of spools with 0g weight in the inventory.
     */
    val showEmptySpoolsFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.SHOW_EMPTY_SPOOLS] ?: true }

    val showAddToCartTrackersFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.SHOW_ADD_TO_CART_TRACKERS] ?: false }

    val debugModeFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.DEBUG_MODE] ?: false }

    val debugForceOosFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.DEBUG_FORCE_OOS] ?: false }

    val debugForceUnmappedFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.DEBUG_FORCE_UNMAPPED] ?: false }

    val debugForceTokenExpiredFlow: Flow<Boolean> = context.dataStore.data
        .map { it[PreferencesKeys.DEBUG_FORCE_TOKEN_EXPIRED] ?: false }

    /**
     * Records the showing of the battery optimization dialog.
     */
    suspend fun setBatteryOptimizationShown(shown: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.HAS_SHOWN_BATTERY_OPTIMIZATION] = shown }
    }

    suspend fun savePrinterUpdateInterval(minutes: Int) {
        context.dataStore.edit { it[PreferencesKeys.PRINTER_UPDATE_INTERVAL] = minutes.coerceAtLeast(15) }
    }

    suspend fun saveStockUpdateInterval(minutes: Int) {
        context.dataStore.edit { it[PreferencesKeys.STOCK_UPDATE_INTERVAL] = minutes.coerceAtLeast(15) }
    }

    suspend fun saveDefaultFirstPage(destination: String) {
        context.dataStore.edit { it[PreferencesKeys.DEFAULT_FIRST_PAGE] = destination }
    }

    suspend fun saveLowFilamentThresholdG(grams: Int) {
        context.dataStore.edit { it[PreferencesKeys.LOW_FILAMENT_THRESHOLD_G] = grams }
    }

    suspend fun setAutoDetectRunout(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_DETECT_RUNOUT] = enabled }
    }

    /**
     * Saves the user's region and MQTT connectivity preference.
     */
    suspend fun saveRegion(region: SyncRegion, isChinaMqtt: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.STORE_REGION] = region.name
            preferences[PreferencesKeys.IS_CHINA_MQTT] = isChinaMqtt
            preferences[PreferencesKeys.HAS_SET_REGION] = true
        }
    }

    /**
     * Updates the Floating Action Button's default action.
     */
    suspend fun saveFabMode(mode: FabAction) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FAB_MODE] = mode.name
        }
    }

    /**
     * Records the completion of the initial catalog sync.
     */
    suspend fun setFirstSyncFinished(finished: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.HAS_FIRST_SYNC_FINISHED] = finished }
    }
    suspend fun setShowEmptySpools(show: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SHOW_EMPTY_SPOOLS]=show }
    }

    suspend fun setShowAddToCartTrackers(show: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SHOW_ADD_TO_CART_TRACKERS] = show }
    }

    suspend fun setDebugMode(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DEBUG_MODE] = enabled }
    }

    suspend fun setDebugForceOos(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DEBUG_FORCE_OOS] = enabled }
    }

    suspend fun setDebugForceUnmapped(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DEBUG_FORCE_UNMAPPED] = enabled }
    }

    suspend fun setDebugForceTokenExpired(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DEBUG_FORCE_TOKEN_EXPIRED] = enabled }
    }
}
