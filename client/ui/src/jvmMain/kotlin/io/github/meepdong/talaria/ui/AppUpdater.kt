package io.github.meepdong.talaria.ui

/** How a platform installs an update from the bridge (spec §17): Android's session installer. */
interface AppUpdater {
    /** Whether this app may install apps right now (Android's "Install unknown apps" switch for it). */
    fun canInstall(): Boolean = true

    /** Opens the setting that allows it; Talaria carries on by itself when the user comes back ([TalariaController.resumeUpdate]). */
    fun askPermission() {}

    /** Hands a checked installer to the system. The answer comes back through [TalariaController.updateFailed], or the app is replaced. */
    fun install(apk: ByteArray)
}
