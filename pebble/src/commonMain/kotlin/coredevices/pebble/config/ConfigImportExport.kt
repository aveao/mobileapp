package coredevices.pebble.config

import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.WatchPref
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ConfigExport(
    val version: Int = 1,
    val exportedAt: String,
    val watchPrefs: Map<String, String> = emptyMap(),
    val coreConfig: CoreConfig? = null,
    val libPebbleConfig: LibPebbleConfig? = null,
)

private val exportJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val importJson = Json {
    ignoreUnknownKeys = true
}

suspend fun buildConfigExport(
    watchPrefsFlow: kotlinx.coroutines.flow.Flow<List<WatchPreference<*>>>,
    coreConfig: CoreConfig,
    libPebbleConfig: LibPebbleConfig,
): ConfigExport {
    val prefs = watchPrefsFlow.first()
    val prefsMap = mutableMapOf<String, String>()
    for (pref in prefs) {
        val value = pref.value ?: continue
        @Suppress("UNCHECKED_CAST")
        val typedPref = pref.pref as WatchPref<Any?>
        prefsMap[pref.pref.id] = typedPref.encodeValue(value)
    }
    return ConfigExport(
        exportedAt = Clock.System.now().toString(),
        watchPrefs = prefsMap,
        coreConfig = coreConfig,
        libPebbleConfig = libPebbleConfig,
    )
}

fun applyConfigImport(
    export: ConfigExport,
    libPebble: LibPebble,
    coreConfigHolder: CoreConfigHolder,
) {
    // Apply watch prefs
    for ((id, encodedValue) in export.watchPrefs) {
        val pref = WatchPref.from(id) ?: continue
        @Suppress("UNCHECKED_CAST")
        val typedPref = pref as WatchPref<Any?>
        val decoded = typedPref.decodeValue(encodedValue)
        libPebble.setWatchPref(WatchPreference(typedPref, decoded))
    }

    // Apply LibPebbleConfig
    export.libPebbleConfig?.let { config ->
        libPebble.updateConfig(config)
    }

    // Apply CoreConfig
    export.coreConfig?.let { config ->
        coreConfigHolder.update(config)
    }
}

fun serializeExport(export: ConfigExport): String {
    return exportJson.encodeToString(export)
}

fun deserializeExport(jsonString: String): ConfigExport {
    return importJson.decodeFromString(jsonString)
}
