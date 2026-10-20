import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// AppInfo.version comes from `current.version` in gradle.properties, so servers see the version the build was made from.
val generateAppInfo = tasks.register("generateAppInfo") {
    val version = providers.gradleProperty("current.version")
    val outputDirectory = layout.buildDirectory.dir("generated/appInfo/commonMain/kotlin")
    inputs.property("version", version)
    outputs.dir(outputDirectory)
    doLast {
        val file = outputDirectory.get().file("com/skeksik/current/core/model/AppInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.skeksik.current.core.model
            |
            |/** Generated from gradle.properties; do not edit. */
            |object AppInfo {
            |    const val NAME: String = "Current"
            |    const val VERSION: String = "${version.get()}"
            |}
            |""".trimMargin(),
        )
    }
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
        namespace = "com.skeksik.current.core.model"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppInfo)
        }
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
