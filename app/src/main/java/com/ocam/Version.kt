package com.ocam

import android.content.Context

/**
 * What this build calls itself: the version name Gradle stamped in, which is the CI run number
 * and the commit it was built from. It is written into the APK's manifest, into the file name of
 * the download, and shown in settings, so a build on a phone can always be traced back to the
 * code that produced it.
 */
fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"
