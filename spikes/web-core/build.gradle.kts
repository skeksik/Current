import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Spike (spike/web-ui): shared core for the three web UI prototypes. Not used by the app.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.spike.kotlinSerialization)
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            // Opt-in live check against a test server (-Pspike.live=true); the server comes from a file outside the repo.
            val live = providers.gradleProperty("spike.live").orNull == "true"
            systemProperty("spike.live", live.toString())
            systemProperty("spike.serverEnv", System.getProperty("user.home") + "/.current/test-server.env")
            if (live) outputs.upToDateWhen { false }
        }
    }

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    compilerOptions {
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.spike.coroutinesCore)
            api(libs.spike.lifecycleViewmodel)
            implementation(libs.spike.ktorClientCore)
            implementation(libs.spike.ktorClientContentNegotiation)
            implementation(libs.spike.ktorSerializationJson)
            implementation(libs.spike.serializationJson)
        }
        webMain.dependencies {
            implementation(libs.spike.ktorClientJs)
            implementation(libs.spike.kotlinxBrowser)
            implementation(npm("hls.js", "1.7.3"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.spike.coroutinesTest)
            implementation(libs.spike.ktorClientMock)
        }
        jvmTest.dependencies {
            implementation(libs.spike.ktorClientJava)
        }
    }
}
