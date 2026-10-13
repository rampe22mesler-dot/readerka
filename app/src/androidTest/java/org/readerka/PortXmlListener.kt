package org.readerka

import android.os.SystemClock
import android.util.AtomicFile
import android.util.Xml
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File

/** Durable diagnostics only; all assertions still run through the standard JUnit runner. */
class PortXmlListener : RunListener() {
    private data class Case(val description: Description, val start: Long = SystemClock.elapsedRealtime(),
        var finish: Long? = null, var failure: Failure? = null, var skipped: Boolean = false)
    private val cases = linkedMapOf<String, Case>()
    private var expected = 0
    private var complete = false
    override fun testRunStarted(description: Description) { expected = description.testCount(); write() }
    override fun testStarted(description: Description) { cases[description.toString()] = Case(description); write() }
    override fun testFailure(failure: Failure) { cases.getOrPut(failure.description.toString()) { Case(failure.description) }.failure = failure; write() }
    override fun testAssumptionFailure(failure: Failure) { cases.getOrPut(failure.description.toString()) { Case(failure.description) }.skipped = true; write() }
    override fun testIgnored(description: Description) { cases[description.toString()] = Case(description, skipped = true, finish = SystemClock.elapsedRealtime()); write() }
    override fun testFinished(description: Description) { cases[description.toString()]?.finish = SystemClock.elapsedRealtime(); write() }
    override fun testRunFinished(result: Result) { complete = true; write() }
    private fun write() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "test-results").apply { mkdirs() }
        val target = AtomicFile(File(directory, "port-tests.xml"))
        val output = target.startWrite()
        try {
            val xml = Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true) }
            xml.startTag(null, "testsuite").attribute(null, "name", "Readerka Android port")
                .attribute(null, "complete", complete.toString()).attribute(null, "expected", expected.toString())
                .attribute(null, "tests", cases.values.count { it.finish != null }.toString())
                .attribute(null, "failures", cases.values.count { it.failure != null }.toString())
                .attribute(null, "errors", "0").attribute(null, "skipped", cases.values.count { it.skipped }.toString())
            cases.values.forEach { case ->
                xml.startTag(null, "testcase").attribute(null, "classname", case.description.className.orEmpty())
                    .attribute(null, "name", case.description.methodName ?: case.description.displayName)
                    .attribute(null, "finished", (case.finish != null).toString())
                    .attribute(null, "time", ((case.finish ?: SystemClock.elapsedRealtime()) - case.start).div(1000.0).toString())
                case.failure?.let { xml.startTag(null, "failure").attribute(null, "message", it.message.orEmpty()).text(it.trace).endTag(null, "failure") }
                if (case.skipped) xml.startTag(null, "skipped").endTag(null, "skipped")
                xml.endTag(null, "testcase")
            }
            xml.endTag(null, "testsuite").endDocument()
            xml.flush(); target.finishWrite(output)
        } catch (error: Exception) { target.failWrite(output); throw error }
    }
}
