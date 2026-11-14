package dev.st33ng.mculivewatch.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BorderLayout
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Pins the AWT half of the claim that a tool-window tab switch detaches the hidden panel.
 *
 * Why this test exists: [LiveSessionService] was introduced because the session used to be torn
 * down in a panel's `removeNotify()`, and IntelliJ calls that on a mere tab switch. The platform
 * half is verified against source (`ToolWindowContentUi.ensureSelectedContentVisible()` in
 * `platform/platform-impl`, branch 262: `contentComponent.removeAll(); contentComponent.add(new)`
 * on `selectionChanged`). This test verifies the AWT half of exactly that call pattern: that
 * removing a child from a displayable container really fires `removeNotify()` on it, and adding it
 * back fires `addNotify()` again.
 *
 * The container must be displayable — that is the trap. `removeNotify` is *not* called for children
 * of a container that was never shown, so a test without a shown window would "prove" the opposite
 * of reality.
 */
class ToolWindowContentSwapTest {

    private class TrackingPanel : JPanel() {
        var added = 0
        var removed = 0

        override fun addNotify() {
            added++
            super.addNotify()
        }

        override fun removeNotify() {
            removed++
            super.removeNotify()
        }
    }

    @Test
    fun `removing a child from a shown container fires removeNotify and re-adding fires addNotify`() {
        var failure: Throwable? = null

        SwingUtilities.invokeAndWait {
            val contentComponent = JPanel(BorderLayout())
            val first = TrackingPanel()
            val second = TrackingPanel()
            val frame = JFrame("live-expressions-swap-test")
            try {
                frame.contentPane.add(contentComponent, BorderLayout.CENTER)

                // Same sequence as ToolWindowContentUi.ensureSelectedContentVisible().
                contentComponent.add(first, BorderLayout.CENTER)
                frame.isVisible = true
                assertEquals("the frame must be displayable for addNotify to fire", 1, first.added)

                contentComponent.removeAll()
                contentComponent.add(second, BorderLayout.CENTER)

                assertEquals(
                    "the deselected panel must be detached — this is what killed the old session",
                    1,
                    first.removed,
                )
                assertEquals("the newly selected panel must be attached", 1, second.added)

                // And back again, which is what a tab switch back does.
                contentComponent.removeAll()
                contentComponent.add(first, BorderLayout.CENTER)
                assertEquals("re-selecting must attach the panel again", 2, first.added)
                assertTrue("and detach the other one", second.removed >= 1)
            } catch (t: Throwable) {
                failure = t
            } finally {
                frame.dispose()
            }
        }

        failure?.let { throw it }
    }
}
