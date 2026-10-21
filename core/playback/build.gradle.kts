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

    jvm {
        testRuns["test"].executionTask.configure {
            // The web probe answers recorded in real browsers (ADR 0011) are the fixtures of the probe tests.
            val webProbes = layout.projectDirectory.dir("../../docs/web-probes")
            inputs.dir(webProbes).withPropertyName("webProbes").withPathSensitivity(PathSensitivity.RELATIVE)
            systemProperty("current.webProbes", webProbes.asFile.absolutePath)
        }
    }

    js {
        browser()
        // Only to run commonTest on Node (`jsNodeTest`), which needs no browser.
        nodejs()
    }

    android {
        namespace = "com.skeksik.current.core.playback"
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
