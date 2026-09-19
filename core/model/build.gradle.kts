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
    testImplementation(libs.junit4)
    // AA-017: pure-JVM validation of the plugin-manifest contract schema (draft 2020-12).
    // Deliberately test-scope only: no Android runtime dependency, no production module.
    testImplementation(libs.json.schema.validator)
}
