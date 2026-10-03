rootProject.name = "talaria-client"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core:protocol")
include(":core:security")
include(":core:security-desktop")
