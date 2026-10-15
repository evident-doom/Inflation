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
    mainClass.set("inflation.tools.HexFaunaCheckKt")
    maxHeapSize = "12g"
    args = (project.findProperty("faunaArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("hexHistory") {
    description = "Opens the graphs of a history the check wrote (pass the CSV with -PhistoryFile=...)."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.ui.HexHistoryPanelKt")
    args = listOfNotNull(project.findProperty("historyFile") as String?)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
}

tasks.register<JavaExec>("hexWaterCheck") {
    description = "Runs water and plants for some years and reports dry-season water, springs, ground water and rivers running dry (-PwaterArgs=\"--seed 1 --years 8\")."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.tools.WaterCheckKt")
    maxHeapSize = "12g"
    args = (project.findProperty("waterArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("climateCheck") {
    description = "Generates a world and summarises its climate (-PclimateArgs=\"--seed 1 --width 1000 --height 2000\")."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.tools.ClimateCheckKt")
    maxHeapSize = "12g"
    args = (project.findProperty("climateArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}
