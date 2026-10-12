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

tasks.register<JavaExec>("viewer") {
    description = "Opens the world viewer with time controls."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.ui.ViewerKt")
    maxHeapSize = "16g" // the full 1000 x 2000 km map needs about 10 GB
    args = (project.findProperty("viewerArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
}

tasks.register<JavaExec>("fauna") {
    description = "Runs the living world for some years and prints animal populations."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.ecology.FaunaReportKt")
    maxHeapSize = "6g"
    args = (project.findProperty("faunaArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("imitate") {
    description = "Trains one network per species to copy the hand-written baseline behaviour."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.learning.ImitateKt")
    maxHeapSize = "8g"
    args = (project.findProperty("imitateArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("audit") {
    description = "Runs the living world and reports which species found a niche, which died out and which grow without limit."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.ecology.NicheAuditKt")
    maxHeapSize = "12g"
    args = (project.findProperty("auditArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}
