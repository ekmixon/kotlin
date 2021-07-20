/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.frontend.api.test.framework

import com.intellij.configurationStore.SchemeNameToFileName
import com.intellij.configurationStore.StreamProvider
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.mock.MockApplication
import com.intellij.mock.MockProject
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.options.EmptySchemesManager
import com.intellij.openapi.options.SchemeManager
import com.intellij.openapi.options.SchemeManagerFactory
import com.intellij.openapi.options.SchemeProcessor
import com.intellij.psi.codeStyle.*
import com.intellij.psi.impl.source.codeStyle.CodeStyleSchemesImpl
import com.intellij.psi.impl.source.codeStyle.IndentHelper
import com.intellij.psi.impl.source.codeStyle.IndentHelperImpl
import com.intellij.psi.impl.source.codeStyle.PersistableCodeStyleSchemes
import org.jetbrains.kotlin.idea.fir.low.level.api.test.base.AbstractLowLevelApiTest
import org.jetbrains.kotlin.idea.frontend.api.InvalidWayOfUsingAnalysisSession
import org.jetbrains.kotlin.idea.frontend.api.KtAnalysisSessionProvider
import org.jetbrains.kotlin.idea.frontend.api.fir.KtFirAnalysisSessionProvider
import org.jetbrains.kotlin.idea.references.HLApiReferenceProviderService
import org.jetbrains.kotlin.idea.references.KotlinFirReferenceContributor
import org.jetbrains.kotlin.idea.references.KotlinReferenceProviderContributor
import org.jetbrains.kotlin.psi.KotlinReferenceProvidersService
import java.nio.file.Path

abstract class AbstractHLApiTest : AbstractLowLevelApiTest() {
    @OptIn(InvalidWayOfUsingAnalysisSession::class)
    override fun registerServicesForProject(project: MockProject) {
        super.registerServicesForProject(project)
        project.registerService(KtAnalysisSessionProvider::class.java, KtFirAnalysisSessionProvider::class.java)
        project.registerService(ProjectCodeStyleSettingsManager::class.java)
    }

    override fun registerApplicationServices(application: MockApplication) {
        super.registerApplicationServices(application)
        if (application.getServiceIfCreated(KotlinReferenceProvidersService::class.java) != null) return
        application.registerService(KotlinReferenceProvidersService::class.java, HLApiReferenceProviderService::class.java)
        application.registerService(KotlinReferenceProviderContributor::class.java, KotlinFirReferenceContributor::class.java)
        application.registerService(IndentHelper::class.java, IndentHelperImpl::class.java)
        application.registerService(AppCodeStyleSettingsManager::class.java, AppCodeStyleSettingsManager::class.java)
        application.registerService(CodeStyleSettingsService::class.java, CodeStyleSettingsServiceImpl::class.java)


        application.registerService(SchemeManagerFactory::class.java, object : SchemeManagerFactory() {
            override fun <SCHEME : Any, MUTABLE_SCHEME : SCHEME> create(
                directoryName: String,
                processor: SchemeProcessor<SCHEME, MUTABLE_SCHEME>,
                presentableName: String?,
                roamingType: RoamingType,
                schemeNameToFileName: SchemeNameToFileName,
                streamProvider: StreamProvider?,
                directoryPath: Path?,
                isAutoSave: Boolean
            ): SchemeManager<SCHEME> {
                @Suppress("UNCHECKED_CAST")
                return EmptySchemesManager() as SchemeManager<SCHEME>
            }

        })
        application.registerService(CodeStyleSchemes::class.java, PersistableCodeStyleSchemes::class.java)

        CoreApplicationEnvironment.registerApplicationExtensionPoint(
            CodeStyleSettingsProvider.EXTENSION_POINT_NAME,
            CodeStyleSettingsProvider::class.java
        )
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
            LanguageCodeStyleSettingsProvider.EP_NAME,
            LanguageCodeStyleSettingsProvider::class.java
        )
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
            FileIndentOptionsProvider.EP_NAME,
            FileIndentOptionsProvider::class.java
        )

        CoreApplicationEnvironment.registerApplicationExtensionPoint(
            FileTypeIndentOptionsProvider.EP_NAME,
            FileTypeIndentOptionsProvider::class.java
        )

        CoreApplicationEnvironment.registerApplicationExtensionPoint(
            FileCodeStyleProvider.EP_NAME,
            FileCodeStyleProvider::class.java
        )
    }
}