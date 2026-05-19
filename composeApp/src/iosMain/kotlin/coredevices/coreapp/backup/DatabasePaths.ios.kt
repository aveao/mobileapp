package coredevices.coreapp.backup

import io.rebble.libpebblecommon.connection.AppContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual fun getCoreDatabasePath(appContext: AppContext): String {
    return documentDirectory() + "/$CORE_DB_FILENAME"
}

actual fun getRingDatabasePath(appContext: AppContext): String {
    return documentDirectory() + "/$RING_DB_FILENAME"
}

private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}
