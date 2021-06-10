/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializer
import org.jetbrains.kotlin.backend.common.serialization.linkerissues.UserVisibleIrModulesSupport
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.library.KONAN_PLATFORM_LIBS_NAME_PREFIX
import org.jetbrains.kotlin.konan.library.KONAN_STDLIB_NAME
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.RequiredUnresolvedLibrary
import org.jetbrains.kotlin.library.unresolvedDependencies
import org.jetbrains.kotlin.utils.ResolvedDependency
import org.jetbrains.kotlin.utils.ResolvedDependencyArtifactPath
import org.jetbrains.kotlin.utils.ResolvedDependencyId
import org.jetbrains.kotlin.utils.ResolvedDependencyVersion

class KonanUserVisibleIrModulesSupport(
        externalDependenciesLoader: ExternalDependenciesLoader,
        private val konanKlibDir: File
) : UserVisibleIrModulesSupport(externalDependenciesLoader) {
    override fun getUserVisibleModules(deserializers: Collection<IrModuleDeserializer>): Map<ResolvedDependencyId, ResolvedDependency> {
        return compressedModules(deserializers)
    }

    override fun modulesFromDeserializers(deserializers: Collection<IrModuleDeserializer>): Map<ResolvedDependencyId, ResolvedDependency> {
        // Transform deserializers to [ModuleWithUninitializedDependencies]s.
        val modules = deserializers.mapNotNull { deserializer ->
            val library = deserializer.kotlinLibrary ?: return@mapNotNull null

            val moduleId = getUserVisibleModuleId(deserializer)
            val module = ResolvedDependency(
                    id = moduleId,
                    selectedVersion = library.effectiveLibraryVersion,
                    requestedVersionsByIncomingDependencies = mutableMapOf(), // To be initialized in a separate pass below.
                    artifactPaths = mutableSetOf(ResolvedDependencyArtifactPath(library.libraryFile.absolutePath))
            )

            // Don't rely on dependencies in IrModuleDeserializer. In Kotlin/Native each module depends on all other modules,
            // and this contradicts with the real module dependencies as written in KLIB manifest files.
            val outgoingDependencyIds = library.unresolvedDependencies.map { it.moduleId }

            moduleId to ModuleWithUninitializedDependencies(module, outgoingDependencyIds)
        }.toMap()

        // Stamp dependencies.
        return modules.stampDependenciesWithRequestedVersionEqualToSelectedVersion()
    }

    /**
     * This is an optimization to avoid displaying 100+ Kotlin/Native platform libraries to the user.
     * Instead, lets compress them into a single row and avoid excessive output.
     */
    private fun compressedModules(deserializers: Collection<IrModuleDeserializer>): Map<ResolvedDependencyId, ResolvedDependency> {
        val compressedModules: MutableMap<ResolvedDependencyId, ResolvedDependency> = mergedModules(deserializers)

        var platformLibrariesVersion: ResolvedDependencyVersion? = null // Must be the same version to succeed.
        val platformLibraries: MutableList<ResolvedDependency> = mutableListOf() // All platform libraries to be patched.
        val outgoingDependencyIds: MutableSet<ResolvedDependencyId> = mutableSetOf() // All outgoing dependencies from platform libraries.

        for ((moduleId, module) in compressedModules) {
            if (moduleId.isKonanPlatformLibrary) {
                if (ResolvedDependencyId.SOURCE_CODE_MODULE_ID !in module.requestedVersionsByIncomingDependencies) {
                    continue
                }

                platformLibrariesVersion = when (platformLibrariesVersion) {
                    null, module.selectedVersion -> module.selectedVersion
                    else -> {
                        // Multiple versions of platform libs. Give up.
                        return compressedModules
                    }
                }

                platformLibraries += module
            } else {
                module.requestedVersionsByIncomingDependencies.keys.forEach { incomingDependencyId ->
                    if (incomingDependencyId.isKonanPlatformLibrary) {
                        outgoingDependencyIds += moduleId
                    }
                }
            }
        }

        if (platformLibraries.isNotEmpty()) {
            platformLibraries.forEach { it.visibleAsFirstLevelDependency = false }

            val compressedModuleId = ResolvedDependencyId("$KONAN_PLATFORM_LIBS_NAME_PREFIX* (${platformLibraries.size} libraries)")
            val compressedModule = ResolvedDependency(
                    id = compressedModuleId,
                    selectedVersion = platformLibrariesVersion!!,
                    requestedVersionsByIncomingDependencies = mutableMapOf(ResolvedDependencyId.SOURCE_CODE_MODULE_ID to platformLibrariesVersion),
                    artifactPaths = mutableSetOf()
            )

            outgoingDependencyIds.forEach { outgoingDependencyId ->
                val outgoingDependency = compressedModules.getValue(outgoingDependencyId)
                outgoingDependency.requestedVersionsByIncomingDependencies[compressedModuleId] = compressedModule.selectedVersion
            }

            compressedModules[compressedModuleId] = compressedModule
        }

        return compressedModules
    }

    // For default libraries the version is the same as the version of the compiler. Note: Empty string means missing (unknown) version.
    private val KotlinLibrary.effectiveLibraryVersion: ResolvedDependencyVersion
        get() {
            // This is much safer check then KotlinLibrary.isDefault, which may return false even for "stdlib" when
            // Kotlin/Native compiler is running with "-nostdlib", "-no-endorsed-libs", "-no-default-libs" arguments.
            val isDefault = libraryFile.startsWith(konanKlibDir)
            return ResolvedDependencyVersion(versions.compilerVersion?.takeIf { isDefault }.orEmpty())
        }

    override val ResolvedDependencyId.isKotlinLibrary: Boolean
        get() = uniqueNames.any { uniqueName -> uniqueName == KONAN_STDLIB_NAME || uniqueName.startsWith(KOTLIN_LIBRARY_PREFIX) }

    companion object {
        private val RequiredUnresolvedLibrary.moduleId: ResolvedDependencyId
            get() = ResolvedDependencyId(path) // Yep, it's named "path" but in fact keeps unique name of the library.

        private val ResolvedDependencyId.isKonanPlatformLibrary: Boolean
            get() = uniqueNames.any { it.startsWith(KONAN_PLATFORM_LIBS_NAME_PREFIX) }
    }
}
