package nl.tudelft.ewi.se.ciselab.testgenie.listener

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import nl.tudelft.ewi.se.ciselab.testgenie.editor.Workspace
import nl.tudelft.ewi.se.ciselab.testgenie.evosuite.TestGenerationResultListener
import org.evosuite.utils.CompactReport

class TestGenerationResultListenerImpl(private val project: Project) : TestGenerationResultListener {
    private val log = Logger.getInstance(this.javaClass)

    override fun testGenerationResult(testReport: CompactReport, resultName: String) {
        log.info("Received test result for $resultName")
        val workspace = project.service<Workspace>()
        println(testReport.testScaffoldCode)

        ApplicationManager.getApplication().invokeLater {
            workspace.receiveGenerationResult(resultName, testReport)
        }
    }
}
