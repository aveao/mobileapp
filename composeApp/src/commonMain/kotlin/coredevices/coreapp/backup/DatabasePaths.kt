package coredevices.coreapp.backup

import io.rebble.libpebblecommon.connection.AppContext

internal const val CORE_DB_FILENAME = "coreapp.db"
internal const val RING_DB_FILENAME = "coreapp_room.db"

expect fun getCoreDatabasePath(appContext: AppContext): String
expect fun getRingDatabasePath(appContext: AppContext): String
