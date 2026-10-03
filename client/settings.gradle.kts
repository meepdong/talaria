rootProject.name = "talaria-client"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform pulls a few androidx libraries that only Google publishes.
        google()
    }
}

include(":core:protocol")
include(":core:security")
include(":core:security-desktop")
include(":core:session")
include(":ui")
include(":desktopApp")
include(":androidApp")
