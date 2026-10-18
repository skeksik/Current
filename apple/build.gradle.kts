plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Umbrella framework for the SwiftUI apps (iOS, macOS, tvOS). No Compose in here (docs/decisions/0006).
kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        macosArm64(),
        tvosArm64(),
        tvosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            export(project(":core:model"))
            export(project(":core:network"))
            export(project(":core:database"))
            export(project(":core:playback"))
            export(project(":data:jellyfin"))
            export(project(":data:seerr"))
            export(project(":data:stash"))
            export(project(":feature:home"))
            export(project(":feature:library"))
            export(project(":feature:requests"))
            export(project(":feature:player"))
            export(project(":feature:settings"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:network"))
            api(project(":core:database"))
            api(project(":core:playback"))
            api(project(":data:jellyfin"))
            api(project(":data:seerr"))
            api(project(":data:stash"))
            api(project(":feature:home"))
            api(project(":feature:library"))
            api(project(":feature:requests"))
            api(project(":feature:player"))
            api(project(":feature:settings"))
        }
    }
}
