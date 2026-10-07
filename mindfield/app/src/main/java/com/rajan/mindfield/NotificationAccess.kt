package com.rajan.mindfield

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Turns notifications back on the way Android allows: its permission prompt while it can still be
 * shown (Android 13 and later, until you've said no twice), otherwise the app's notification
 * settings, which is also where a reminder switched off on its own is turned back on.
 * [onResult] runs after the prompt; coming back from the settings page re-checks on resume.
 */
@Composable
fun rememberEnableNotifications(onResult: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    return remember(context, launcher) {
        {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val canAsk = Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED &&
                (!Notifier.askedBefore(context) || context.findActivity()?.shouldShowRequestPermissionRationale(permission) == true)
            if (canAsk) {
                Notifier.markAsked(context)
                launcher.launch(permission)
            } else {
                Notifier.openSettings(context)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
