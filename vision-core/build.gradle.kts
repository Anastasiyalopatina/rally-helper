plugins {
    kotlin("jvm")
}

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        kotlin.exclude("radar/vision/cli/**")
    }
    create("cli") {
        kotlin.srcDir("src/main/kotlin")
        kotlin.include("radar/vision/cli/**")
        compileClasspath += main.get().output
        runtimeClasspath += main.get().output + compileClasspath
    }
}

dependencies {
    add("cliImplementation", "org.json:json:20240303")
    testImplementation(kotlin("test-junit"))
}

fun registerDatasetVerification(name: String, mode: String, descriptionText: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = descriptionText
    dependsOn("cliClasses")
    classpath = sourceSets["cli"].runtimeClasspath
    mainClass.set("radar.vision.cli.VerifyFramesKt")
    args(mode, rootProject.layout.projectDirectory.dir("vision-core/testdata").asFile.absolutePath)
}

registerDatasetVerification("verifyCalibration", "calibration", "Runs the detector over calibration smoke frames.")
registerDatasetVerification("verifyHoldout", "holdout", "Evaluates only independent holdout frames.")
registerDatasetVerification("verifySequences", "sequences", "Replays recorded frame sequences.")
registerDatasetVerification("verifyMutations", "mutations", "Runs labelled synthetic robustness checks.")
registerDatasetVerification("verifyRuntimeTemplates", "runtime-asset", "Checks the compact runtime template asset.")

tasks.register<JavaExec>("exportAnnotations") {
    group = "verification"
    description = "Writes calibration frames with detector geometry into build/reports."
    dependsOn("cliClasses")
    classpath = sourceSets["cli"].runtimeClasspath
    mainClass.set("radar.vision.cli.AnnotateFramesKt")
    args(
        rootProject.layout.projectDirectory.dir("vision-core/testdata").asFile.absolutePath,
        layout.buildDirectory.dir("reports/vision/annotated").get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("verifyCoreLogic") {
    group = "verification"
    description = "Checks tracker stabilization and hardened state-machine invariants."
    dependsOn("cliClasses")
    classpath = sourceSets["cli"].runtimeClasspath
    mainClass.set("radar.vision.cli.CoreLogicChecksKt")
}

tasks.register<JavaExec>("inspectFrame") {
    group = "verification"
    description = "Inspects one local frame without retaining it in build outputs. Use -PframePath=/path/image.png."
    dependsOn("cliClasses")
    classpath = sourceSets["cli"].runtimeClasspath
    mainClass.set("radar.vision.cli.InspectFrameKt")
    val framePath = providers.gradleProperty("framePath")
    doFirst { require(framePath.isPresent) { "-PframePath is required" } }
    args(
        framePath.orElse("").get(),
        rootProject.layout.projectDirectory.file("app/src/main/assets/detector_templates.bin").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("replayCapturedScenarios") {
    group = "verification"
    description = "Replays private Capture Lab scenario ZIPs with manifest time and separate ground truth."
    dependsOn("cliClasses")
    classpath = sourceSets["cli"].runtimeClasspath
    mainClass.set("radar.vision.cli.CaptureLabReplayRunnerKt")
    val captureBundle = providers.gradleProperty("captureBundle")
    doFirst { require(captureBundle.isPresent) { "-PcaptureBundle=/path/to/export-or-scenario.zip is required" } }
    args(
        captureBundle.orElse("").get(),
        rootProject.layout.projectDirectory.file("app/src/main/assets/detector_templates.bin").asFile.absolutePath,
        layout.buildDirectory.dir("reports/replay").get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("verifyReplayInfrastructure") {
    group = "verification"
    description = "Exercises the deterministic replay runner with generated neutral frames."
    dependsOn("cliClasses")
    classpath = sourceSets["cli"].runtimeClasspath
    mainClass.set("radar.vision.cli.CaptureLabReplaySelfCheckKt")
    args(
        layout.buildDirectory.dir("tmp/replay-self-check").get().asFile.absolutePath,
        rootProject.layout.projectDirectory.file("app/src/main/assets/detector_templates.bin").asFile.absolutePath,
        layout.buildDirectory.dir("reports/replay-self-check").get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("compileRuntimeTemplates") {
    group = "build setup"
    description = "Compiles private calibration frames into a compact runtime feature asset."
    dependsOn("cliClasses")
    classpath = sourceSets["cli"].runtimeClasspath
    mainClass.set("radar.vision.cli.CompileRuntimeTemplatesKt")
    args(
        rootProject.layout.projectDirectory.dir("vision-core/testdata/calibration/frames").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("app/src/main/assets/detector_templates.bin").asFile.absolutePath,
    )
}

tasks.register("verifyFrames") {
    group = "verification"
    description = "Compatibility alias for verifyCalibration."
    dependsOn("verifyCalibration")
}
