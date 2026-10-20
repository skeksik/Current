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

    jvm()

    js {
        browser()
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
            implementation(libs.ktor.clientMock)
        }
    }
}
