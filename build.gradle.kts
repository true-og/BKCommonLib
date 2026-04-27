import groovy.json.JsonSlurper
import java.util.zip.ZipFile

plugins {
    eclipse
    id("java-library")
    id("com.bergerkiller.mountiplex") version "2.93"
    id("com.gradleup.shadow") version "8.3.9"
    id("maven-publish")
}

val buildNumber = System.getenv("BUILD_NUMBER") ?: "NO-CI"
val serverDependencyVersion = libs.versions.spigot.get()
val localPurpurServerJar = file(
    "${System.getProperty("user.home")}/.m2/repository/org/purpurmc/purpur/purpur-server/" +
            "$serverDependencyVersion/purpur-server-$serverDependencyVersion-mojang-mapped.jar"
)
val localPurpurApiJar = file(
    "${System.getProperty("user.home")}/.m2/repository/org/purpurmc/purpur/purpur-api/" +
            "$serverDependencyVersion/purpur-api-$serverDependencyVersion.jar"
)
val localPurpurDevBundleZip = file(
    "${System.getProperty("user.home")}/.m2/repository/org/purpurmc/purpur/dev-bundle/" +
            "$serverDependencyVersion/dev-bundle-$serverDependencyVersion.zip"
)
val localBungeeCordChatJar = file(
    "${System.getProperty("user.home")}/.m2/repository/net/md-5/bungeecord-chat/" +
            "1.16-R0.4/bungeecord-chat-1.16-R0.4.jar"
)

fun readPurpurDevBundleDependencies(section: String): List<String> {
    if (!localPurpurDevBundleZip.isFile) {
        return emptyList()
    }

    return ZipFile(localPurpurDevBundleZip).use { zip ->
        val configEntry = zip.getEntry("config.json") ?: return@use emptyList()
        val config = zip.getInputStream(configEntry).reader().use { reader ->
            @Suppress("UNCHECKED_CAST")
            JsonSlurper().parse(reader) as Map<String, Any?>
        }
        @Suppress("UNCHECKED_CAST")
        val buildData = config["buildData"] as? Map<String, Any?> ?: return@use emptyList()
        @Suppress("UNCHECKED_CAST")
        (buildData[section] as? List<String>).orEmpty()
    }
}

val purpurRuntimeDependencies = readPurpurDevBundleDependencies("runtimeDependencies").filterNot {
    it.startsWith("org.purpurmc.purpur:")
}

fun localMavenArtifactJar(notation: String): File? {
    val parts = notation.split(':')
    if (parts.size < 3) {
        return null
    }

    val groupPath = parts[0].replace('.', '/')
    val artifact = parts[1]
    val version = parts[2]
    val classifier = if (parts.size >= 4) parts[3] else null
    val jarName = buildString {
        append(artifact).append('-').append(version)
        if (classifier != null) {
            append('-').append(classifier)
        }
        append(".jar")
    }

    val jarFile = file(
        "${System.getProperty("user.home")}/.m2/repository/$groupPath/$artifact/$version/$jarName"
    )
    if (jarFile.isFile) {
        return jarFile
    }

    fun versionPrefix(version: String): String {
        val major = version.takeWhile { it.isDigit() }
        return if (major.isNotEmpty()) "$major." else version
    }

    // Fall back to the newest locally cached version for this artifact when the exact
    // bundle coordinate is unavailable in ~/.m2, but keep it in the same major line.
    // This avoids mixing 1.19.4 server jars with 1.20+ Mojang library ABIs.
    val artifactDir = file("${System.getProperty("user.home")}/.m2/repository/$groupPath/$artifact")
    val preferredCandidates = artifactDir.listFiles()
        ?.filter { it.isDirectory }
        ?.filter { it.name == version || it.name.startsWith(versionPrefix(version)) }
        ?.sortedByDescending { it.name }
        .orEmpty()
    val fallbackCandidates = artifactDir.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        .orEmpty()
    for (candidate in preferredCandidates + fallbackCandidates) {
        val candidateJar = file(
            "${candidate.absolutePath}/$artifact-${candidate.name}" +
                    (if (classifier != null) "-$classifier" else "") +
                    ".jar"
        )
        if (candidateJar.isFile) {
            return candidateJar
        }
    }

    return null
}

val localPurpurRuntimeDependencyFiles = purpurRuntimeDependencies.mapNotNull(::localMavenArtifactJar)

group = "com.bergerkiller.bukkit"
version = "1.19.4-v2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenLocal {
        // Used to access a server JAR for testing
        // TODO Use Paperclip instead
        content {
            includeGroup("org.spigotmc")
            includeGroup("com.mojang")
            includeGroup("org.purpurmc.purpur")
        }
    }
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://hub.spigotmc.org/nexus/content/groups/public/")
    maven("https://jitpack.io")

    // Repo for TeamBergerhealer plugins, modules and several of its (soft) dependencies. Also used for:
    // - Comphenix ProtocolLib
    // - Aikar minecraft-timings
    // - Myles ViaVersion
    maven("https://ci.mg-dev.eu/plugin/repository/everything/")
}

// Configuration for shaded dependencies which should not be added to the published Maven .pom
val internal = configurations.create("internal")
configurations {
    compileOnly {
        extendsFrom(internal)
    }
}

dependencies {
    //
    // Server dependencies
    //

    // Spigot API includes the Bukkit API and is what plugins generally use
    compileOnly(libs.spigot.api)
    // We also depend on netty for the network logic, which is available in public repo
    compileOnly(libs.netty.all)
    // Log4j that is used inside the server
    compileOnly(libs.log4j.api)
    compileOnly(libs.log4j.core)

    //
    // Dependencies shaded into the library for internal use
    //

    // Mountiplex is included in BKCommonLib at the same package
    api(libs.mountiplex)
    // Region change tracker is included in BKCommonLib for the region block change event
    api(libs.regionchangetracker)
    // Aikar's minecraft timings library, https://github.com/aikar/minecraft-timings
    internal(libs.timings) {
        isTransitive = false
    }
    // GSON isn't available in spigot versions prior to 1.8.1, shade it in order to keep 1.8 compatibility
    internal(libs.gson)

    //
    // Optional provided dependencies that BKCommonLib can talk with
    //

    // ViaVersion API
    compileOnly(libs.viaversion)
    // ProtocolLib hook for protocol handling
    compileOnly(libs.protocollib)

    //
    // Cloud command framework
    // Is relocated - requires appropriate relocation in plugins using it
    //
    internal(libs.cloud.paper)
    internal(libs.cloud.annotations)
    internal(libs.cloud.minecraft.extras)
    internal(libs.commodore) {
        isTransitive = false
    }
    internal(libs.adventure.api)
    internal(libs.adventure.platform.bukkit)

    //
    // Test dependencies
    //

    testImplementation(files(localPurpurApiJar))
    testImplementation(libs.netty.all)
    testImplementation(libs.log4j.api)
    testImplementation(libs.log4j.core)
    testImplementation("com.google.guava:guava:31.1-jre")
    testImplementation("org.yaml:snakeyaml:1.33")
    testImplementation(files(localBungeeCordChatJar))
    testImplementation(libs.adventure.api)
    testRuntimeOnly("net.fabricmc:mapping-io:0.4.1")
    testRuntimeOnly("com.github.Carleslc.Simple-YAML:Simple-Yaml:1.8.4")
    testRuntimeOnly("com.github.Carleslc.Simple-YAML:Simple-Configuration:1.8.4")
    testRuntimeOnly("net.kyori:adventure-key:4.13.1")
    testRuntimeOnly("net.kyori:adventure-text-logger-slf4j:4.13.1")
    testRuntimeOnly("net.kyori:adventure-text-minimessage:4.13.1")
    testRuntimeOnly("net.kyori:adventure-text-serializer-gson:4.13.1")
    testRuntimeOnly("net.kyori:adventure-text-serializer-legacy:4.13.1")
    testRuntimeOnly("net.kyori:adventure-text-serializer-plain:4.13.1")
    testRuntimeOnly("com.github.Carleslc.Simple-YAML:Simple-Yaml:1.8.4")
    testRuntimeOnly("com.github.Carleslc.Simple-YAML:Simple-Configuration:1.8.4")
    if (localPurpurServerJar.isFile) {
        testImplementation(files(localPurpurServerJar))
    }
    testRuntimeOnly(files(localPurpurRuntimeDependencyFiles))
    testImplementation(libs.mockito.core)
    testImplementation(libs.junit)
}

publishing {
    repositories {
        maven("https://ci.mg-dev.eu/plugin/repository/everything") {
            name = "MGDev"
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

mountiplex {
    generateTemplateHandles()
    remapAnnotationStrings()
}

tasks {
    named<Jar>("jar") {
        enabled = false
    }

    generateTemplateHandles {
        source.set("com/bergerkiller/templates/init.txt")
        target.set("com/bergerkiller/generated")
        variables.put("version", libs.versions.minecraft)
    }

    assemble {
        dependsOn(shadowJar)
    }

    withType<JavaCompile>().configureEach {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    javadoc {
        options.encoding = "UTF-8"
        isFailOnError = true
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }

    processResources {
        from("src/main/templates")
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand(
                "version" to version,
                "build" to buildNumber,
                "url" to "https://github.com/bergerhealer/BKCommonLib",
                "authors" to "bergerkiller, lenis0012, timstans, bubba1234119, KamikazePlatypus, mg_1999, Friwi"
            )
        }
    }

    shadowJar {
        val prefix = "com.bergerkiller.bukkit.common.dep"
        relocate("co.aikar.timings.lib", "$prefix.timingslib")
        relocate("com.google.gson", "$prefix.gson")

        // Cloud command framework and its dependencies
        relocate("cloud.commandframework", "$prefix.cloud")
        relocate("io.leangen.geantyref", "$prefix.typetoken")
        relocate("me.lucko.commodore", "$prefix.me.lucko.commodore")
        relocate("net.kyori", "$prefix.net.kyori")

        // Mountiplex and its dependencies
        val mountiplexPrefix = "com.bergerkiller.mountiplex.dep"
        relocate("org.objectweb.asm", "$mountiplexPrefix.org.objectweb.asm")
        relocate("org.objenesis", "$mountiplexPrefix.org.objenesis")
        relocate("javassist", "$mountiplexPrefix.javassist")

        configurations.add(internal)

        dependencies {
            exclude(dependency("org.apiguardian:apiguardian-api"))
            exclude(dependency("org.checkerframework:checker-qual"))
        }

        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        archiveBaseName.set(project.name)
        archiveVersion.set(project.version.toString())
        archiveClassifier.set("")

        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
