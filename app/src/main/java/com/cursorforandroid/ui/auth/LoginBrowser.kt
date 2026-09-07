package com.cursorforandroid.ui.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Opens the cursor.com confirmation page in a Custom Tab: it shares the browser's cursor.com session, so a user who
 * is already signed in there only has to approve, and it stacks on this app's task, so the app can come back on top
 * once the approval has been polled. Falls back to whatever handles `https://` when no browser supports Custom Tabs;
 * returns false only when there is no browser at all.
 */
fun openLoginPage(context: Context, url: String, toolbar: Color): Boolean {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(false)
        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar.toArgb()).build())
        .build()
    return runCatching { intent.launchUrl(context, Uri.parse(url)) }.isSuccess
}
