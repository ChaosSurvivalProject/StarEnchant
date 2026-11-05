plugins {
    java
}

group = "com.starinn"
version = "1.0.0"

java {
    // 目标 Java 21（Purpur 1.21.11 运行时要求 Java 21）
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // Paper 官方仓库：paper-api 与 Purpur 的 Bukkit API 层完全一致（Purpur 为 Paper 超集），
    // 本插件仅使用 Bukkit/Paper 原生 API，编译产物可直接运行于 Purpur 1.21.11。
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
    }
    processResources {
        // plugin.yml 的 ${version} 占位符在打包时替换
        filesMatching("plugin.yml") {
            expand("version" to project.version.toString())
        }
    }
    jar {
        archiveBaseName = "StarEnchant"
        // 零第三方依赖：不打包任何库，产物即纯净插件 jar
    }
}
