package org.jetbrains.research.testgenie.tools.llm

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import org.jetbrains.research.testgenie.TestGenieBundle
import org.jetbrains.research.testgenie.actions.*
import org.jetbrains.research.testgenie.data.CodeType
import org.jetbrains.research.testgenie.data.FragmentToTestDada
import org.jetbrains.research.testgenie.editor.Workspace
import org.jetbrains.research.testgenie.helpers.generateMethodDescriptor
import org.jetbrains.research.testgenie.tools.evosuite.generation.ResultWatcher
import org.jetbrains.research.testgenie.tools.isPromptLengthWithinLimit
import org.jetbrains.research.testgenie.tools.llm.error.LLMErrorManager
import org.jetbrains.research.testgenie.tools.llm.generation.LLMProcessManager
import org.jetbrains.research.testgenie.tools.llm.generation.PromptManager
import org.jetbrains.research.testgenie.tools.template.Tool

/**
 * The Llm class represents a tool called "Llm" that is used to generate tests for Java code.
 *
 * @param name The name of the tool. Default value is "Llm".
 */
class Llm(override val name: String = "Llm") : Tool {
    private val log = Logger.getInstance(ResultWatcher::class.java)

    private val llmErrorManager: LLMErrorManager = LLMErrorManager()

    private fun getLLMProcessManager(e: AnActionEvent, codeType: FragmentToTestDada): LLMProcessManager {
        val project: Project = e.project!!

        val classesToTest = mutableListOf<PsiClass>()
        // check if cut has any none java super class
        val maxPolymorphismDepth = SettingsArguments.maxPolyDepth(project)

        val psiFile: PsiFile = e.dataContext.getData(CommonDataKeys.PSI_FILE)!!
        val caret: Caret = e.dataContext.getData(CommonDataKeys.CARET)?.caretModel?.primaryCaret!!
        val cutPsiClass: PsiClass = getSurroundingClass(psiFile, caret)

        var currentPsiClass = cutPsiClass
        for (index in 0 until maxPolymorphismDepth) {
            if (!classesToTest.contains(currentPsiClass)) {
                classesToTest.add(currentPsiClass)
            }

            if (currentPsiClass.superClass == null ||
                currentPsiClass.superClass!!.qualifiedName == null ||
                currentPsiClass.superClass!!.qualifiedName!!.startsWith("java.")
            ) {
                break
            }
            currentPsiClass = currentPsiClass.superClass!!
        }

        var prompt: String
        while (true) {
            prompt = when (codeType.type!!) {
                CodeType.CLASS -> PromptManager(project, classesToTest[0], classesToTest).generatePromptForClass()
                CodeType.METHOD ->
                    PromptManager(project, classesToTest[0], classesToTest).generatePromptForMethod(codeType.objectDescription)

                CodeType.LINE -> PromptManager(project, classesToTest[0], classesToTest).generatePromptForLine(codeType.objectIndex)
            }

            // Too big prompt processing
            if (!isPromptLengthWithinLimit(prompt)) {
                // depth of polygons reducing
                if (SettingsArguments.maxPolyDepth(project) > 1) {
                    project.service<Workspace>().testGenerationData.polyDepthReducing++
                    log.info("poly depth is: ${SettingsArguments.maxPolyDepth(project)}")
                    continue
                }

                // depth of input params reducing
                if (SettingsArguments.maxInputParamsDepth(project) > 1) {
                    project.service<Workspace>().testGenerationData.inputParamsDepthReducing++
                    log.info("input params depth is: ${SettingsArguments.maxPolyDepth(project)}")
                    continue
                }
            }
            break
        }

        if ((project.service<Workspace>().testGenerationData.polyDepthReducing != 0 || project.service<Workspace>().testGenerationData.inputParamsDepthReducing != 0) &&
            isPromptLengthWithinLimit(prompt)
        ) {
            llmErrorManager.warningProcess(
                TestGenieBundle.message("promptReduction") + "\n" +
                    "Maximum depth of polygons is ${SettingsArguments.maxPolyDepth(project)}.\n" +
                    "Maximum depth for input parameters is ${SettingsArguments.maxInputParamsDepth(project)}.",
                project,
            )
        }

        log.info("Prompt is:\n$prompt")

        return LLMProcessManager(project, prompt)
    }

    /**
     * Checks if the token is set.
     *
     * @param project The project for error processing.
     *
     * @return True if the token is set, false otherwise.
     */
    private fun isCorrectToken(project: Project): Boolean {
        if (!SettingsArguments.isTokenSet()) {
            llmErrorManager.errorProcess(TestGenieBundle.message("missingToken"), project)
            return false
        }
        return true
    }

    /**
     * Generates tests for a given class.
     *
     * @param e the AnActionEvent object containing information about the action event
     * @throws IllegalArgumentException if the project in the AnActionEvent object is null
     */
    override fun generateTestsForClass(e: AnActionEvent) {
        if (!isCorrectToken(e.project!!)) return
        val codeType = FragmentToTestDada(CodeType.CLASS)
        createLLMPipeline(e).runTestGeneration(getLLMProcessManager(e, codeType), codeType)
    }

    /**
     * Generates tests for a given method.
     *
     * @param e The AnActionEvent that triggered the method generation.
     * @throws IllegalStateException if the project or the surrounding method is null.
     */
    override fun generateTestsForMethod(e: AnActionEvent) {
        if (!isCorrectToken(e.project!!)) return
        val psiFile: PsiFile = e.dataContext.getData(CommonDataKeys.PSI_FILE)!!
        val caret: Caret = e.dataContext.getData(CommonDataKeys.CARET)?.caretModel?.primaryCaret!!
        val psiMethod: PsiMethod = getSurroundingMethod(psiFile, caret)!!
        val codeType = FragmentToTestDada(CodeType.METHOD, generateMethodDescriptor(psiMethod))
        createLLMPipeline(e).runTestGeneration(getLLMProcessManager(e, codeType), codeType)
    }

    /**
     * Generates tests for a specific line of code.
     *
     * @param e The AnActionEvent that triggered the generation of tests.
     */
    override fun generateTestsForLine(e: AnActionEvent) {
        if (!isCorrectToken(e.project!!)) return
        val psiFile: PsiFile = e.dataContext.getData(CommonDataKeys.PSI_FILE)!!
        val caret: Caret = e.dataContext.getData(CommonDataKeys.CARET)?.caretModel?.primaryCaret!!
        val selectedLine: Int = getSurroundingLine(psiFile, caret)?.plus(1)!!
        val codeType = FragmentToTestDada(CodeType.LINE, selectedLine)
        createLLMPipeline(e).runTestGeneration(getLLMProcessManager(e, codeType), codeType)
    }
}
