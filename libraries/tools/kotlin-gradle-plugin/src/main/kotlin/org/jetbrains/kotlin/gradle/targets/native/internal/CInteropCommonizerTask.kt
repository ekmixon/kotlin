/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.internal

import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.commonizer.*
import org.jetbrains.kotlin.compilerRunner.GradleCliCommonizer
import org.jetbrains.kotlin.compilerRunner.konanHome
import org.jetbrains.kotlin.gradle.dsl.multiplatformExtensionOrNull
import org.jetbrains.kotlin.gradle.plugin.CInteropSettings
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinSharedNativeCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.kotlinSourceSetsIncludingDefault
import org.jetbrains.kotlin.gradle.plugin.sources.resolveAllDependsOnSourceSets
import org.jetbrains.kotlin.gradle.targets.native.internal.CInteropCommonizerTask.CInteropGist
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

@CacheableTask
internal open class CInteropCommonizerTask : AbstractCInteropCommonizerTask() {

    internal data class CInteropGist(
        @get:Input val identifier: CInteropIdentifier,
        @get:Input val konanTarget: KonanTarget,
        @get:Internal val sourceSets: Provider<Set<KotlinSourceSet>>,
        @get:Classpath val libraryFile: Provider<File>,
        @get:Classpath val dependencies: FileCollection
    ) {
        @Suppress("unused") // Used for UP-TO-DATE check
        @get:Input
        val allSourceSetNames: Provider<List<String>> = sourceSets.map { it.resolveAllDependsOnSourceSets().map(Any::toString) }
    }

    override val outputDirectory: File = project.buildDir.resolve("classes/kotlin/commonizer")

    @get:Nested
    internal var cinterops = setOf<CInteropGist>()
        private set

    @get:OutputDirectories
    val allOutputDirectories: Set<File>
        get() = getAllCommonizationParameters().map { outputDirectory(it) }.toSet()

    @Suppress("unused") // Used for UP-TO-DATE check
    @get:Classpath
    val commonizedNativeDistributionDependencies: Set<File>
        get() = getAllCommonizationParameters().flatMap { parameters -> parameters.targets }
            .flatMap { target -> project.getNativeDistributionDependencies(target) }
            .toSet()

    fun from(vararg tasks: CInteropProcess) = from(
        tasks.toList()
            .onEach { task -> this.dependsOn(task) }
            .map { task -> task.toGist() }
    )

    internal fun from(vararg cinterop: CInteropGist) {
        from(cinterop.toList())
    }

    internal fun from(cinterops: List<CInteropGist>) {
        this.cinterops += cinterops
    }

    fun exclude(vararg tasks: CInteropProcess) {
        exclude(tasks.map { it.settings.identifier })
    }

    fun exclude(vararg settings: CInteropSettings) {
        exclude(settings.mapNotNull { (it as? DefaultCInteropSettings)?.identifier })
    }

    internal fun exclude(interopIdentifiers: List<CInteropIdentifier>) {
        this.cinterops = this.cinterops.filterTo(mutableSetOf()) { it.identifier !in interopIdentifiers }
    }

    @TaskAction
    internal fun commonizeCInteropLibraries() {
        getAllCommonizationParameters().forEach(::commonize)
    }

    private fun commonize(parameters: CInteropCommonizationParameters) {
        val cinteropsForTarget = cinterops.filter { cinterop -> cinterop.identifier in parameters.interops }
        outputDirectory(parameters).deleteRecursively()
        if (cinteropsForTarget.isEmpty()) return

        GradleCliCommonizer(project).commonizeLibraries(
            konanHome = project.file(project.konanHome),
            outputTargets = parameters.targets,
            inputLibraries = cinteropsForTarget.map { it.libraryFile.get() }.filter { it.exists() }.toSet(),
            dependencyLibraries = cinteropsForTarget.flatMap { it.dependencies.files }.map(::NonTargetedCommonizerDependency).toSet()
                    + getNativeDistributionDependencies(parameters),
            outputDirectory = outputDirectory(parameters),
            logLevel = project.commonizerLogLevel
        )
    }

    private fun getNativeDistributionDependencies(parameters: CInteropCommonizationParameters): Set<CommonizerDependency> {
        return (parameters.targets + parameters.targets.allLeaves()).flatMapTo(mutableSetOf()) { target ->
            project.getNativeDistributionDependencies(target).map { dependency -> TargetedCommonizerDependency(target, dependency) }
        }
    }

    private fun findAllSharedInterops(): Set<SharedInterops> {
        val multiplatformExtension = project.multiplatformExtensionOrNull ?: return emptySet()

        val fromSharedNativeCompilations = multiplatformExtension
            .targets.flatMap { target -> target.compilations }
            .filterIsInstance<KotlinSharedNativeCompilation>()
            .mapNotNull { compilation -> findSharedInterops(compilation) }
            .toSet()

        val fromSourceSets = multiplatformExtension.sourceSets
            .mapNotNull { sourceSet -> findSharedInterops(project, sourceSet) }
            .toSet()

        return (fromSharedNativeCompilations + fromSourceSets)
            .run(::removeNotRegisteredInterops)
    }

    @Nested
    internal fun getAllCommonizationParameters(): Set<CInteropCommonizationParameters> {
        val sharedNativeCInterops = findAllSharedInterops()
        if (sharedNativeCInterops.isEmpty()) return emptySet()

        return sharedNativeCInterops.distinct()
            .filter { potentialRoot -> sharedNativeCInterops.none { other -> potentialRoot isProperSubsetOf other } }
            .map { root -> root to sharedNativeCInterops.filter { other -> other isProperSubsetOf root } }
            .mapTo(mutableSetOf()) { (root, subsets) ->
                CInteropCommonizationParameters(
                    targets = subsets.mapTo(mutableSetOf()) { it.target } + root.target,
                    interops = root.interops
                )
            }
    }

    override fun findCommonizationParameters(sharedInterops: SharedInterops): CInteropCommonizationParameters? {
        val supportedParameters = getAllCommonizationParameters().filter { parameters -> parameters.supports(sharedInterops) }
        if (supportedParameters.isEmpty()) return null
        assert(supportedParameters.size == 1) {
            "Unnecessary work detected: Multiple commonization parameters seem to be doing redundant work"
        }
        return supportedParameters.first()
    }
}

internal fun CInteropCommonizationParameters.supports(
    compilation: KotlinSharedNativeCompilation
): Boolean {
    return supports(findSharedInterops(compilation) ?: return false)
}

internal fun CInteropCommonizationParameters.supports(sharedCInterops: SharedInterops): Boolean {
    return targets.contains(sharedCInterops.target) && interops.containsAll(sharedCInterops.interops)
}

private fun CInteropProcess.toGist(): CInteropGist {
    return CInteropGist(
        identifier = settings.identifier,
        konanTarget = konanTarget,
        // FIXME support cinterop with PM20
        sourceSets = project.provider { (settings.compilation as? KotlinCompilation<*>)?.kotlinSourceSetsIncludingDefault },
        libraryFile = outputFileProvider,

        /**
         * See: KT-46109
         * For now, c-interop commonization is invoked for all relevant files together.
         * Using dependencies coming e.g. from a different Gradle project requires additional design.
         */
        dependencies = project.files()
    )
}

/**
 * Represents a single invocation to the commonizer
 */
internal data class CInteropCommonizationParameters(
    @get:Input val targets: Set<SharedCommonizerTarget>,
    @get:Input val interops: Set<CInteropIdentifier>
)

private fun CInteropCommonizerTask.removeNotRegisteredInterops(
    parameters: Set<SharedInterops>
): Set<SharedInterops> {
    val registeredInterops = this.cinterops.map { it.identifier }
    return parameters.mapTo(mutableSetOf()) { params ->
        params.copy(interops = params.interops.filterTo(mutableSetOf()) { interop -> interop in registeredInterops })
    }
}

private infix fun SharedInterops.isProperSubsetOf(other: SharedInterops): Boolean {
    return target.allLeaves() != other.target.allLeaves() && other.target.allLeaves().containsAll(target.allLeaves())
            && interops != other.interops && other.interops.containsAll(interops)
}
