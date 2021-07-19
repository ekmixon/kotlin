/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.internal

import org.gradle.api.Project
import org.jetbrains.kotlin.commonizer.SharedCommonizerTarget
import org.jetbrains.kotlin.commonizer.allLeaves
import org.jetbrains.kotlin.commonizer.util.transitiveClosure
import org.jetbrains.kotlin.gradle.dsl.multiplatformExtensionOrNull
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.AbstractKotlinNativeCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.CompilationSourceSetUtil.compilationsBySourceSets
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinSharedNativeCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.kotlinSourceSetsIncludingDefault
import org.jetbrains.kotlin.gradle.plugin.sources.resolveAllDependsOnSourceSets

// TODO NOW: functionalTest!
/**
 * Represents a single shared native compilation / shared native source set
 * that would rely on given [interops]
 */
internal data class SharedInterops(
    val target: SharedCommonizerTarget,
    val interops: Set<CInteropIdentifier>
)

internal fun findSharedInterops(compilation: KotlinSharedNativeCompilation): SharedInterops? {
    val project = compilation.project

    return SharedInterops(
        target = project.getCommonizerTarget(compilation) as? SharedCommonizerTarget ?: return null,
        interops = project.getDependingNativeCompilations(compilation)
            /* If any depending native compilation has no interop, then commonization is useless */
            .flatMap { nativeCompilation -> nativeCompilation.cinterops.ifEmpty { return null } }
            .map { interop -> interop.identifier }
            .toSet()
            .ifEmpty { return null }
    )
}


internal fun findSharedInterops(project: Project, sourceSet: KotlinSourceSet): SharedInterops? {
    val commonizerTarget = project.getCommonizerTarget(sourceSet) as? SharedCommonizerTarget ?: return null
    val compilations = compilationsBySourceSets(project)[sourceSet] ?: return null

    /* Non-native or non 'shared native' source sets can return eagerly */
    if (compilations.any { compilation -> compilation !is AbstractKotlinNativeCompilation }) {
        return null
    }

    val allInterops = compilations.filterIsInstance<KotlinNativeCompilation>()
        .flatMap { nativeCompilation ->
            // TODO NOW: Ugly!
            nativeCompilation.cinterops.plus(
                transitiveClosure(nativeCompilation) { associateWith.filterIsInstance<KotlinNativeCompilation>() }.flatMap { it.cinterops }
            ).ifEmpty { return null }
        }
        .map { interop -> interop.identifier }
        .toSet()
        .ifEmpty { return null }

    return SharedInterops(commonizerTarget, allInterops)
}

private fun Project.getDependingNativeCompilations(compilation: KotlinSharedNativeCompilation): Set<KotlinNativeCompilation> {
    /**
     * Some implementations of [KotlinCompilation] do not contain the default source set in
     * [KotlinCompilation.kotlinSourceSets] or [KotlinCompilation.allKotlinSourceSets]
     * see KT-45412
     */
    fun KotlinCompilation<*>.allParticipatingSourceSets(): Set<KotlinSourceSet> {
        return kotlinSourceSetsIncludingDefault + kotlinSourceSetsIncludingDefault.resolveAllDependsOnSourceSets()
    }

    val multiplatformExtension = multiplatformExtensionOrNull ?: return emptySet()
    val allParticipatingSourceSetsOfCompilation = compilation.allParticipatingSourceSets()

    return multiplatformExtension.targets
        .flatMap { target -> target.compilations }
        .filterIsInstance<KotlinNativeCompilation>()
        .filter { nativeCompilation -> nativeCompilation.allParticipatingSourceSets().containsAll(allParticipatingSourceSetsOfCompilation) }
        .toSet()
}
