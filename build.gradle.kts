import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.yarn

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// Kotlin/JS's own npm packages (kotlin-js-store/yarn.lock). Ktor's JS client pins ws 8.20.1, which has a memory exhaustion DoS fixed
// in 8.21.0. ws runs only under Node (Kotlin/JS tooling); the web bundle replaces it with a stub (ADR 0011).
plugins.withType<YarnPlugin> {
    yarn.resolution("ws", "8.21.3")
}
