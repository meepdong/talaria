import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // The connection to the bridge, shared by the Android and desktop apps. It uses
    // java.security and OkHttp, so for now it lives in the JVM source set.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        jvmMain.dependencies {
            api(project(":core:security"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // The integration tests start the real Python bridge (see bridge_harness.py).
    // CI sets TALARIA_PYTHON so they can't be skipped there.
    val python = providers.environmentVariable("TALARIA_PYTHON").orNull
    inputs.property("talariaPython", python ?: "")
    python?.let { environment("TALARIA_PYTHON", it) }
    inputs.dir(rootDir.resolve("../bridge/src")).withPropertyName("bridgeSources")
    environment("TALARIA_BRIDGE_SRC", rootDir.resolve("../bridge/src").absolutePath)
}
