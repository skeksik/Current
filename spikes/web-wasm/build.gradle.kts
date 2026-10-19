import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Spike (spike/web-ui): prototype C, Compose for web (Wasm, canvas) with HTML elements patched in for the sign-in form, links and
// video. Not used by the app.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "web-wasm"
        browser {
            commonWebpackConfig {
                outputFileName = "web-wasm.js"
            }
        }
        binaries.executable()
    }

    compilerOptions {
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":spikes:web-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.spike.coilCompose)
            implementation(libs.spike.coilNetworkKtor)
            implementation(libs.spike.ktorClientJs)
            implementation(libs.spike.kotlinxBrowser)
        }
    }
}
