import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.audioape.plugin.host"
    // API 36 is the repository's owner-verified Android 16 pin (AA-003); the vault is
    // an Android-only module and deliberately requires no Android runtime for its typed
    // envelope (VaultResult lives in the pure-JVM boundary side of this library).
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // AA-020: the SDK 36 pin (and all catalog pins) are frozen AA-003/AA-007 versions,
        // not silent upgrades; these advisories are not defects for this module either.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // AA-020: the vault is Android Keystore + JCA (AES/GCM). androidx.security:security-crypto
    // was deprecated by its own README ("use Android Keystore directly"); direct use is the
    // maintained approach, so no extra runtime crypto dependency is needed.
    api(project(":plugin:protocol"))

    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
}
