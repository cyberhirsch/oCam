plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Every CI run stamps its own number, so two builds are never the same version and Android will
// treat a newer one as an upgrade. A local build has no number and stays at 1.
val baseVersion = "1.0"
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "0").toIntOrNull() ?: 0
val buildCommit = System.getenv("BUILD_COMMIT")?.take(7)

// CI restores the one key every published build must share, or Android refuses each new build as
// an update. Without it, as on a developer's machine, a debug build uses the local debug key.
val stableKeystore = System.getenv("OCAM_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.ocam"
    compileSdk = 35

    signingConfigs {
        if (stableKeystore != null) {
            create("stable") {
                storeFile = file(stableKeystore)
                storePassword = System.getenv("OCAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OCAM_KEY_ALIAS")
                keyPassword = System.getenv("OCAM_KEYSTORE_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.ocam"
        // MediaStore RELATIVE_PATH and direct opening of physical cameras both need API 29.
        minSdk = 29
        targetSdk = 35
        versionCode = maxOf(buildNumber, 1)
        versionName = buildString {
            append(baseVersion).append('.').append(buildNumber)
            // The commit is what turns a bug report into something reproducible.
            if (buildCommit != null) append('+').append(buildCommit)
        }
    }

    buildTypes {
        debug {
            if (stableKeystore != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
