import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("fabric-loom") version "1.17-SNAPSHOT" apply false
}

// Shared setup for every addon sub-project (currently: blood).
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "fabric-loom")

    group = property("maven_group") as String

    val javaTarget = 21

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    dependencies {
        "minecraft"("com.mojang:minecraft:${property("minecraft_version")}")
        "mappings"("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
        "modImplementation"("net.fabricmc:fabric-loader:${property("loader_version")}")
        "modImplementation"("net.fabricmc:fabric-language-kotlin:${property("kotlin_loader_version")}")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
        // The HHA base mod - addons compile against it and it is loaded in dev runs.
        "modImplementation"(files(rootProject.file(property("hha_jar") as String)))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaTarget)
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaTarget.toString()))
    }

    tasks.withType<ProcessResources>().configureEach {
        inputs.property("version", project.version)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand(
                mapOf(
                    "version" to project.version.toString(),
                    "minecraft_version" to project.property("minecraft_version").toString(),
                    "loader_version" to project.property("loader_version").toString(),
                    "kotlin_loader_version" to project.property("kotlin_loader_version").toString(),
                ),
            )
        }
    }
}
