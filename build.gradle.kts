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

tasks.register<JavaExec>("hexViewer") {
    description = "Opens the hex world viewer: herds and hunters walking hex to hex over the fine terrain."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.ui.HexViewerKt")
    maxHeapSize = "16g"
    args = (project.findProperty("viewerArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("hexFauna") {
    description = "Runs the hex world for some years and prints animal numbers, causes of death and the cost of a step."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.ecology.HexFaunaCheckKt")
    maxHeapSize = "12g"
    args = (project.findProperty("faunaArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
}
