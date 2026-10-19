// Spike (spike/web-ui): prototype A, Compose HTML (real DOM) over the shared core. Not used by the app.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "web-html.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":spikes:web-core"))
            implementation(libs.compose.runtime)
            implementation(libs.spike.composeHtmlCore)
        }
    }
}
