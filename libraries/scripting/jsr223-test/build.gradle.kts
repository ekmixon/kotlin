
plugins {
    kotlin("jvm")
}

val embeddableTestRuntime by configurations.creating {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}

dependencies {
    testApi(commonDep("junit"))
    testCompileOnly(project(":kotlin-scripting-jvm-host-unshaded"))
    testCompileOnly(project(":compiler:cli"))
    testCompileOnly(project(":core:util.runtime"))
    
    testRuntimeOnly(project(":kotlin-scripting-jsr223-unshaded"))
    testRuntimeOnly(project(":kotlin-compiler"))
    testRuntimeOnly(project(":kotlin-reflect"))

    embeddableTestRuntime(commonDep("junit"))
    embeddableTestRuntime(projectRuntimeJar(":kotlin-scripting-jsr223"))
    embeddableTestRuntime(projectRuntimeJar(":kotlin-scripting-compiler-embeddable"))
    embeddableTestRuntime(testSourceSet.output)
}

sourceSets {
    "main" {}
    "test" { projectDefault() }
}

tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
    kotlinOptions.freeCompilerArgs += "-Xallow-kotlin-package"
}

projectTest(parallel = true)

projectTest(taskName = "embeddableTest", parallel = true) {
    workingDir = rootDir
    dependsOn(embeddableTestRuntime)
    classpath = embeddableTestRuntime
}
