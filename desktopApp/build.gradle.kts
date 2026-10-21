import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    // AppInfo.NAME for the window title.
    implementation(project(":core:model"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    // The dark title bar on Windows (WindowsTitleBar); already used by core:database (ADR 0017).
    implementation(libs.jna)

    implementation(libs.compose.uiToolingPreview)
}

// The window icon reads the installer's PNG from the classpath (main.kt), so there is one icon file per format.
sourceSets.main {
    resources.srcDir("icons")
    resources.exclude("*.ico")
}

// The one version source (gradle.properties), also used by AppInfo and the Android app.
val appVersion = providers.gradleProperty("current.version").get()

// The JDK that jpackage runs from and copies into the installer, so the bundled runtime does not depend on the JDK that runs Gradle.
// Same major version as CI (Temurin there). No vendor pin: this resolves while desktopApp is configured, for every build, and any
// installed JDK 21 then matches without a download; the foojay resolver (settings.gradle.kts) fetches one only when none is installed.
val packagingJdk = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
}

compose.desktop {
    application {
        mainClass = "com.skeksik.current.MainKt"
        javaHome = packagingJdk.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            // The installed app's name (installer, Start menu, launcher); the app ID stays com.skeksik.current.
            packageName = "Current"
            packageVersion = appVersion
            description = "Media client for Jellyfin, Seerr and Stash"
            vendor = "skeksik"

            // Added to the plugin's defaults (java.base, java.desktop, java.logging, jdk.crypto.ec), which jlink copies into the
            // runtime. java.net.http: ktor-client-java. jdk.accessibility: Java Access Bridge for screen readers on Windows.
            // jdk.unsupported and java.instrument: what suggestRuntimeModules (jdeps) reported in CI on 2026-10-21, after the first two
            // were listed (java.instrument is kotlinx-coroutines' debug agent). CI checks the packaged runtime has them all.
            modules("java.net.http", "jdk.accessibility", "jdk.unsupported", "java.instrument")

            windows {
                // Rendered once from docs/brand/current-icon.svg (a placeholder): the ICO holds 16-256 px, the PNG is 512 px.
                iconFile.set(project.file("icons/current.ico"))
                // Never change: it ties every version's MSI together, so a newer one upgrades in place.
                upgradeUuid = "420fb9fa-28cd-429a-8bf8-bccfb857d4b4"
                // Installs under the user's profile without an administrator prompt; upgrades are per user too.
                perUserInstall = true
                // The install folder, relative to %LOCALAPPDATA% for a per-user install. Never jpackage's default (the app name):
                // %LOCALAPPDATA%\Current is AppFolders.local(), and removing the product deletes INSTALLDIR recursively, which took
                // local.json with it. Not Programs\Current either: jpackage gives intermediate per-user folders no RemoveFolder entry,
                // which fails WiX's ICE64 (docs/platform-notes.md, "Desktop packaging"). Only the folder differs; every name shown
                // to users stays "Current". Nothing may ever be written under the install folder: uninstalling deletes it.
                installationPath = "Current App"
                menu = true
                menuGroup = "Current"
                shortcut = true
            }
            linux {
                iconFile.set(project.file("icons/current.png"))
                // Debian package names are lowercase.
                packageName = "current"
                // TODO: the owner replaces this placeholder before the first DEB is handed out.
                // An address only: jpackage writes the Maintainer field as "<vendor> <address>".
                debMaintainer = "maintainer@example.com"
                appCategory = "video"
                menuGroup = "AudioVideo;Video;Player"
                shortcut = true
            }
        }
    }
}
