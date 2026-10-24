import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
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

// Release uses the PhonePi companion keystore so both apps share a signing
// cert. The clock control permission is signature-level and stays denied
// when the certs differ. Debug uses ~/.android/debug.keystore.
// SHA-1 for a Spotify app (package dev.phonepi.clock): ./gradlew :app:signingReport
val releaseStorePath = phonepiProp("PHONEPI_RELEASE_STORE_FILE")
val releaseStorePassword = phonepiProp("PHONEPI_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = phonepiProp("PHONEPI_RELEASE_KEY_ALIAS") ?: "phonepi"
val releaseKeyPassword = phonepiProp("PHONEPI_RELEASE_KEY_PASSWORD") ?: releaseStorePassword
val releaseStoreFile = releaseStorePath?.let { file(it) }?.takeIf { it.isFile }

fun certSha1(storeFile: java.io.File?, storePassword: String?, keyAlias: String?): String {
    if (storeFile == null || !storeFile.isFile || storePassword.isNullOrEmpty() || keyAlias.isNullOrEmpty()) {
        return ""
    }
    val ks = KeyStore.getInstance(
        if (storeFile.extension.equals("p12", true) || storeFile.extension.equals("pfx", true)) "PKCS12" else "JKS"
    )
    FileInputStream(storeFile).use { ks.load(it, storePassword.toCharArray()) }
    val cert = ks.getCertificate(keyAlias) as X509Certificate
    return MessageDigest.getInstance("SHA-1").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}

android {
    namespace = "dev.phonepi.clock"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.phonepi.clock"
        minSdk = 26
        targetSdk = 36
        versionCode = phonepiProp("clockVersionCode")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "RELEASE_CERT_SHA1", "\"${certSha1(releaseStoreFile, releaseStorePassword, releaseKeyAlias)}\"")
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
            // Default debug keystore: ~/.android/debug.keystore, alias androiddebugkey.
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.matching { it.name.matches(Regex("assembleRelease|bundleRelease|packageRelease")) }.configureEach {
    doFirst {
        check(releaseStoreFile != null && releaseStoreFile.isFile) {
            "assembleRelease needs PHONEPI_RELEASE_STORE_FILE, the same keystore as the PhonePi companion"
        }
    }
}

dependencies {
    implementation(project(":schedule"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation("androidx.test:core:1.6.1")
}
