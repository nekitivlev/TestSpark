package org.jetbrains.research.testspark.tools.llm.generation

import com.gitlab.mvysny.konsumexml.konsumeXml
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import org.jetbrains.research.testspark.TestSparkBundle
import org.jetbrains.research.testspark.data.Report
import org.jetbrains.research.testspark.data.TestCase
import org.jetbrains.research.testspark.editor.Workspace
import org.jetbrains.research.testspark.services.CommandLineService
import org.jetbrains.research.testspark.tools.llm.error.LLMErrorManager
import org.jetbrains.research.testspark.tools.llm.test.TestCaseGeneratedByLLM
import java.io.File

/**
 * The TestCoverageCollector class is responsible for collecting test coverage data and generating a report.
 *
 * @property indicator The progress indicator to display the current task progress.
 * @property project The project associated with the test generation.
 * @property classFQN The class under test's full qualified name.
 * @property resultPath The path to save the generated test coverage report.
 * @property generatedTestPaths The paths of the generated test files.
 * @property generatedTestFile The generated test file.
 * @property generatedTestPackage The package of the generated test file.
 * @property projectBuildPath The path to the project build directory.
 * @property testCases The list of test cases generated by the LLM.
 * @property fileNameFQN The fully qualified name of the file containing the class under test.
 */
class TestCoverageCollector(
    private val indicator: ProgressIndicator,
    private val project: Project,
    private val classFQN: String,
    private val resultPath: String,
    private val generatedTestPaths: List<String>,
    private val generatedTestFile: File,
    private val generatedTestPackage: String,
    private val projectBuildPath: String,
    private val testCases: MutableList<TestCaseGeneratedByLLM>,
    private val cutModule: Module,
    private val fileNameFQN: String,
) {
    private val log = Logger.getInstance(this::class.java)

    private val javaHomeDirectory = ProjectRootManager.getInstance(project).projectSdk!!.homeDirectory!!

    private val report = Report()

    /**
     * Executes Jacoco on the compiled test file and collects the Jacoco results.
     *
     * @return The normalized Jacoco report.
     */
    fun collect(): Report {
        log.info("Test collection begins")

        // run Jacoco on the compiled test file
        runJacoco()

        // collect the Jacoco results and return the report
        return report.normalized()
    }

    /**
     * Compiles the generated test file using the proper javac and returns a Pair
     * indicating whether the compilation was successful and any error message encountered during compilation.
     *
     * @return A Pair containing a boolean indicating whether the compilation was successful
     *         and a String containing any error message encountered during compilation.
     */
    fun compileTestCases() {
        for (index in generatedTestPaths.indices) {
            if (project.service<CommandLineService>().compileCode(generatedTestPaths[index], projectBuildPath).first) {
                project.service<Workspace>().testGenerationData.compilableTestCases.add(testCases[index])
            }
        }
    }

    /**
     * Runs Jacoco to collect code coverage data and generate reports.
     */
    private fun runJacoco() {
        indicator.text = TestSparkBundle.message("runningJacoco")

        log.info("Running jacoco")

        // Execute each test method separately
        for (testCase in testCases) {
            // name of .exec and .xml files
            val dataFileName = "${generatedTestFile.parentFile.absolutePath}/jacoco-${(List(20) { ('a'..'z').toList().random() }.joinToString(""))}"

            val testExecutionError = project.service<CommandLineService>().createXmlFromJacoco(
                generatedTestFile.name.split('.')[0],
                dataFileName,
                cutModule,
                classFQN,
                testCase.name,
                projectBuildPath,
                generatedTestPackage,
            )

            // check if XML report is produced
            if (!File("$dataFileName.xml").exists()) {
                LLMErrorManager().errorProcess("Something went wrong with generating Jacoco report.", project)
                return
            }
            log.info("xml file exists")

            // save data to TestGenerationResult
            saveData(testCase, collectLinesCoveredDuringException(testExecutionError), "$dataFileName.xml")
        }
    }

    /**
     * Collect lines covered during the exception happening.
     *
     * @param testExecutionError error output (including the thrown stack trace) during the test execution.
     * @return a set of lines that are covered in CUT during the exception happening.
     */
    private fun collectLinesCoveredDuringException(testExecutionError: String): Set<Int> {
        if (testExecutionError.isBlank()) {
            return emptySet()
        }

        val result = mutableSetOf<Int>()

        // get frames
        val frames = testExecutionError.split("\n\tat ").toMutableList()
        frames.removeFirst()

        frames.forEach { frame ->
            if (frame.contains(classFQN)) {
                val coveredLineNumber = frame.split(":")[1].replace(")", "").toIntOrNull()
                if (coveredLineNumber != null) {
                    result.add(coveredLineNumber)
                }
            }
        }

        return result
    }

    /**
     * Saves data of a given test case to a report.
     *
     * @param testCase The test case generated by LLM.
     * @param xmlFileName The XML file name to read data from.
     */
    private fun saveData(testCase: TestCaseGeneratedByLLM, linesCoveredDuringTheException: Set<Int>, xmlFileName: String) {
        indicator.text = TestSparkBundle.message("testCasesSaving")
        val setOfLines = mutableSetOf<Int>()
        var isCorrectSourceFile: Boolean
        File(xmlFileName).readText().konsumeXml().apply {
            children("report") {
                children("sessioninfo") {}
                children("package") {
                    children("class") {
                        children("method") {
                            children("counter") {}
                        }
                        children("counter") {}
                    }
                    children("sourcefile") {
                        isCorrectSourceFile = this.attributes.getValue("name") == fileNameFQN
                        children("line") {
                            if (isCorrectSourceFile && this.attributes.getValue("mi") == "0") {
                                setOfLines.add(this.attributes.getValue("nr").toInt())
                            }
                        }
                        children("counter") {}
                    }
                    children("counter") {}
                }
                children("counter") {}
            }
        }

        log.info("Test case saved:\n$testCase")

        // Add lines that Jacoco might have missed because of its limitation during the exception
        setOfLines.addAll(linesCoveredDuringTheException)

        report.testCaseList[testCase.name] = TestCase(
            testCase.name,
            testCase.toString(),
            setOfLines,
            setOf(),
            setOf(),
        )
    }
}
