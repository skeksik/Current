// Spike (spike/web-ui): prototype B's Kotlin side, a thin @JsExport facade over the shared core, built as an ES module library
// with TypeScript definitions for the React + Vite app in this folder. Not used by the app.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    js {
        outputModuleName = "current-core"
        browser()
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":spikes:web-core"))
        }
    }
}
