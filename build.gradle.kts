import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform")
    jacoco
    `maven-publish`
}

val publishedProjects = allprojects
    .filter { p -> p.name.startsWith("prometheus") }
    .toSet()
val publishedKotlinSourceDirs = publishedProjects
    .flatMap { p ->
        listOf(
            "${p.projectDir}/src/commonMain/kotlin",
            "${p.projectDir}/src/jvmMain/kotlin",
            "${p.projectDir}/src/main/kotlin",
        )
    }
val publishedKotlinClassDirs = publishedProjects
    .map { p ->
        "${p.buildDir}/classes/kotlin/jvm/main"
    }

allprojects {
    group = "dev.evo.prometheus"
    version = "0.0.4-arm-fork"

    val isProjectPublished = this in publishedProjects
    if (isProjectPublished) {
        apply {
            plugin("maven-publish")
        }
        publishing {
            repositories {
                maven {
                    name = "nexusReleases"
                    credentials {
                        val nexusUser: String? by project
                        val nexusPassword: String? by project
                        username = System.getenv("NEXUS_USERNAME") ?: System.getenv("NEXUS_USER") ?: nexusUser
                        password = System.getenv("NEXUS_PASSWORD") ?: System.getenv("NEXUS_PASS") ?: nexusPassword
                    }
                    url = uri(System.getenv("NEXUS_URL") ?: "")
                }
            }
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = Versions.jvmTarget
        }
    }
    tasks.withType<JavaCompile> {
        targetCompatibility = Versions.jvmTarget
    }

    afterEvaluate {
        val coverage = tasks.register<JacocoReport>("jacocoJvmTestReport") {
            group = "Reporting"
            description = "Generate Jacoco coverage report."

            classDirectories.setFrom(publishedKotlinClassDirs)
            sourceDirectories.setFrom(publishedKotlinSourceDirs)

            executionData.setFrom(files("$buildDir/jacoco/jvmTest.exec"))
            reports {
                html.required.set(true)
                xml.required.set(true)
                csv.required.set(false)
            }
        }

        tasks.withType<Test> {
            outputs.upToDateWhen { false }

            testLogging {
                events = mutableSetOf<TestLogEvent>().apply {
                    add(TestLogEvent.FAILED)
                    if (project.hasProperty("showPassedTests")) {
                        add(TestLogEvent.PASSED)
                    }
                }
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        val jvmTestTask = tasks.findByName("jvmTest")?.apply {
            outputs.upToDateWhen { false }
            finalizedBy(coverage)
        }
        tasks.findByName("jsNodeTest")?.apply {
            outputs.upToDateWhen { false }
        }
        tasks.findByName("linuxX64Test")?.apply {
            outputs.upToDateWhen { false }
        }
        val testTask = tasks.findByName("test")?.apply {
            outputs.upToDateWhen { false }
            finalizedBy(coverage)
        }
        if (testTask != null) {
            tasks.register("allTests") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                dependsOn(testTask)
            }
            tasks.register("jvmTest") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                dependsOn(testTask)
            }
        }
    }
}

kotlin {
    configureMultiPlatform(project)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(Libs.atomicfu())
                implementation(Libs.kotlinxCoroutines("core"))
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(project(":test-util"))
                implementation(Libs.kotlinxCoroutines("test"))
            }
        }

        val jvmMain by getting {}

        val jvmTest by getting {}

        val jsMain by getting {}

        val nativeMain by getting {}

        val nativeTest by getting {}
    }
}

tasks {
    named("jvmTest") {
        outputs.upToDateWhen { false }

        dependsOn(
            ":prometheus-kt-hotspot:test",
            ":prometheus-kt-ktor:jvmTest",
            ":prometheus-kt-push:jvmTest"
        )
    }
}

extra["projectUrl"] = uri("https://github.com/anti-social/prometheus-kt")
configureMultiplatformPublishing("prometheus-kt", "Prometheus Kotlin Client")