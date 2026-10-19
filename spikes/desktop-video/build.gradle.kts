// Spike prototype for the M1 "Desktop video" spike (docs/roadmap.md). Not used by the app.
// Candidate 2: libmpv in a native child window, with Compose controls in a transparent window on top.
// Candidate 1 (measured briefly): MediaMP draws libmpv into Compose as a GPU texture.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// libmpv-2.dll and its dependencies (an LGPL-2.1 build: mpv -Dgpl=false, FFmpeg without --enable-gpl/--enable-version3),
// taken from MediaMP's Windows runtime jar. Candidate 2 uses only the DLLs; candidate 1 also uses MediaMP's code.
val mpvRuntime: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.spike.jna)
    implementation(libs.spike.mediampMpv)
    runtimeOnly(libs.spike.mpvRuntimeWindowsX64)

    mpvRuntime(libs.spike.mpvRuntimeWindowsX64)

    testImplementation(libs.kotlin.test)
}

// Headless libmpv smoke test: ./gradlew :spikes:desktop-video:test -Pspike.testMedia=/path/to/file.mkv
tasks.test {
    providers.gradleProperty("spike.testMedia").orNull?.let { systemProperty("spike.testMedia", it) }
    systemProperty("spike.outDir", layout.buildDirectory.dir("spike-out").get().asFile.absolutePath)
    testLogging { showStandardStreams = true }
}

val extractMpv by tasks.registering(Sync::class) {
    from({ mpvRuntime.map { zipTree(it) } }) {
        include("*.dll", "*.txt")
    }
    into(layout.buildDirectory.dir("mpv"))
}

// Pass -PmpvDir=C:\path\to\dir to load another libmpv-2.dll instead of the extracted one.
val mpvDir: String = providers.gradleProperty("mpvDir").orNull
    ?: layout.buildDirectory.dir("mpv").get().asFile.absolutePath
val outDir: String = layout.buildDirectory.dir("spike-out").get().asFile.absolutePath

// Every -Pspike.<name>=<value> becomes the system property spike.<name> (see README: spike.source skips the launcher).
val spikeProperties: Map<String, String> = providers.gradlePropertiesPrefixedBy("spike.").get()
// -PskikoRenderApi=OPENGL|SOFTWARE|DIRECT3D picks Compose's renderer (Windows default: DIRECT3D).
val skikoRenderApi: String? = providers.gradleProperty("skikoRenderApi").orNull

compose.desktop {
    application {
        mainClass = "com.skeksik.current.spike.desktopvideo.MainKt"
        jvmArgs += listOf(
            "-Dspike.mpvDir=$mpvDir",
            "-Dspike.outDir=$outDir",
            // A native crash writes its report next to the spike's other logs.
            "-XX:ErrorFile=$outDir${File.separator}hs_err_pid%p.log",
        )
        jvmArgs += spikeProperties.filterKeys { it != "spike.testMedia" }.map { (k, v) -> "-D$k=$v" }
        skikoRenderApi?.let { jvmArgs += "-Dskiko.renderApi=$it" }
    }
}

tasks.matching { it.name == "run" }.configureEach {
    dependsOn(extractMpv)
}
