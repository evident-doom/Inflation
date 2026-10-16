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
    // The code compiles clean; keep it so.
    compilerOptions { allWarningsAsErrors.set(true) }
}

application {
    mainClass.set("inflation.tools.GenerateWorldKt")
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

tasks.register<JavaExec>("testWorld") {
    description = "Settled test worlds for short runs: list, build NAME... [--years N], compare runs on them with the full map (-PtestArgs=\"list\"); a test then runs as hexFauna --world NAME."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.tools.TestWorldsKt")
    maxHeapSize = "12g"
    args = (project.findProperty("testArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("pollinationProbe") {
    description = "Runs the hex world and prints, month by month, how well the fruit-bearing plants are pollinated, north to south."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.tools.PollinationProbeKt")
    maxHeapSize = "12g"
    args = (project.findProperty("probeArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("hexHistory") {
    description = "Opens the graphs of a history the check wrote (pass the CSV with -PhistoryFile=...)."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.ui.HexHistoryPanelKt")
    args = listOfNotNull(project.findProperty("historyFile") as String?)
}

// Run settings come from inflation.properties in the project directory, or from the file
// -Dhex.config=FILE names (see config.example.properties); every task is given that file.
tasks.withType<JavaExec>().configureEach {
    System.getProperty("hex.config")?.let { systemProperty("hex.config", file(it).absolutePath) }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("hex.config", "none") // (tests run on the defaults, whatever inflation.properties says)
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

tasks.register<JavaExec>("insectSeasons") {
    description = "Probe: insects and small life month by month, by climate class, over the last years of a run."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.tools.InsectSeasonsKt")
    maxHeapSize = "12g"
    args = (project.findProperty("insectArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("herpCheck") {
    description = "Probe: reptiles and amphibians by climate class, the terms of their rule against how they changed."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.tools.HerpCheckKt")
    maxHeapSize = "12g"
    args = (project.findProperty("herpArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<JavaExec>("residentHunger") {
    description = "Check: when and where resident kinds (hippo by default) go hungry, by water within reach, green and density."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.tools.ResidentHungerCheckKt")
    maxHeapSize = "12g"
    args = (project.findProperty("hungerArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}
