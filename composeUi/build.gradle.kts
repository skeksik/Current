import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    android {
        namespace = "com.skeksik.current.composeui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.coil.compose)
            implementation(libs.coil.networkKtor)
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:playback"))
            implementation(project(":feature:home"))
            implementation(project(":feature:library"))
            implementation(project(":feature:requests"))
            implementation(project(":feature:player"))
            implementation(project(":feature:settings"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
