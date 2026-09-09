package coredevices.coreapp.backup

import co.touchlab.kermit.Logger
import com.oldguy.common.io.File
import com.oldguy.common.io.FileMode
import com.oldguy.common.io.ZipEntry
import com.oldguy.common.io.ZipFile
import coredevices.database.CoreDatabase
import coredevices.ring.database.Preferences
import coredevices.ring.database.room.RingDatabase
import coredevices.util.BackupRestore
import coredevices.util.CoreConfigHolder
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.util.getTempFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.transferFrom
import kotlinx.io.writeString
import kotlinx.serialization.json.Json

class BackupManager(
    private val appContext: AppContext,
    private val coreDatabase: CoreDatabase,
    private val ringDatabase: RingDatabase,
    private val coreConfigHolder: CoreConfigHolder,
    private val libPebble: LibPebble,
    private val preferences: Preferences,
    private val clock: Clock = Clock.System,
) : BackupRestore {
    private val logger = Logger.withTag("BackupManager")
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val dbFiles: Map<String, String>
        get() = mapOf(
            CORE_DB_FILENAME to getCoreDatabasePath(appContext),
            "libpebble3.db" to libPebble.getDatabasePath(),
            RING_DB_FILENAME to getRingDatabasePath(appContext),
        )

    override suspend fun createBackup(): Path = withContext(Dispatchers.IO) {
        logger.i { "Starting backup..." }

        val timestamp = clock.now()
        val settings = exportSettings()

        val zipPath = getTempFilePath(appContext, "backup-$timestamp.pebblebak", "backups")
        val zipPathStr = zipPath.toString()
        logger.i { "Creating ZIP at $zipPathStr" }

        val zipFile = ZipFile(File(zipPathStr), mode = FileMode.Write)
        zipFile.use {
            val settingsBuf = Buffer().also { it.writeString(json.encodeToString(settings)) }
            zipFile.addEntry(ZipEntry("settings.json"), { settingsBuf.readByteArray() })

            for ((filename, path) in dbFiles) {
                // Add .db file
                zipFile.zipFile(File(path), "databases/$filename")
                logger.d { "Added $filename (${File(path).size} bytes)" }

                // Add WAL and SHM if they exist
                val walFile = File("$path-wal")
                if (walFile.exists) {
                    zipFile.zipFile(walFile, "databases/$filename-wal")
                    logger.d { "Added $filename-wal (${walFile.size} bytes)" }
                }
                val shmFile = File("$path-shm")
                if (shmFile.exists) {
                    zipFile.zipFile(shmFile, "databases/$filename-shm")
                    logger.d { "Added $filename-shm (${shmFile.size} bytes)" }
                }
            }

            // Add cached PBW files. Sideloaded apps can't be re-fetched, so they must be
            // included; store-app caches come along too since we zip the whole directory.
            val pbwDir = Path(libPebble.getPbwCacheDirectory())
            if (SystemFileSystem.exists(pbwDir)) {
                addDirectoryToZip(zipFile, pbwDir, "pbw")
            }
        }

        logger.i { "Backup created at $zipPath (${File(zipPathStr).size} bytes)" }
        zipPath
    }

    override suspend fun restoreBackup(source: kotlinx.io.RawSource): Unit = withContext(Dispatchers.IO) {
        logger.i { "Starting restore..." }

        val tempZipPath = getTempFilePath(appContext, "restore-temp.zip", "backups")
        source.buffered().use { bufferedSource ->
            SystemFileSystem.sink(tempZipPath).buffered().use { sink ->
                sink.transferFrom(bufferedSource)
            }
        }
        logger.i { "Temp ZIP written (${File(tempZipPath.toString()).size} bytes)" }

        val zipFile = ZipFile(File(tempZipPath.toString()), mode = FileMode.Read)
        zipFile.use {
            val zipEntryNames = zipFile.entries.map { e -> e.name }.toSet()
            logger.d { "ZIP entries: $zipEntryNames" }

            // Read settings
            var settingsJson = ""
            zipFile.readTextEntry("settings.json") { text, _ -> settingsJson += text }
            val settings = json.decodeFromString<SettingsExport>(settingsJson)

            // Close all databases before overwriting files
            logger.i { "Closing databases..." }
            coreDatabase.close()
            ringDatabase.close()
            libPebble.closeDatabase()

            // Extract database files, WALs, and SHMs
            for ((filename, targetPath) in dbFiles) {
                for (suffix in listOf("", "-wal", "-shm")) {
                    val entryName = "databases/$filename$suffix"
                    val filePath = "$targetPath$suffix"

                    if (entryName in zipEntryNames) {
                        extractEntry(zipFile, entryName, filePath)
                        logger.d { "Restored $filename$suffix" }
                    } else if (suffix.isNotEmpty()) {
                        // WAL/SHM not in backup — delete local copy so Room doesn't use stale data
                        deleteIfExists(filePath)
                        logger.d { "Deleted local $filename$suffix (not in backup)" }
                    }
                }
            }

            // Restore PBW cache. Clear existing first so stale entries from the old install
            // don't linger after restore.
            val pbwDir = Path(libPebble.getPbwCacheDirectory())
            clearDirectory(pbwDir)
            SystemFileSystem.createDirectories(pbwDir, false)
            val pbwEntries = zipEntryNames.filter { it.startsWith("pbw/") && !it.endsWith("/") }
            for (entryName in pbwEntries) {
                val relative = entryName.removePrefix("pbw/")
                val targetPath = Path(pbwDir, relative)
                SystemFileSystem.createDirectories(targetPath.parent ?: pbwDir, false)
                extractEntry(zipFile, entryName, targetPath.toString())
            }
            logger.d { "Restored ${pbwEntries.size} PBW cache files" }

            // Restore settings
            logger.i { "Restoring settings..." }
            restoreSettings(settings)
        }

        try { SystemFileSystem.delete(tempZipPath) } catch (_: Exception) {}
        logger.i { "Restore complete. App restart required." }
    }

    private suspend fun extractEntry(zipFile: ZipFile, entryName: String, targetPath: String) {
        val sink = SystemFileSystem.sink(Path(targetPath)).buffered()
        sink.use {
            zipFile.readEntry(entryName) { _, bytes, count, _ ->
                sink.write(bytes, 0, count.toInt())
            }
        }
    }

    private suspend fun exportSettings(): SettingsExport {
        return SettingsExport(
            coreConfig = coreConfigHolder.config.first(),
            libPebbleConfig = libPebble.config.first(),
            ringPreferences = exportRingPreferences(),
        )
    }

    private fun exportRingPreferences(): RingPreferencesExport {
        return RingPreferencesExport(
            llmMode = preferences.llmMode.value.id,
            useCactusTranscription = preferences.useCactusTranscription.value,
            cactusMode = preferences.cactusMode.id,
            ringPaired = preferences.ringPaired.value,
            musicControlMode = preferences.musicControlMode.value.id,
            lastSyncIndex = preferences.lastSyncIndex.value,
            debugDetailsEnabled = preferences.debugDetailsEnabled.value,
            approvedBeeperContacts = preferences.approvedBeeperContacts.value,
            secondaryMode = preferences.secondaryMode.value.id,
            secondaryModeMcpGroupId = preferences.secondaryModeMcpGroupId.value,
            reminderProvider = preferences.reminderProvider.value.id,
            noteProvider = preferences.noteProvider.value.id,
            noteShortcut = try {
                Json.encodeToString(preferences.noteShortcut.value)
            } catch (_: Exception) { null },
            autoDismissActionNotifications = preferences.autoDismissActionNotifications.value,
            phoneCalendarEnabled = preferences.phoneCalendarEnabled.value,
            platformSttDefaulted = preferences.platformSttDefaulted,
            backupEnabled = preferences.backupEnabled.value,
            useEncryption = preferences.useEncryption.value,
            defaultCaptureType = preferences.defaultCaptureType.value.id,
        )
    }

    private suspend fun restoreSettings(settings: SettingsExport) {
        settings.coreConfig?.let { coreConfigHolder.update(it) }
        settings.libPebbleConfig?.let { libPebble.updateConfig(it) }
        settings.ringPreferences?.let { restoreRingPreferences(it) }
    }

    private suspend fun restoreRingPreferences(prefs: RingPreferencesExport) {
        preferences.setLlmMode(coredevices.ring.agent.LlmMode.fromId(prefs.llmMode))
        preferences.setUseCactusTranscription(prefs.useCactusTranscription)
        preferences.setCactusMode(coredevices.util.models.CactusSTTMode.fromId(prefs.cactusMode))
        preferences.setRingPaired(prefs.ringPaired)
        preferences.setMusicControlMode(coredevices.ring.database.MusicControlMode.fromId(prefs.musicControlMode))
        preferences.setLastSyncIndex(prefs.lastSyncIndex)
        preferences.setDebugDetailsEnabled(prefs.debugDetailsEnabled)
        preferences.setApprovedBeeperContacts(prefs.approvedBeeperContacts.ifEmpty { null })
        preferences.setSecondaryMode(coredevices.ring.database.SecondaryMode.fromId(prefs.secondaryMode))
        preferences.setSecondaryModeMcpGroupId(prefs.secondaryModeMcpGroupId)
        preferences.setAutoDismissActionNotifications(prefs.autoDismissActionNotifications)
        preferences.setPhoneCalendarEnabled(prefs.phoneCalendarEnabled)
        preferences.setBackupEnabled(prefs.backupEnabled)
        preferences.setUseEncryption(prefs.useEncryption)
        preferences.setDefaultCaptureType(coredevices.ring.agent.DefaultCaptureType.fromId(prefs.defaultCaptureType))
        // One-way latch; a false value just means it hasn't fired yet.
        if (prefs.platformSttDefaulted) preferences.setPlatformSttDefaulted()
        coredevices.ring.agent.builtin_servlets.reminders.ReminderProvider.fromId(prefs.reminderProvider)
            ?.let { preferences.setReminderProvider(it) }
        coredevices.ring.agent.builtin_servlets.notes.NoteProvider.fromId(prefs.noteProvider)
            ?.let { preferences.setNoteProvider(it) }
        prefs.noteShortcut?.let {
            try {
                preferences.setNoteShortcut(Json.decodeFromString<coredevices.ring.data.NoteShortcutType>(it))
            } catch (_: Exception) {}
        }
    }

    private fun deleteIfExists(path: String) {
        try { SystemFileSystem.delete(Path(path)) } catch (_: Exception) {}
    }

    private suspend fun addDirectoryToZip(zipFile: ZipFile, dir: Path, entryPrefix: String) {
        SystemFileSystem.list(dir).forEach { child ->
            val meta = SystemFileSystem.metadataOrNull(child) ?: return@forEach
            val entryName = "$entryPrefix/${child.name}"
            if (meta.isRegularFile) {
                zipFile.zipFile(File(child.toString()), entryName)
                logger.d { "Added $entryName (${meta.size} bytes)" }
            } else if (meta.isDirectory) {
                addDirectoryToZip(zipFile, child, entryName)
            }
        }
    }

    private fun clearDirectory(dir: Path) {
        if (!SystemFileSystem.exists(dir)) return
        SystemFileSystem.list(dir).forEach { child ->
            val meta = SystemFileSystem.metadataOrNull(child)
            if (meta?.isDirectory == true) {
                clearDirectory(child)
            }
            try { SystemFileSystem.delete(child) } catch (_: Exception) {}
        }
    }
}
