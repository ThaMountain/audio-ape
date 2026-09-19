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
    api(project(":plugin:protocol"))

    // AA-018: :plugin:fixtures is a pure-JVM module. It depends only on
    // :plugin:protocol and must never import :core:database, :core:storage, or :app.
    // The isolation test enforces this structurally (build graph) and by reflection.
    testImplementation(libs.junit4)
}
