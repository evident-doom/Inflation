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

/**
 * Probes: programs that answered one question about the model (src/probes), kept runnable with runTool and
 * their own tasks but apart from the model and the maintained tools. They see the model's internals.
 */
val probes: SourceSet = sourceSets.create("probes") {
    compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
    runtimeClasspath += sourceSets["main"].output + sourceSets["main"].runtimeClasspath
}

kotlin {
    jvmToolchain(21)
    target.compilations.getByName("probes").associateWith(target.compilations.getByName("main"))
    // The code compiles clean; keep it so.
    compilerOptions { allWarningsAsErrors.set(true); freeCompilerArgs.addAll("-Wextra", "-Xreturn-value-checker=full") }
}

application {
    mainClass.set("inflation.tools.GenerateWorldKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

/**
 * A task that runs one of the tools: [main] with the arguments given in -P[argsProperty]="..." (split at spaces),
 * on [heap] of memory (null: the JVM's default).
 */
fun tool(name: String, main: String, argsProperty: String, heap: String? = "12g", description: String) =
    tasks.register<JavaExec>(name) {
        this.description = description
        group = "application"
        classpath = probes.runtimeClasspath
        mainClass.set(main)
        heap?.let { maxHeapSize = it }
        args = (project.findProperty(argsProperty) as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    }

tool("hexViewer", "inflation.ui.HexViewerKt", "viewerArgs", heap = "16g",
    description = "Opens the hex world viewer: herds and hunters walking hex to hex over the fine terrain.")

tool("hexFauna", "inflation.tools.HexFaunaCheckKt", "faunaArgs",
    description = "Runs the hex world for some years and prints animal numbers, causes of death and the cost of a step.")

tool("testWorld", "inflation.tools.TestWorldsKt", "testArgs",
    description = "Settled test worlds for short runs: list, build NAME... [--years N], compare runs on them with the full map (-PtestArgs=\"list\"); a test then runs as hexFauna --world NAME.")

tool("pollinationProbe", "inflation.tools.PollinationProbeKt", "probeArgs",
    description = "Runs the hex world and prints, month by month, how well the fruit-bearing plants are pollinated, north to south.")

tasks.register<JavaExec>("hexHistory") {
    description = "Opens the graphs of a history the check wrote (pass the CSV with -PhistoryFile=...)."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("inflation.ui.HexHistoryPanelKt")
    args = listOfNotNull(project.findProperty("historyFile") as String?)
}

tool("runTool", "", "toolArgs", description = "Runs any tool by its main class: -Pmain=inflation.tools.WindReportKt -PtoolArgs=\"--seed 1\".").configure {
    mainClass.set(project.findProperty("main") as String? ?: "")
}

// Run settings come from inflation.properties in the project directory, or from the file
// -Dhex.config=FILE names (see config.example.properties); every task is given that file.
tasks.withType<JavaExec>().configureEach {
    System.getProperty("hex.config")?.let { systemProperty("hex.config", file(it).absolutePath) }
}

// (build compiles the probes too, so a change to the model cannot leave them broken.)
tasks.named("check") { dependsOn(probes.classesTaskName) }

tasks.test {
    useJUnitPlatform()
    systemProperty("hex.config", "none") // (tests run on the defaults, whatever inflation.properties says)
    maxHeapSize = "4g"
}

tool("hexWaterCheck", "inflation.tools.WaterCheckKt", "waterArgs",
    description = "Runs water and plants for some years and reports dry-season water, springs, ground water and rivers running dry (-PwaterArgs=\"--seed 1 --years 8\").")

tool("climateCheck", "inflation.tools.ClimateCheckKt", "climateArgs",
    description = "Generates a world and summarises its climate (-PclimateArgs=\"--seed 1 --width 1000 --height 2000\").")

tool("insectSeasons", "inflation.tools.InsectSeasonsKt", "insectArgs",
    description = "Probe: insects and small life month by month, by climate class, over the last years of a run.")

tool("herpCheck", "inflation.tools.HerpCheckKt", "herpArgs",
    description = "Probe: reptiles and amphibians by climate class, the terms of their rule against how they changed.")

tool("residentHunger", "inflation.tools.ResidentHungerCheckKt", "hungerArgs",
    description = "Check: when and where resident kinds (hippo by default) go hungry, by water within reach, green and density.")

tool("floodplainCheck", "inflation.tools.FloodplainCheckKt", "floodArgs",
    description = "Measures the river plains world generation makes and, with --years, whether they flood and stay moist (-PfloodArgs=\"--seed 1 --years 2\").")

tool("hexPpo", "inflation.learning.HexPpo", "ppoArgs", heap = null,
    description = "The trainer: learns the move networks and bands' ways by PPO (-PppoArgs=\"--out DIR --size KM --rounds N\"; settings are ppo.* config keys).")
