import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// All language modules from the sibling `languages` directory.
val languageModules = file("../languages").listFiles { f -> f.isDirectory }!!
    .map { it.name }
    .sorted()

kotlin {
    jvm("desktop")

    androidLibrary {
        namespace = "cn.enaium.treesitter.languages.example"
        compileSdk = (property("sdk.version.compile") as String).toInt()
        minSdk = (property("sdk.version.min") as String).toInt()
        buildToolsVersion = "36.0.0"
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    jvmToolchain(17)

    sourceSets {
        commonMain {
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            languageSettings {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }

            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
            }
        }



        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                languageModules.forEach {
                    implementation(project(":languages:$it"))
                }
                implementation(libs.ktreesitter)
            }
        }

        getByName("androidMain") {
            dependencies {
                languageModules.forEach {
                    implementation(project(":languages:$it"))
                }
                implementation(libs.ktreesitter)
                implementation(libs.androidx.activity.compose)
            }
        }

        getByName("nativeMain") {
            dependencies {
                languageModules.forEach {
                    implementation(project(":languages:$it"))
                }
                implementation(libs.ktreesitter)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "cn.enaium.treesitter.languages.example.MainKt"
    }
}
