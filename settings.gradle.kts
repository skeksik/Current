rootProject.name = "Current"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")
include(":webApp")

include(":composeUi")

include(":core:model")
include(":core:network")
include(":core:database")
include(":core:designsystem")
include(":core:playback")

include(":data:jellyfin")
include(":data:seerr")
include(":data:stash")

include(":feature:home")
include(":feature:library")
include(":feature:requests")
include(":feature:player")
include(":feature:settings")

include(":apple")

// Spike prototypes (spike/web-ui), not used by the app; removed before merging
include(":spikes:web-core")
include(":spikes:web-html")
include(":spikes:web-ts")
include(":spikes:web-wasm")
