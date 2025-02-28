package org.jetbrains.research.testspark.tools

import com.intellij.util.messages.Topic
import org.jetbrains.research.testspark.data.Report

val TEST_GENERATION_RESULT_TOPIC: Topic<TestGenerationResultListener> = Topic.create(
    "TEST_GENERATION_RESULT_TOPIC",
    TestGenerationResultListener::class.java,
    Topic.BroadcastDirection.TO_PARENT,
)

/**
 * Topic interface for sending and receiving test results produced by evosuite
 *
 * Subscribers to this topic will receive a Report whenever the plugin triggers a test
 * generation job with testspark.evosuite.Runner
 */
interface TestGenerationResultListener {
    fun testGenerationResult(testReport: Report, resultName: String, fileUrl: String)
}
