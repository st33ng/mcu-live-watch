package com.jfrog.MyPlugin.clion.toolWindow

import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

class GraphPanel : JPanel(BorderLayout()){
    init {
        setupUI()
    }

    private fun setupUI() {
        val controlPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        add(controlPanel, BorderLayout.NORTH)
    }
}