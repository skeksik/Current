import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()

    jvm()

    js {
        browser()
        // Only to run commonTest on Node (`jsNodeTest`), which needs no browser.
        nodejs()
    }

    android {
        namespace = "com.skeksik.current.feature.requests"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
