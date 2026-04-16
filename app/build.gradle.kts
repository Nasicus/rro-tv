plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing. Pulls credentials from three sources in priority order
 * so both local `./gradlew :app:bundleRelease` and GitHub Actions CI work
 * without touching any file tracked by git:
 *
 * 1. Environment variables (CI) — SIGNING_KEYSTORE_FILE + SIGNING_KEY_* set
 *    by the release workflow after it base64-decodes the keystore secret.
 * 2. Gradle properties (local) — RRO_KEYSTORE_FILE / RRO_KEY_* in
 *    ~/.gradle/gradle.properties.
 * 3. Unsigned — returns null, release build will be unsigned (useful for
 *    `assembleRelease` smoke tests that don't need to install).
 */
fun releaseSigning(): Map<String, String>? {
    val env = System.getenv()
    val ciFile = env["SIGNING_KEYSTORE_FILE"]
    if (!ciFile.isNullOrBlank()) {
        return mapOf(
            "file" to ciFile,
            "storePassword" to env.getValue("SIGNING_KEYSTORE_PASSWORD"),
            "keyAlias" to env.getValue("SIGNING_KEY_ALIAS"),
            "keyPassword" to env.getValue("SIGNING_KEY_PASSWORD"),
        )
    }
    val localFile = providers.gradleProperty("RRO_KEYSTORE_FILE").orNull
    if (!localFile.isNullOrBlank()) {
        return mapOf(
            "file" to localFile,
            "storePassword" to providers.gradleProperty("RRO_KEYSTORE_PASSWORD").get(),
            "keyAlias" to providers.gradleProperty("RRO_KEY_ALIAS").get(),
            "keyPassword" to providers.gradleProperty("RRO_KEY_PASSWORD").get(),
        )
    }
    return null
}

android {
    namespace = "ch.nasicus.rro.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.nasicus.rro.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        releaseSigning()?.let { creds ->
            create("release") {
                storeFile = file(creds.getValue("file"))
                storePassword = creds.getValue("storePassword")
                keyAlias = creds.getValue("keyAlias")
                keyPassword = creds.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }
}

dependencies {
    val media3 = "1.5.0"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation("com.google.guava:guava:33.3.1-android")
}
