import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Windows and Linux device key storage for the desktop app. JNA is only used to
    // call Windows DPAPI (Crypt32); nothing here is loaded on Android.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":core:security"))
            implementation(libs.jna.platform)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
