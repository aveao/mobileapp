package coredevices.coreapp.backup

import io.rebble.libpebblecommon.connection.AppContext

actual fun getCoreDatabasePath(appContext: AppContext): String {
    return appContext.context.applicationContext.getDatabasePath(CORE_DB_FILENAME).absolutePath
}

actual fun getRingDatabasePath(appContext: AppContext): String {
    return appContext.context.applicationContext.getDatabasePath(RING_DB_FILENAME).absolutePath
}
