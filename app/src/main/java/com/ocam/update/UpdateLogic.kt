package com.ocam.update

import java.io.File
import java.security.MessageDigest

/** The rolling release every build of the app is published to. */
const val RELEASE_API_URL = "https://api.github.com/repos/cyberhirsch/oCam/releases/tags/debug-latest"

/** The asset whose name never changes. The versioned one beside it says which build this is. */
const val FIXED_APK_NAME = "oCam.apk"

/** The version code of a build with no CI run number, which never came from the release. */
private const val LOCAL_BUILD_VERSION_CODE = 1L

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** A build on the release: the number Android compares, and the name people read. */
data class ReleaseBuild(val versionCode: Long, val label: String)

/** A build worth offering, and where to get it. */
data class UpdateOffer(val build: ReleaseBuild, val apkUrl: String, val sizeBytes: Long)

/** How far an update has got. Only [Checking] and [Downloading] have work running. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val offer: UpdateOffer) : UpdateState
    data class Downloading(val offer: UpdateOffer, val percent: Int) : UpdateState
    data class Ready(val offer: UpdateOffer, val file: File) : UpdateState
    data class Installing(val ready: Ready) : UpdateState
    data class Failed(val message: String) : UpdateState
}

private val VERSIONED_ASSET = Regex("""^oCam-(\d+\.\d+\.(\d+))(?:-([0-9a-f]+))?\.apk$""")

/**
 * The build a versioned asset holds, from `oCam-1.0.37-5b309bd.apk`. The third number is the
 * CI run, which is also the version code, and the name is taken from what the build itself stamped.
 */
fun parseVersionedAsset(name: String): ReleaseBuild? {
    val match = VERSIONED_ASSET.matchEntire(name) ?: return null
    val (version, run, commit) = match.destructured
    val versionCode = run.toLongOrNull() ?: return null
    val label = if (commit.isEmpty()) version else "$version+$commit"
    return ReleaseBuild(versionCode, label)
}

/**
 * The newest build among a release's assets. While CI is replacing the files two versioned assets
 * can stand side by side; the file itself is checked again after it has been downloaded.
 */
fun newestBuild(assetNames: List<String>): ReleaseBuild? =
    assetNames.mapNotNull(::parseVersionedAsset).maxByOrNull { it.versionCode }

fun isNewer(release: Long, installed: Long): Boolean = release > installed

/** At most once a day. A clock that went backwards must not silence the check for good. */
fun checkDue(now: Long, lastCheck: Long?): Boolean =
    lastCheck == null || now < lastCheck || now - lastCheck >= DAY_MILLIS

/**
 * A local build is signed with the developer's own key, so nothing on the release can replace it.
 * The settings button still asks, and says so; the launch check would only nag.
 */
fun automaticCheckWanted(installedVersionCode: Long): Boolean =
    installedVersionCode > LOCAL_BUILD_VERSION_CODE

/** A build must not take the app down during a run, the merge after it, or a timer. */
fun safeToInstall(busy: Boolean, runInProgress: Boolean, timerRunning: Boolean): Boolean =
    !busy && !runInProgress && !timerRunning

/** A signing certificate's identity, in the form `apksigner` prints it. */
fun certificateFingerprint(certificate: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(certificate)
        .joinToString("") { "%02x".format(it) }

/** What a downloaded file says about itself, or null when Android could not read it. */
data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

enum class Verdict(val message: String) {
    OK(""),
    UNREADABLE("The download is damaged. Try again."),
    WRONG_PACKAGE("The download is not oCam, so it was thrown away."),
    DIFFERENT_KEY(
        "This build is signed with another key, so Android would refuse it. Uninstall oCam once " +
            "and install it from the release page. Settings and film simulations go with it."
    ),
    NOT_NEWER("The newest build is still being published. Try again in a minute."),
}

/** Whether a downloaded file may be handed to the installer. */
fun judgeDownload(
    archive: ApkFacts?,
    ownPackage: String,
    installedVersionCode: Long,
    installedSigners: Set<String>,
): Verdict = when {
    archive == null -> Verdict.UNREADABLE
    archive.packageName != ownPackage -> Verdict.WRONG_PACKAGE
    // An empty set is a failure to read the certificates and never a match.
    archive.signers.isEmpty() || archive.signers != installedSigners -> Verdict.DIFFERENT_KEY
    !isNewer(archive.versionCode, installedVersionCode) -> Verdict.NOT_NEWER
    else -> Verdict.OK
}
