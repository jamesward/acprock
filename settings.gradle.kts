pluginManagement {
    repositories {
        // Prefer Maven Central for plugins so the build is resilient to
        // plugin-portal CDN flakiness. Portal stays as a fallback for the
        // few plugins whose marker is not on Maven Central (currently:
        // io.ktor.plugin, jib).
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "acprock"

include("app")

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}
