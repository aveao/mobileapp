package coredevices.coreapp.backup

import coredevices.ring.database.MusicControlMode
import coredevices.ring.database.SecondaryMode
import coredevices.util.CoreConfig
import io.rebble.libpebblecommon.LibPebbleConfig
import kotlinx.serialization.Serializable

@Serializable
data class SettingsExport(
    val coreConfig: CoreConfig? = null,
    val libPebbleConfig: LibPebbleConfig? = null,
    val ringPreferences: RingPreferencesExport? = null,
)

@Serializable
data class RingPreferencesExport(
    val useCactusAgent: Boolean = false,
    val useCactusTranscription: Boolean = true,
    val cactusMode: Int = 0,
    val ringPaired: String? = null,
    val musicControlMode: Int = MusicControlMode.DoubleClick.id,
    val lastSyncIndex: Int? = null,
    val debugDetailsEnabled: Boolean = false,
    val approvedBeeperContacts: List<String> = emptyList(),
    val secondaryMode: Int = SecondaryMode.Search.id,
    val reminderProvider: Int = 1,
    val noteProvider: Int = 1,
    val noteShortcut: String? = null,
)
