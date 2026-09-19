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
    // AA-018: :plugin:protocol is a pure-JVM boundary module and must stay free of
    // Android, Room, SAF and player types. It intentionally depends on nothing but the
    // Kotlin/JVM standard library.
    testImplementation(libs.junit4)
}
