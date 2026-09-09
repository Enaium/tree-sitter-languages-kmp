package cn.enaium.treesitter.languages.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension

/**
 * Convention for the per-platform JNI artifact modules (`jni/<os>-<arch>`).
 *
 * Each module packages the JNI shared libraries produced by the language
 * modules' `buildJni` task (cmake host build) for its own platform into a
 * single jar at `lib/<os>/<arch>/`, and publishes it as
 * `cn.enaium.treesitter:treesitter-languages-jni-<suffix>`.
 */
class JniPlatformPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val suffix = project.name
        val (os, arch) = when (suffix) {
            "darwin-aarch64" -> "macos" to "aarch64"
            "darwin-x86_64" -> "macos" to "x64"
            "linux-x86_64" -> "linux" to "x64"
            "linux-aarch64" -> "linux" to "aarch64"
            "windows-x86_64" -> "windows" to "x64"
            else -> error("Unknown JNI platform suffix: $suffix")
        }

        project.plugins.apply("java-library")
        project.plugins.apply("maven-publish")

        project.group = "cn.enaium.treesitter"
        project.version = "0.25.1"

        val langModules = project.rootProject.projectDir.resolve("languages")
            .listFiles { f -> f.isDirectory }!!
            .map { it.name }

        val jniJar = project.tasks.register("jniJar", Jar::class.java)
        jniJar.configure {
            group = "build"
            description = "Package JNI libraries for $os/$arch"
            archiveFileName.set("treesitter-languages-jni-$suffix.jar")
            destinationDirectory.set(project.layout.buildDirectory.dir("libs"))
            // Path layout matches the generated binding's libPath():
            // classpath resource "/lib/<os>/<arch>/libktreesitter-<lang>.<ext>".
            langModules.forEach { lang ->
                val libDir = project.rootProject.projectDir
                    .resolve("languages/$lang/build/jni-libs/lib/$os/$arch")
                from(libDir) {
                    into("lib/$os/$arch")
                }
            }
        }

        val publishing =
            project.extensions.getByType(PublishingExtension::class.java)
        publishing.publications.create(
            "jni",
            MavenPublication::class.java
        ) {
            artifactId = "treesitter-languages-jni-$suffix"
            artifact(jniJar)
        }
    }
}
