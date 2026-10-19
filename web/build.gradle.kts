plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Umbrella library for the React web app in webApp/ (docs/decisions/0011), the web's counterpart of apple/. No Compose in here.
// A thin @JsExport facade: ViewModel StateFlows become Observables, actions are plain functions, no suspend function is exported.
kotlin {
    js {
        outputModuleName = "current-core"
        browser()
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }

    compilerOptions {
        optIn.add("kotlin.js.ExperimentalJsExport")
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
            implementation(project(":core:playback"))
            implementation(project(":data:jellyfin"))
            implementation(project(":data:seerr"))
            implementation(project(":data:stash"))
            implementation(project(":feature:home"))
            implementation(project(":feature:library"))
            implementation(project(":feature:requests"))
            implementation(project(":feature:player"))
            implementation(project(":feature:settings"))

            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}
