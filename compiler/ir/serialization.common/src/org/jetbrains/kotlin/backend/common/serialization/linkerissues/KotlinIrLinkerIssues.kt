/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.linkerissues

import org.jetbrains.kotlin.backend.common.serialization.IrModuleDeserializer
import org.jetbrains.kotlin.ir.linkage.KotlinIrLinkerInternalException
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.ResolvedDependency
import org.jetbrains.kotlin.utils.ResolvedDependencyId

abstract class KotlinIrLinkerIssue {
    protected abstract val message: String

    fun raiseIssue(messageLogger: IrMessageLogger): KotlinIrLinkerInternalException {
        messageLogger.report(IrMessageLogger.Severity.ERROR, message, null)
        throw KotlinIrLinkerInternalException()
    }

    protected fun StringBuilder.appendDependencies(
        allModules: Map<ResolvedDependencyId, ResolvedDependency>,
        problemModuleId: ResolvedDependencyId? = null,
        moduleIdComparator: Comparator<ResolvedDependencyId>
    ) {
        append("\n\nProject dependencies:")
        if (allModules.isEmpty()) {
            append(" <empty>")
            return
        }

        val incomingDependencyIdToDependencies: MutableMap<ResolvedDependencyId, MutableCollection<ResolvedDependency>> = mutableMapOf()
        allModules.values.forEach { module ->
            module.requestedVersionsByIncomingDependencies.keys.forEach { incomingDependencyId ->
                incomingDependencyIdToDependencies.getOrPut(incomingDependencyId) { mutableListOf() } += module
            }
        }

        val renderedModules: MutableSet<ResolvedDependencyId> = mutableSetOf()
        var everDependenciesOmitted = false

        fun renderModules(modules: Collection<ResolvedDependency>, parentData: Data?) {
            val filteredModules: Collection<ResolvedDependency> = if (parentData == null)
                modules.filter { it.visibleAsFirstLevelDependency }
            else
                modules

            val sortedModules: List<ResolvedDependency> = filteredModules.sortedWith { a, b ->
                moduleIdComparator.compare(a.id, b.id)
            }

            sortedModules.forEachIndexed { index, module ->
                val data = Data(
                    parent = parentData,
                    incomingDependencyId = module.id, // For children.
                    isLast = index + 1 == sortedModules.size
                )

                append('\n').append(data.regularLinePrefix)
                module.id.uniqueNames.joinTo(this)

                val incomingDependencyId: ResolvedDependencyId = parentData?.incomingDependencyId
                    ?: ResolvedDependencyId.SOURCE_CODE_MODULE_ID
                val requestedVersion = module.requestedVersionsByIncomingDependencies.getValue(incomingDependencyId)
                if (!requestedVersion.isEmpty() || !module.selectedVersion.isEmpty()) {
                    append(": ")
                    append(requestedVersion.version.ifEmpty { UNKNOWN_VERSION })
                    if (requestedVersion != module.selectedVersion) {
                        append(" -> ")
                        append(module.selectedVersion.version.ifEmpty { UNKNOWN_VERSION })
                    }
                }

                if (problemModuleId == module.id) {
                    append('\n').append(data.errorLinePrefix)
                    append("^^^ This is a problem module.")
                }

                val dependencies: Collection<ResolvedDependency>? = incomingDependencyIdToDependencies[module.id]
                val renderedFirstTime = renderedModules.add(module.id)
                if (renderedFirstTime) {
                    // Rendered for the first time => also render dependencies.
                    if (dependencies != null) {
                        renderModules(dependencies, data)
                    }
                } else if (!dependencies.isNullOrEmpty()) {
                    everDependenciesOmitted = true
                    append(" (*)")
                }
            }
        }

        // Find first-level dependencies. I.e. the modules that the source code module directly depends on.
        val firstLevelDependencies: Collection<ResolvedDependency> =
            incomingDependencyIdToDependencies.getValue(ResolvedDependencyId.SOURCE_CODE_MODULE_ID)

        renderModules(firstLevelDependencies, parentData = null)

        if (everDependenciesOmitted) {
            append("\n\n(*) - dependencies omitted (listed previously)")
        }
    }

    private class Data(val parent: Data?, val incomingDependencyId: ResolvedDependencyId, val isLast: Boolean) {
        val regularLinePrefix: String
            get() {
                return generateSequence(this) { it.parent }.map {
                    if (it === this) {
                        if (it.isLast) "\u2514\u2500\u2500\u2500 " /* └─── */ else "\u251C\u2500\u2500\u2500 " /* ├─── */
                    } else {
                        if (it.isLast) "     " else "\u2502    " /* │ */
                    }
                }.toList().asReversed().joinToString(separator = "")
            }

        val errorLinePrefix: String
            get() {
                return generateSequence(this) { it.parent }.map {
                    if (it.isLast) "     " else "\u2502    " /* │ */
                }.toList().asReversed().joinToString(separator = "")
            }
    }

    companion object {
        private const val UNKNOWN_VERSION = "unknown"
    }
}

class SignatureIdNotFoundInModuleWithDependencies(
    idSignature: IdSignature,
    currentModuleDeserializer: IrModuleDeserializer,
    allModuleDeserializers: Collection<IrModuleDeserializer>,
    userVisibleIrModulesSupport: UserVisibleIrModulesSupport
) : KotlinIrLinkerIssue() {
    override val message = buildString {
        val currentModuleId: ResolvedDependencyId = userVisibleIrModulesSupport.getUserVisibleModuleId(currentModuleDeserializer)
        val allVisibleModules: Map<ResolvedDependencyId, ResolvedDependency> =
            userVisibleIrModulesSupport.getUserVisibleModules(allModuleDeserializers)

        // cause:
        append("Module $currentModuleId has a reference to symbol ${idSignature.render()}.")
        append(" Neither the module itself nor its dependencies contain such declaration.")

        // explanation:
        append("\n\nThis could happen if the required dependency is missing in the project.")
        append(" Or if there are two (or more) dependency libraries, where one library ($currentModuleId)")
        append(" was compiled against the different version of the other library")
        append(" than the one currently used in the project.")

        // action items:
        append(" Please check that the project configuration is correct and has consistent versions of all required dependencies.")

        // the tree of dependencies:
        appendDependencies(
            allModules = allVisibleModules,
            problemModuleId = currentModuleId,
            moduleIdComparator = userVisibleIrModulesSupport.moduleIdComparator
        )
    }
}

class NoDeserializerForModule(moduleName: Name, idSignature: IdSignature?) : KotlinIrLinkerIssue() {
    override val message = buildString {
        append("Could not load module ${moduleName.asString()}")
        if (idSignature != null) append(" in an attempt to find deserializer for symbol ${idSignature.render()}.")
    }
}

class SymbolTypeMismatch(
    cause: IrSymbolTypeMismatchException,
    allModuleDeserializers: Collection<IrModuleDeserializer>,
    userVisibleIrModulesSupport: UserVisibleIrModulesSupport
) : KotlinIrLinkerIssue() {
    override val message: String = buildString {
        // cause:
        append(cause.message)

        // explanation:
        append("\n\nThis could happen if there are two (or more) dependency libraries,")
        append(" where one library was compiled against the different version of the other library")
        append(" than the one currently used in the project.")

        // action items:
        append(" Please check that the project configuration is correct and has consistent versions of dependencies.")

        // the tree of dependencies:
        appendDependencies(
            allModules = userVisibleIrModulesSupport.getUserVisibleModules(allModuleDeserializers),
            moduleIdComparator = userVisibleIrModulesSupport.moduleIdComparator
        )
    }
}
