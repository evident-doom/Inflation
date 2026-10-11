plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "inflation"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("inflation.world.GenerateWorldKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
}
