plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), using the KGP from the root build.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release version and its version code (PROCESS.md, Releases; Version.kt's versionCodeOf is the same rule).
val talariaVersion = providers.gradleProperty("talaria.version").get()
val talariaVersionCode = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$""").matchEntire(talariaVersion)
    ?.destructured?.let { (major, minor, patch, beta) ->
        major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (beta.toIntOrNull() ?: 99)
    } ?: error("talaria.version=$talariaVersion isn't X.Y.Z or X.Y.Z-beta.N")

android {
    namespace = "io.github.meepdong.talaria.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.meepdong.talaria"
        minSdk = 29
        targetSdk = 36
        versionCode = talariaVersionCode
        versionName = talariaVersion
    }

    signingConfigs {
        // A fixed debug key, so each debug build installs over the last one and keeps the pairing.
        // It's public (the repository is), so it signs debug builds only. Release builds are left
        // unsigned here: the server signs them with the release key (bridge README, App updates).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Android Studio and CI debug builds install beside the released app instead of clashing with it.
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // The shared screens and TalariaController, compiled straight into the app.
            // core/session is JVM-only for now, so :ui can't have an Android target yet;
            // the Android code reads the very same files the desktop app builds from.
            kotlin.srcDir("../ui/src/commonMain/kotlin")
            kotlin.srcDir("../ui/src/jvmMain/kotlin")
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/versions/9/previous-compilation-data.bin")
        }
    }
}

dependencies {
    implementation(project(":core:session"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(kotlin("test-junit"))
}
