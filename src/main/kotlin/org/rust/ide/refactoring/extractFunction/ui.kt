/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.extractFunction

import java.awt.event.ActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.refactoring.ui.MethodSignatureComponent
import com.intellij.refactoring.ui.NameSuggestionsField
import com.intellij.ui.components.dialog
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import org.rust.ide.refactoring.isValidRustVariableIdentifier
import org.rust.lang.RsFileType
import org.rust.openapiext.fullWidthCell
import org.rust.openapiext.isUnitTestMode

private var MOCK: ExtractFunctionUi? = null

fun extractFunctionDialog(
    project: Project,
    config: RsExtractFunctionConfig,
    callback: () -> Unit

) {
    val extractFunctionUi = if (isUnitTestMode) {
        MOCK ?: error("You should set mock ui via `withMockExtractFunctionUi`")
    } else {
        DialogExtractFunctionUi(project)
    }
    extractFunctionUi.extract(config, callback)
}

@TestOnly
fun withMockExtractFunctionUi(mockUi: ExtractFunctionUi, action: () -> Unit) {
    MOCK = mockUi
    try {
        action()
    } finally {
        MOCK = null
    }
}

interface ExtractFunctionUi {
    fun extract(config: RsExtractFunctionConfig, callback: () -> Unit)
}

fun noLifetimeFixMode(
    project: Project,
    callback: () -> Unit
)  {
    if (!isUnitTestMode) {
        val panel = panel {
            row("Do you want to proceed with possibly incorrect lifetimes?") { button("OK") {_: ActionEvent -> callback()} }
        }
        val dialog = dialog("Lifetime Repairs Failed", panel) {
            callback()
            emptyList()
        }
        dialog.show()
    }
    return
}

fun cargoMode (
    project: Project,
    callback: () -> Unit

) : Boolean {
    val l : (ActionEvent) -> Unit = {_ : ActionEvent -> callback()}
    if (!isUnitTestMode) {
        val panel = panel {
            row("Lifetime repairs failed using Rustc. Do you want to proceed with using Cargo to repaire lifetimes?  It might take up to a minute.") 
            { button("OK", l) }
        }
        val dialog = dialog("Cargo Mode?", panel) {
            callback()
            emptyList()
        }
        dialog.show()
    }
    return true
}


private class DialogExtractFunctionUi(
    private val project: Project
) : ExtractFunctionUi {

    override fun extract(config: RsExtractFunctionConfig, callback: () -> Unit) {
        val functionNameField = NameSuggestionsField(emptyArray(), project, RsFileType)
        functionNameField.minimumSize = JBUI.size(300, 30)

        val visibilityBox = ComboBox<String>()
        with(visibilityBox) {
            addItem("Public")
            addItem("Private")
        }
        visibilityBox.selectedItem = "Private"
        val signatureComponent = RsSignatureComponent(config.signature, project)
        signatureComponent.minimumSize = JBUI.size(300, 30)

        visibilityBox.addActionListener {
            updateConfig(config, functionNameField, visibilityBox)
            signatureComponent.setSignature(config.signature)
        }

        val parameterPanel = ExtractFunctionParameterTablePanel(::isValidRustVariableIdentifier, config) {
            signatureComponent.setSignature(config.signature)
        }

        val panel = panel {
            row("Name:") { fullWidthCell(functionNameField) }
            row("Visibility:") { cell(visibilityBox) }
            row("Parameters:") { fullWidthCell(parameterPanel) }
            row("Signature:") { fullWidthCell(signatureComponent) }
        }

        val extractDialog = dialog(
            "Extract Function",
            panel,
            resizable = true,
            focusedComponent = functionNameField.focusableComponent,
            okActionEnabled = false,
            project = project,
            parent = null,
            errorText = null,
            modality = DialogWrapper.IdeModalityType.IDE
        ) {
            updateConfig(config, functionNameField, visibilityBox)
            callback()
            emptyList()
        }

        functionNameField.addDataChangedListener {
            updateConfig(config, functionNameField, visibilityBox)
            signatureComponent.setSignature(config.signature)
            extractDialog.isOKActionEnabled = isValidRustVariableIdentifier(config.name)
        }
        extractDialog.show()
    }

    private fun updateConfig(
        config: RsExtractFunctionConfig,
        functionName: NameSuggestionsField,
        visibilityBox: ComboBox<String>
    ) {
        config.name = functionName.enteredName
        config.visibilityLevelPublic = visibilityBox.selectedItem == "Public"
    }
}

private class RsSignatureComponent(
    signature: String,
    project: Project
) : MethodSignatureComponent(signature, project, RsFileType) {
    private val myFileName = "dummy." + RsFileType.defaultExtension

    override fun getFileName(): String = myFileName
}
