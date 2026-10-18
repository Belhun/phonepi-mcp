import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
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

fun phonepiUpdatePubKey(): String {
    phonepiProp("PHONEPI_UPDATE_PUBKEY")?.lowercase()?.removePrefix("0x")?.let { return it }
    val fromFile = phonepiProp("PHONEPI_UPDATE_PUBKEY_FILE")
        ?: "${System.getProperty("user.home")}/.phonepi/release/manifest-ed25519.pub"
    val file = file(fromFile)
    if (file.isFile) {
        return file.readText().trim().lowercase()
    }
    return ""
}

fun phonepiPinnedCertSha256(storeFile: java.io.File?, storePassword: String?, keyAlias: String?): String {
    phonepiProp("PHONEPI_PINNED_CERT_SHA256")?.lowercase()?.removePrefix("0x")?.let { return it }
    if (storeFile == null || !storeFile.isFile || storePassword.isNullOrEmpty() || keyAlias.isNullOrEmpty()) {
        return ""
    }
    val ks = KeyStore.getInstance(storeFile.extension.lowercase().let { ext ->
        if (ext == "p12" || ext == "pfx") "PKCS12" else "JKS"
    })
    FileInputStream(storeFile).use { ks.load(it, storePassword.toCharArray()) }
    val cert = ks.getCertificate(keyAlias) as X509Certificate
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    return digest.joinToString("") { b -> "%02x".format(b) }
}

val releaseStorePath = phonepiProp("PHONEPI_RELEASE_STORE_FILE")
val releaseStorePassword = phonepiProp("PHONEPI_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = phonepiProp("PHONEPI_RELEASE_KEY_ALIAS") ?: "phonepi"
val releaseKeyPassword = phonepiProp("PHONEPI_RELEASE_KEY_PASSWORD") ?: releaseStorePassword
val releaseStoreFile = releaseStorePath?.let { file(it) }?.takeIf { it.isFile }
val pinnedReleaseCert = phonepiPinnedCertSha256(releaseStoreFile, releaseStorePassword, releaseKeyAlias)
val updateManifestPublicKey = phonepiUpdatePubKey()

android {
    namespace = "dev.phonepi.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.phonepi.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PINNED_RELEASE_CERT_SHA256", "\"$pinnedReleaseCert\"")
        buildConfigField("boolean", "REQUIRE_RELEASE_CERT_PIN", "false")
        buildConfigField("String", "UPDATE_MANIFEST_PUBLIC_KEY", "\"$updateManifestPublicKey\"")
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
            buildConfigField("boolean", "REQUIRE_RELEASE_CERT_PIN", "false")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "REQUIRE_RELEASE_CERT_PIN", "true")
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
            "assembleRelease needs PHONEPI_RELEASE_STORE_FILE (see docs/release-signing.md)"
        }
        check(!releaseStorePassword.isNullOrEmpty()) {
            "assembleRelease needs PHONEPI_RELEASE_STORE_PASSWORD from env, never commit it"
        }
        check(pinnedReleaseCert.length == 64) {
            "assembleRelease could not compute PINNED_RELEASE_CERT_SHA256 from the keystore"
        }
        check(updateManifestPublicKey.length == 64) {
            "assembleRelease needs PHONEPI_UPDATE_PUBKEY or ~/.phonepi/release/manifest-ed25519.pub"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.eddsa)
    implementation(libs.play.services.location)

    testImplementation("junit:junit:4.13.2")
}
