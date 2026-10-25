import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

fun phonepiProp(name: String): String? {
    val env = System.getenv(name)?.trim().orEmpty()
    if (env.isNotEmpty()) return env
    val prop = (project.findProperty(name) as String?)?.trim().orEmpty()
    return prop.takeIf { it.isNotEmpty() }
}

val releaseStorePath = phonepiProp("PHONEPI_RELEASE_STORE_FILE")
val releaseStorePassword = phonepiProp("PHONEPI_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = phonepiProp("PHONEPI_RELEASE_KEY_ALIAS") ?: "phonepi"
val releaseKeyPassword = phonepiProp("PHONEPI_RELEASE_KEY_PASSWORD") ?: releaseStorePassword
val releaseStoreFile = releaseStorePath?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "dev.phonepi.wear"
    compileSdk = 36

    defaultConfig {
        // Same id as the companion. Required for Wear Data Layer pairing.
        applicationId = "dev.phonepi.companion"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword ?: ""
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: ""
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.matching { it.name.matches(Regex("assembleRelease|bundleRelease|packageRelease")) }.configureEach {
    doFirst {
        val store = releaseStoreFile
        check(store != null && store.isFile) {
            "assembleRelease needs PHONEPI_RELEASE_STORE_FILE (same key as the companion; see docs/release-signing.md)"
        }
        check(!releaseStorePassword.isNullOrEmpty()) {
            "assembleRelease needs PHONEPI_RELEASE_STORE_PASSWORD from env, never commit it"
        }
        val ks = KeyStore.getInstance(store.extension.lowercase().let { ext ->
            if (ext == "p12" || ext == "pfx") "PKCS12" else "JKS"
        })
        FileInputStream(store).use { ks.load(it, releaseStorePassword.toCharArray()) }
        check(ks.getCertificate(releaseKeyAlias) is X509Certificate) {
            "assembleRelease could not read the release cert; watch and phone must share this key"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material3:1.5.6")
    implementation("androidx.wear.compose:compose-foundation:1.5.6")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation(libs.play.services.wearable)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(libs.kotlinx.coroutines.play.services)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.guava:guava:33.4.8-android")

    testImplementation("junit:junit:4.13.2")
}
