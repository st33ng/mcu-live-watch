package dev.st33ng.mculivewatch.storage

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project-scoped owner of [LiveExpressionTable].
 *
 * Previously the table lived in a global `object`, so two open projects shared one JTable and one
 * sample history. Scoping it to the project removes that cross-talk.
 */
@Service(Service.Level.PROJECT)
class LiveExpressionsService {

    val table: LiveExpressionTable = LiveExpressionTable()

    companion object {
        fun getInstance(project: Project): LiveExpressionsService = project.service()
    }
}
