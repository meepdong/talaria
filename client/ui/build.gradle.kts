import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    // The Connect, Confirm code and Status screens (UI.md §1–2), shared by the desktop
    // app now and the Android app in step 5. The screens in commonMain only see the
    // plain models in Model.kt; jvmMain turns the session's state into those models.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
        jvmMain.dependencies {
            api(project(":core:session"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}

// Version.kt must say the version gradle.properties does (PROCESS.md, Releases).
val talariaVersion = providers.gradleProperty("talaria.version").get()
val versionFile = providers.fileContents(layout.projectDirectory.file(
    "src/commonMain/kotlin/io/github/meepdong/talaria/ui/Version.kt")).asText.get()
check("const val TALARIA_VERSION = \"$talariaVersion\"" in versionFile) {
    "Version.kt doesn't say talaria.version=$talariaVersion from gradle.properties"
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
