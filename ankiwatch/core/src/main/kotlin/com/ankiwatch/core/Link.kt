package com.ankiwatch.core

/**
 * What one side can see of the other over the Wearable Data Layer, in words the user can act
 * on. The other app shows up through its capability only when it is installed and signed
 * with the same key, so a connected device without the capability means the app is missing
 * there or comes from a different download (each CI build without a fixed key signs anew).
 */
object Link {

    enum class State {
        /** The other app is installed on a reachable device. */
        READY,

        /** A device is connected, but the other app isn't on it (or doesn't match). */
        APP_MISSING,

        /** No device is connected at all. */
        NO_DEVICE,

        /** The Wear OS link itself couldn't be queried. */
        UNAVAILABLE
    }

    data class Status(val state: State, val deviceName: String? = null, val error: String? = null)

    /**
     * @param appDevices names of reachable devices advertising the other app's capability,
     *   or null if that lookup failed
     * @param connectedDevices names of all connected devices, or null if that lookup failed
     * @param error the first lookup failure, if any
     */
    fun classify(appDevices: List<String>?, connectedDevices: List<String>?, error: String? = null): Status = when {
        !appDevices.isNullOrEmpty() -> Status(State.READY, appDevices.first().ifBlank { null })
        !connectedDevices.isNullOrEmpty() -> Status(State.APP_MISSING, connectedDevices.first().ifBlank { null })
        appDevices == null && connectedDevices == null -> Status(State.UNAVAILABLE, error = error ?: "unknown error")
        else -> Status(State.NO_DEVICE, error = error)
    }

    /** Short value for the phone app's "Watch" row; null while not yet checked. */
    fun phoneRow(status: Status?): String = when (status?.state) {
        null -> "Checking…"
        State.READY -> "Connected"
        State.APP_MISSING -> "AnkiWatch missing on watch"
        State.NO_DEVICE -> "No watch connected"
        State.UNAVAILABLE -> "Link unavailable"
    }

    /** What to do next, shown under the phone app's status rows. */
    fun phoneHint(status: Status?): String = when (status?.state) {
        null, State.READY ->
            "Open AnkiWatch on your watch to review. This phone app only needs to stay " +
                "installed; it doesn't have to be open."
        State.APP_MISSING ->
            "${status.deviceName ?: "Your watch"} is connected, but AnkiWatch isn't installed on " +
                "it, or it comes from a different download than this phone app. Install " +
                "ankiwatch-watch.apk from the same download as this app (see the README)."
        State.NO_DEVICE ->
            "This phone can't see any watch right now. Check that Bluetooth is on and the " +
                "watch shows as connected in Galaxy Wearable."
        State.UNAVAILABLE ->
            "Android couldn't check the watch link (Google Play services): ${status.error}"
    }

    /** What the watch says when it can't use the phone; null when it can. */
    fun watchMessage(status: Status?): String? = when (status?.state) {
        null, State.READY -> null
        State.APP_MISSING ->
            "Phone found, but AnkiWatch Phone isn't on it, or it's from a different download. " +
                "Install both apps from the same download."
        State.NO_DEVICE -> "Phone not connected"
        State.UNAVAILABLE -> "Can't check the phone link: ${status.error}"
    }
}
