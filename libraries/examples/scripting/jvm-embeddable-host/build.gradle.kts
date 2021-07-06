
plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":examples:scripting-jvm-simple-script"))
    compileOnly(project(":kotlin-scripting-jvm-host-unshaded"))
    testRuntimeOnly(projectRuntimeJar(":kotlin-compiler-embeddable"))
    testRuntimeOnly(projectRuntimeJar(":kotlin-scripting-compiler-embeddable"))
    testRuntimeOnly(projectRuntimeJar(":kotlin-scripting-jvm-host"))
    testRuntimeOnly(project(":kotlin-reflect"))
    testRuntimeOnly(intellijDep()) { includeJars("guava", rootProject = rootProject) }
    testApi(commonDep("junit"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

