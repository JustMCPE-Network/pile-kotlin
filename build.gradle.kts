plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "net.justmcpe.pile"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configurations.all {
        resolutionStrategy.capabilitiesResolution.withCapability("org.lz4", "lz4-java") {
            selectHighestVersion()
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        explicitApi()
        compilerOptions {
            allWarningsAsErrors.set(true)
        }
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "2g"
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
