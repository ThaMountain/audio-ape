import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // AA-021: :core:download is a pure-JVM transfer/verify/extract/commit engine.
    // It must stay free of Android, Room, SAF and player types; persistence (Room) and
    // transport (HTTP) are injected via the DownloadSource / DownloadCommitter seams.
    testImplementation(libs.junit4)
}
