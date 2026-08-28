plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

allprojects {
    group = "cn.enaium.treesitter"
    version = property("project.version") as String

    repositories {
        mavenCentral()
        google()
    }
}
