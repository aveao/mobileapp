package coredevices.util

import PlatformContext
import platform.posix.exit

actual fun restartApp(platformContext: PlatformContext) {
    exit(0)
}
