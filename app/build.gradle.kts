import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.nio.file.LinkOption
import kotlin.io.path.createLinkPointingTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("io.ktor.plugin")
    id("com.google.cloud.tools.jib")
    id("org.graalvm.buildtools.native")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.jamesward.acprock.CliKt"
}

val ktorVersion = "3.4.2"
val acpSdkVersion = "0.18.1"

dependencies {
    implementation("com.agentclientprotocol:acp:$acpSdkVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("com.openai:openai-java:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

val generateVersionProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version")
    val projectVersion = project.version.toString()
    outputs.dir(outputDir)
    inputs.property("version", projectVersion)
    doLast {
        val propsFile = outputDir.get().file("version.properties").asFile
        propsFile.parentFile.mkdirs()
        propsFile.writeText("version=$projectVersion\n")
    }
}

sourceSets.main {
    resources.srcDir(generateVersionProperties.map { it.outputs.files.singleFile })
}

tasks.test {
    exclude("**/*IntegrationTest*")
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests that require a real local kiro-cli (or other ACP agent)"
    group = "verification"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    include("**/*IntegrationTest*")
    systemProperty("acprock.harness.cmd", System.getProperty("acprock.harness.cmd") ?: "kiro-cli")
    testLogging {
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
        events(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "acprock"
            mainClass = "com.jamesward.acprock.CliKt"
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "-Os",
                "--gc=epsilon",
                "-H:+UnlockExperimentalVMOptions",
                "-H:-IncludeMethodData",
                "--exclude-config", "kotlin-reflect-2\\..*\\.jar", "META-INF/native-image/.*",
            )
            if (!org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
                buildArgs.add("-H:+StripDebugInfo")
            }
            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(21)
                vendor = JvmVendorSpec.GRAAL_VM
            }
        }
    }
    metadataRepository {
        enabled = true
    }
    toolchainDetection = true
}

// fix for: https://github.com/gradle/gradle/issues/28583
fun fixSymlink(target: java.nio.file.Path, expectedSrc: java.nio.file.Path) {
    if (!expectedSrc.isRegularFile(LinkOption.NOFOLLOW_LINKS)) return
    if (!target.isRegularFile(LinkOption.NOFOLLOW_LINKS) || target.fileSize() > 0) return
    logger.warn("fixSymlink: {} -> {}", target, expectedSrc)
    target.deleteExisting()
    target.createLinkPointingTo(expectedSrc)
}

tasks.named("nativeCompile") {
    doFirst {
        val mainCompileOpt = project.extensions.getByType(GraalVMExtension::class).binaries["main"].asCompileOptions()
        val binPath = mainCompileOpt.javaLauncher.get().executablePath.asFile.toPath().parent
        val svmBinPath = binPath.resolve("../lib/svm/bin")
        fixSymlink(binPath.resolve("native-image"), svmBinPath.resolve("native-image"))
    }
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
            platform {
                architecture = "arm64"
                os = "linux"
            }
        }
    }
    to {
        image = project.findProperty("jib.to.image")?.toString() ?: "acprock"
        val cleanVersion = project.version.toString().removePrefix("v").removeSuffix(".dirty")
        tags = (project.findProperty("jib.to.tags")?.toString() ?: "$cleanVersion,latest").split(",").toSet()
    }
    container {
        mainClass = "com.jamesward.acprock.CliKt"
        ports = listOf("9999")
        args = emptyList()
    }
}
