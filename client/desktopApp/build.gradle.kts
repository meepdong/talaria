import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    // The Windows and Linux app: a window with the shared screens, a tray icon that
    // keeps the session while the window is closed, and start at login.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":core:security-desktop"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.jna.platform)
            // offline speech-to-text; its own JNA 5.7 resolves up to ours
            implementation(libs.vosk)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "io.github.meepdong.talaria.desktop.MainKt"

        nativeDistributions {
            // M1 ships the app folder from createDistributable as a zip; MSI and DEB
            // installers come later (MSI needs WiX).
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Talaria"
            // installers take MAJOR.MINOR.PATCH only
            packageVersion = providers.gradleProperty("talaria.version").get().substringBefore("-")
            description = "Talaria: talk to your agents"
            vendor = "meepdong"
            // The whole runtime, so OkHttp, JNA and the P-256 code find every JDK module
            // they reach for. It can be trimmed with suggestRuntimeModules later.
            includeAllModules = true
            windows {
                menuGroup = "Talaria"
                upgradeUuid = "5b0c3f0e-6a51-4f0f-9a3e-2f6f1d6b7a11"
            }
        }
    }
}
