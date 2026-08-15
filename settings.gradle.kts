// 多版本（Stonecutter）项目配置
// 支持版本：1.20.1（Forge）、1.21.1（NeoForge）
// 切换版本：./gradlew chiseledSwitch --chiseledVersion=1.20.1（或 IDE 内 Stonecutter 工具）
pluginManagement {
    repositories {
        maven("https://maven.kikugie.dev/releases") {
            name = "KikuGie Releases"
        }
        maven("https://maven.kikugie.dev/snapshots") {
            name = "KikuGie Snapshots"
        }
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net/") {
            name = "MinecraftForge"
        }
        maven("https://maven.neoforged.net/releases") {
            name = "NeoForged"
        }
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.8.3"
}

rootProject.name = rootDir.name

stonecutter {
    kotlinController = true
    centralScript = "build.gradle"

    create(rootProject) {
        versions("1.20.1", "1.21.1")
        vcsVersion = "1.21.1"
    }
}
