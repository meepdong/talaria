plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), using the KGP from the root build.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.meepdong.talaria.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.meepdong.talaria"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // A fixed debug key, so each CI build installs over the last one and keeps the
        // pairing. It signs debug builds only; release signing comes with the F-Droid work.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
