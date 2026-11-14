package dev.st33ng.mculivewatch.session

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.st33ng.mculivewatch.ui.LiveExpressionPanel

/**
 * The regression this pins: detaching the view must not touch the session.
 *
 * Before 3.0.6 the panel owned the capture and tore it down in `removeNotify()`, which IntelliJ
 * calls when a tool-window tab is merely deselected (verified in the platform source:
 * `ToolWindowContentUi.ensureSelectedContentVisible()` does `contentComponent.removeAll()` from
 * `selectionChanged`). The user saw value updates stop after switching to the Graph tab and had to
 * restart the IDE.
 *
 * A platform test with a real tool window is not possible here — the test framework substitutes
 * `ToolWindowHeadlessManagerImpl$MockToolWindow`, whose component is a bare `JLabel`, so no content
 * ever becomes displayable. The AWT half of the same call pattern is covered by
 * `ToolWindowContentSwapTest`.
 */
class LiveSessionViewDetachTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Without this the fixture's tearDown fails with "Code insight settings damaged" on every
        // test in this project (CLion's live CodeInsightSettings differ from a fresh instance).
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance())
    }

    fun `test detaching the panel leaves the session untouched`() {
        val session = LiveSessionService.getInstance(project)
        val panel = LiveExpressionPanel(project)
        // The platform's light fixture can share one project between test classes, so the status
        // label may already have been touched by an earlier test. The property under test is that
        // attaching/detaching changes nothing -- not that the initial text is a particular string.
        val statusBefore = session.status

        // Simulate the platform: attach, then detach because another tab was selected.
        panel.addNotify()
        panel.removeNotify()

        assertFalse("detaching a view must not start or stop anything", session.isRunning)
        assertEquals("status must be untouched by view detach", statusBefore, session.status)
    }

    fun `test the session is one instance per project`() {
        assertSame(
            LiveSessionService.getInstance(project),
            LiveSessionService.getInstance(project),
        )
    }
}
