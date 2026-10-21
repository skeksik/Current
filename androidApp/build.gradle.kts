import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":core:network"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

// The one version source (gradle.properties), also used by AppInfo and the desktop installers. versionCode must grow with every
// release: MAJOR*10000 + MINOR*100 + PATCH, so MINOR and PATCH stay below 100.
val appVersion = providers.gradleProperty("current.version").get()
val appVersionCode = appVersion.split(".").map(String::toInt).let { parts ->
    require(parts.size == 3 && parts.drop(1).all { it in 0..99 }) { "current.version must be MAJOR.MINOR.PATCH with MINOR, PATCH < 100" }
    parts[0] * 10000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "com.skeksik.current"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.skeksik.current"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}