pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoldTaskbar"
include(":app")
include(":kit")
project(":kit").projectDir = file("kit/kit")
