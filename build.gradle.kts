plugins {
    java
}

group = "dev.ash"
version = "1.0.2"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.geysermc.geyser:api:2.9.4-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.register<JavaExec>("exportHostedPacks") {
    group = "distribution"
    description = "Exports deterministic hosted Java and Bedrock packs for GitHub hosting."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.ash.lowonfire.HostedPackExporter")
    args(layout.projectDirectory.dir("hosted").asFile.absolutePath)
}
