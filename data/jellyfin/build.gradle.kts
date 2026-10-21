import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()

    jvm {
        testRuns["test"].executionTask.configure {
            // The live smoke tests reach a real server only on request (-Pcurrent.liveTests=true), never because CURRENT_LIVE_* is set.
            systemProperty("current.liveTests", providers.gradleProperty("current.liveTests").getOrElse("false"))
        }
    }

    js {
        browser()
        // Only to run commonTest on Node (`jsNodeTest`), which needs no browser.
        nodejs()
    }

    android {
        namespace = "com.skeksik.current.data.jellyfin"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            api(project(":core:network"))
            api(project(":core:database"))
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.clientMock)
        }
    }
}
