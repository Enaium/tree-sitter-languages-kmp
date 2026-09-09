pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tree-sitter-languages-kmp"

include(":example")

listOf(
    "darwin-aarch64",
    "darwin-x86_64",
    "linux-x86_64",
    "linux-aarch64",
    "windows-x86_64"
).forEach { suffix ->
    include(":jni:$suffix")
}

file("languages").listFiles { file -> file.isDirectory }?.forEach {
    include(":languages:${it.name}")
}
