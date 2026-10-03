import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Android and the desktop app both consume the JVM target: TNP only needs
    // java.security (SHA-256, ECDSA P-256), which Android provides too. iOS gets
    // its own target and crypto actuals after v1.
    //
    // Bytecode for Java 17, so any JDK from 17 up builds it, including Android Studio's.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest {
            resources.srcDir(layout.buildDirectory.dir("generated/spec"))
        }
    }
}

// The shared test vectors live in ../spec at the repository root. Copy them into the
// test resources so every implementation is checked against the same files.
val copySpec = tasks.register<Sync>("copySpec") {
    from(rootDir.resolve("../spec")) {
        include("vectors/*.json", "sas-emoji.json")
    }
    into(layout.buildDirectory.dir("generated/spec/spec"))
}

tasks.named("jvmTestProcessResources") {
    dependsOn(copySpec)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
