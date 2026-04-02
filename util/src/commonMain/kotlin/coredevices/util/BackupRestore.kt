package coredevices.util

import kotlinx.io.RawSource
import kotlinx.io.files.Path

interface BackupRestore {
    suspend fun createBackup(): Path
    suspend fun restoreBackup(source: RawSource)
}
