/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.extractFunction

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.elementType
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.RefactoringBundle
import com.intellij.usageView.UsageInfo
import org.rust.ide.presentation.PsiRenderingOptions
import org.rust.ide.presentation.RsPsiRenderer
import org.rust.ide.presentation.renderTypeReference
import org.rust.ide.refactoring.RsRenameProcessor
import org.rust.ide.utils.GenericConstraints
import org.rust.ide.utils.import.RsImportHelper.importTypeReferencesFromTys
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.psi.impl.RsMethodCallImpl
import org.rust.lang.core.psi.impl.RsFunctionImpl
import org.rust.lang.core.psi.impl.*
import org.rust.lang.core.resolve.RsCachedImplItem
import org.rust.lang.core.types.*
import org.rust.lang.core.types.ty.*
import org.rust.openapiext.runWriteCommandAction
import org.apache.commons.io.IOUtils
import java.io.File

class RsExtractFunctionHandler : RefactoringActionHandler {
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        //this doesn't get called from the editor.
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (file !is RsFile) return
        val start = editor?.selectionModel?.selectionStart
        val end = editor?.selectionModel?.selectionEnd
        if (start === null || end === null) return
        val config = RsExtractFunctionConfig.create(file, start, end) ?: return

        extractFunctionDialog(project, config) {
            extractFunction(project, file, config)
        }
    }

    private fun extractFunction(project: Project, file: PsiFile, config: RsExtractFunctionConfig) {
        project.runWriteCommandAction(
            RefactoringBundle.message("extract.method.title"),
            file
        ) {
            val psiFactory = RsPsiFactory(project)
            val extractedFunction = addExtractedFunction(project, config, psiFactory) ?: return@runWriteCommandAction
            replaceOldStatementsWithCallExpr(config, psiFactory)
            val parameters = config.valueParameters.filter { it.isSelected }
            renameFunctionParameters(extractedFunction, parameters.map { it.name })
            val types = (parameters.map { it.type } + config.returnValue?.type).filterNotNull()
            importTypeReferencesFromTys(extractedFunction, types)
            if (!dumpMethodCallTypes(extractedFunction)) {
                return@runWriteCommandAction
            }
            nonLocalController(config, file)
            LOG.info("controller completed")
            borrow(config, file)
            LOG.info("borrow completed")
            repairLifetime(config, file)
            LOG.info("repairer completed")
        }
    }

    private fun dumpMethodCallTypes(extractFn: RsFunction) : Boolean {
        val dumpFileName = "/tmp/method_call_mutability.txt"
        val dumpFile = File(dumpFileName)
        dumpFile.writeText("")
        val visitor = object : RsVisitor() {
            override fun visitElement(o: RsElement) {
                o.acceptChildren(this)
            }

            override fun visitBlock(o: RsBlock) {
                LOG.debug("elem: ${o.text}")
                LOG.debug("elem type: $o")
                for (stmt in o.getStmtList()) {
                    stmt.acceptChildren(this)
                }
            }

            override fun visitDotExpr(o: RsDotExpr) {
                LOG.debug("dot expr: ${o.text}")
                val methodCall = o.getMethodCall()!!
                val inferred = methodCall.inference!!.getResolvedMethodType(o.getMethodCall()!!)!!

                val selfTy = inferred.paramTypes[0]
                if (selfTy is TyReference) {
                    if (selfTy.mutability.isMut) {
                        dumpFile.appendText("${o.text}\n")
                    }
                }

                super.visitDotExpr(o)
            }
        }
        try {
            extractFn.acceptChildren(visitor)
        } catch (e: Exception) {
            LOG.error("dump method call failed: $e")
            return false
        }
        return true
    }

    private fun nonLocalController(config: RsExtractFunctionConfig, file: PsiFile) {
        val name = config.name
        val parentFn = config.function
        val controlBin = "/home/sewen/YNC_Academics/Senior/Capstone/controller-v1"
        val fileParent = file.getContainingDirectory().getVirtualFile().getPath()
        val fileName = file.name
        val filePath = "$fileParent/$fileName"
        LOG.info("file path: $filePath")

        val bak = "/tmp/${fileName}-ij-extract.bk"
        val cmd1 = arrayOf("cp", filePath, bak)
        val proc1 = Runtime.getRuntime().exec(cmd1)
        while (proc1.isAlive) {}

        //write the extracted fn
        File(filePath).writeText(file.text)

        val cmd = arrayOf(controlBin, "run", filePath, filePath, parentFn.name, name)
        val proc = Runtime.getRuntime().exec(cmd)
        while (proc.isAlive) {}
        val exitValue = proc.exitValue()
        val stderr = proc.errorStream.bufferedReader().readText()
        val stdout = proc.inputStream.bufferedReader().readText()
        LOG.info("running controller: \nstdout:\n$stdout\nstderr:\n$stderr")
        LOG.info("exit val $exitValue")
        if (exitValue != 0) {
            LOG.info("bad exit val restoring file")
            val cmd3 = arrayOf("cp", bak, filePath)
            val proc3 = Runtime.getRuntime().exec(cmd3)
            while (proc3.isAlive) {}
        }
        VfsUtil.markDirtyAndRefresh(false, true, true, file.getVirtualFile())
    }

    private fun borrow(config: RsExtractFunctionConfig, file: PsiFile) {
        val name = config.name
        val parentFn = config.function
        val borrowBin = "/home/sewen/YNC_Academics/Senior/Capstone/borrower-v1"
        val fileParent = file.getContainingDirectory().getVirtualFile().getPath()
        val fileName = file.name
        val filePath = "$fileParent/$fileName"
        LOG.info("file path: $filePath")

        val bak = "/tmp/${fileName}-ij-extract.bk"
        val cmd1 = arrayOf("cp", filePath, bak)
        val proc1 = Runtime.getRuntime().exec(cmd1)
        while (proc1.isAlive) {}

        val cmd = arrayOf(borrowBin, "run", filePath, filePath, parentFn.name, name)
        val proc = Runtime.getRuntime().exec(cmd)
        while (proc.isAlive) {}
        val exitValue = proc.exitValue()
        val stderr = proc.errorStream.bufferedReader().readText()
        val stdout = proc.inputStream.bufferedReader().readText()
        LOG.info("running borrower: \nstdout:\n$stdout\nstderr:\n$stderr")
        LOG.info("exit val $exitValue")
        if (exitValue != 0) {
            LOG.info("bad exit val restoring file")
            val cmd3 = arrayOf("cp", bak, filePath)
            val proc3 = Runtime.getRuntime().exec(cmd3)
            while (proc3.isAlive) {}
        }
        VfsUtil.markDirtyAndRefresh(false, true, true, file.getVirtualFile())
    }

    private fun repairLifetime(config: RsExtractFunctionConfig, file: PsiFile) {
        val name = config.name
        val repairBin = "/home/sewen/YNC_Academics/Senior/Capstone/repairer-v1"
        val fileParent = file.getContainingDirectory().getVirtualFile().getPath()
        val fileName = file.name
        val filePath = "$fileParent/$fileName"
        LOG.info("file path: $filePath")

        val bak = "/tmp/${fileName}-ij-extract.bk"
        val cmd1 = arrayOf("cp", filePath, bak)
        val proc1 = Runtime.getRuntime().exec(cmd1)
        while (proc1.isAlive) {}

        // find manifest
        var here = file.getContainingDirectory()
        while (here.findFile("Cargo.toml") == null) {
            here = here.getParentDirectory()
        }
        val herePath = here.getVirtualFile().getPath()
        val manifestPath = "$herePath/Cargo.toml"
        LOG.info("manifest: $manifestPath")
        val cmd = arrayOf(repairBin, "cargo", filePath, manifestPath, name, "loosest-bounds-first")
        val proc = Runtime.getRuntime().exec(cmd)
        while (proc.isAlive) {}
        val exitValue = proc.exitValue()
        val stderr = proc.errorStream.bufferedReader().readText()
        val stdout = proc.inputStream.bufferedReader().readText()
        LOG.info("running repair: \nstdout:\n$stdout\nstderr:\n$stderr")
        LOG.info("exit val $exitValue")
        if (exitValue != 0) {
            LOG.info("bad exit val restoring file")
            val cmd3 = arrayOf("cp", bak, filePath)
            val proc3 = Runtime.getRuntime().exec(cmd3)
            while (proc3.isAlive) {}
        }
        VfsUtil.markDirtyAndRefresh(false, true, true, file.getVirtualFile())
    }

    private fun addExtractedFunction(
        project: Project,
        config: RsExtractFunctionConfig,
        psiFactory: RsPsiFactory
    ): RsFunction? {
        val owner = config.function.owner
        val function = psiFactory.createFunction(config.functionText)
        val psiParserFacade = PsiParserFacade.getInstance(project)
        return when {
            owner is RsAbstractableOwner.Impl && !owner.isInherent -> {
                val impl = findExistingInherentImpl(owner.impl) ?: createNewInherentImpl(owner.impl) ?: return null
                val members = impl.members ?: return null
                members.addBefore(psiParserFacade.createWhiteSpaceFromText("\n\n"), members.rbrace)
                members.addBefore(function, members.rbrace) as? RsFunction
            }
            else -> {
                val newline = psiParserFacade.createWhiteSpaceFromText("\n\n")
                val end = config.function.block?.rbrace ?: return null
                config.function.addAfter(function, config.function.addAfter(newline, end)) as? RsFunction
            }
        }
    }

    /**
     * Finds inherent impl corresponding to [traitImpl].
     * Impls at same tree level are checked (e.g. if [traitImpl] is top-level impl, then top-level impls are checked).
     */
    private fun findExistingInherentImpl(traitImpl: RsImplItem): RsImplItem? {
        check(traitImpl.traitRef != null)
        val cachedTraitImpl = RsCachedImplItem.forImpl(traitImpl)
        return (traitImpl.parent as? RsItemsOwner)
            ?.childrenOfType<RsImplItem>()
            ?.firstOrNull { impl ->
                val cachedImpl = RsCachedImplItem.forImpl(impl)
                val (_, generics, constGenerics) = cachedImpl.typeAndGenerics ?: return@firstOrNull false
                cachedImpl.isInherent && cachedImpl.isValid && !cachedImpl.isNegativeImpl
                    && generics.isEmpty() && constGenerics.isEmpty()  // TODO: Support generics
                    && cachedImpl.typeAndGenerics == cachedTraitImpl.typeAndGenerics
            }
    }

    private fun createNewInherentImpl(traitImpl: RsImplItem): RsImplItem? {
        val parent = traitImpl.parent
        val psiFactory = RsPsiFactory(parent.project)

        val typeReference = traitImpl.typeReference!!
        val constraints = GenericConstraints.create(traitImpl).filterByTypeReferences(listOf(typeReference))

        val renderer = RsPsiRenderer(PsiRenderingOptions())

        val typeParameters = constraints.buildTypeParameters()
        val typeText = renderer.renderTypeReference(typeReference)
        val whereClause = constraints.buildWhereClause()

        val text = "impl$typeParameters $typeText $whereClause{}"
        val newImpl = psiFactory.tryCreateImplItem(text) ?: return null

        val newImplCopy = parent.addAfter(newImpl, traitImpl) as RsImplItem
        parent.addBefore(psiFactory.createWhitespace("\n\n"), newImplCopy)
        return newImplCopy
    }

    /**
     * Original function signature and body are inserted at first.
     * Then it is necessary to change the names of original parameters to the real (renamed) parameters' names.
     */
    private fun renameFunctionParameters(function: RsFunction, newNames: List<String>) {
        val parameters = function.rawValueParameters
            .map { it.pat }
            .filterIsInstance(RsPatIdent::class.java)
            .map { it.patBinding }

        for ((parameter, newName) in parameters.zip(newNames)) {
            if (newName != parameter.name) {
                val parameterUsages = ReferencesSearch.search(parameter, LocalSearchScope(function)).findAll()
                val usageInfo = parameterUsages.map { UsageInfo(it) }.toTypedArray()
                RsRenameProcessor().renameElement(parameter, newName, usageInfo, null)
            }
        }
    }

    private fun replaceOldStatementsWithCallExpr(config: RsExtractFunctionConfig, psiFactory: RsPsiFactory) {
        val stmt = StringBuilder()
        if (config.returnValue?.exprText != null) {
            stmt.append("let ${config.returnValue.exprText} = ")
        }
        val firstParameter = config.parameters.firstOrNull()
        stmt.append(if (firstParameter != null && firstParameter.isSelf) {
            "self.${config.name}(${config.argumentsText})"
        } else {
            val type = when (config.function.owner) {
                is RsAbstractableOwner.Impl,
                is RsAbstractableOwner.Trait -> "Self"
                else -> null
            }
            "${if (type != null) "$type::" else ""}${config.name}(${config.argumentsText})"
        })
        if (config.isAsync) {
            stmt.append(".await")
        }
        config.elements.forEachIndexed { index, psiElement ->
            if (index == config.elements.lastIndex) {
                when (psiElement) {
                    is RsExpr -> psiElement.replace(psiFactory.createExpression(stmt.toString()))
                    is RsExprStmt -> {
                        val needsSemicolon = config.returnValue == null || config.returnValue.exprText != null
                        if (needsSemicolon) {
                            stmt.append(";")
                        }
                        psiElement.replace(psiFactory.createStatement(stmt.toString()))
                    }
                    is RsStmt -> {
                        stmt.append(";")
                        psiElement.replace(psiFactory.createStatement(stmt.toString()))
                    }
                }
            } else {
                psiElement.delete()
            }
        }
    }

    companion object {
        val LOG: Logger = logger<RsExtractFunctionHandler>()
    }
}
