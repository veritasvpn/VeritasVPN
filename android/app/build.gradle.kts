plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.gradleProperty("VERITAS_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("VERITAS_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("VERITAS_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("VERITAS_RELEASE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val releaseTaskRequested = gradle.startParameter.taskNames.any { task ->
    val name = task.substringAfterLast(":")
    name.contains("Release", ignoreCase = true) && (
        name.contains("assemble", ignoreCase = true) ||
            name.contains("bundle", ignoreCase = true) ||
            name.contains("publish", ignoreCase = true)
        )
}

if (releaseTaskRequested && !releaseSigningConfigured) {
    throw GradleException("A signed VeritasVPN release requires the VERITAS_RELEASE_* signing properties.")
}

android {
    namespace = "cloud.veritasvpn"
    // Android 16 / API 36 — required for the current Google Play target level.
    compileSdk = 36

    defaultConfig {
        applicationId = "cloud.veritasvpn"
        minSdk = 26
        targetSdk = 36
        // Google Play requires a new monotonically increasing version code for
        // every uploaded release artifact.
        versionCode = 75
        versionName = "0.2.91"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("veritasRelease") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("veritasRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // play: Google Play Billing only. This is the artifact uploaded to Play.
    // direct: sideloaded website APK. It keeps the existing BTCPay checkout.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "PLAY_BILLING", "true")
        }
        create("direct") {
            dimension = "distribution"
            buildConfigField("boolean", "PLAY_BILLING", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
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
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.caverock:androidsvg-aar:1.4")

    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // Play Billing stays off the website APK. The direct flavor cannot call it.
    "playImplementation"("com.android.billingclient:billing:9.1.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
