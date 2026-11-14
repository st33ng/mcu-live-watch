package dev.st33ng.mculivewatch.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.st33ng.mculivewatch.bridge.BundledPythonService

/**
 * Per-project configuration.
 *
 * Every field is optional: blanks mean "use the script bundled in the plugin and an interpreter
 * discovered automatically". The previous version had three absolute paths hardcoded in source,
 * two of which did not exist on this machine, so the bridge could never start.
 */
@Service(Service.Level.PROJECT)
@State(name = "McuLiveWatchSettings", storages = [Storage("mcu-live-watch.xml")])
class LiveExpressionSettings(private val project: Project) :
    PersistentStateComponent<LiveExpressionSettings.State> {

    data class State(
        /** Blank = autodetect. */
        var pythonExecutable: String = "",
        /** Blank = the script bundled in the plugin. */
        var bridgeScriptPath: String = "",
        /** Blank = the script bundled in the plugin. */
        var elfParserScriptPath: String = "",
        var elfFilePath: String = "",
        var bridgePort: Int = 12345,
        var defaultUpdateRateHz: Int = 1000,
        var demangle: Boolean = false,
        /**
         * Blank = nothing selected yet, and the bridge refuses to start. The part is never guessed
         * from the project: CubeMX spelling does not match SEGGER's, and reading registers from the
         * wrong MCU is worse than an error.
         */
        var deviceName: String = "",
        /** J-Link interface speed in kHz; 0 = use the probe maximum. */
        var jlinkSpeedKhz: Int = 0,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    val bridgePort: Int get() = myState.bridgePort
    val defaultUpdateRateHz: Int get() = myState.defaultUpdateRateHz
    val demangle: Boolean get() = myState.demangle
    val jlinkSpeedKhz: Int get() = myState.jlinkSpeedKhz

    /**
     * Device name handed to J-Link, exactly as chosen in the device picker.
     *
     * Empty means "not selected yet": the bridge refuses to start rather than connecting to a
     * guessed part, because reading registers from the wrong MCU is worse than an error message.
     */
    val deviceName: String get() = myState.deviceName.trim()

    /** Configured interpreter, or the one discovered for the bridge. */
    fun pythonForBridge(): String? {
        val configured = myState.pythonExecutable
        if (configured.isNotBlank()) return configured
        return BundledPythonService.getInstance(project).interpreterForBridge()?.path
    }

    /** Configured interpreter, or the one discovered for the parser. */
    fun pythonForParser(): String? {
        val configured = myState.pythonExecutable
        if (configured.isNotBlank()) return configured
        return BundledPythonService.getInstance(project).interpreterForParser()?.path
    }

    /** Configured bridge script, or the bundled one. */
    fun bridgeScript(): String {
        val configured = myState.bridgeScriptPath
        if (configured.isNotBlank()) return configured
        return BundledPythonService.getInstance(project).bridgeScript()
    }

    /** Configured parser script, or the bundled one. */
    fun parserScript(): String {
        val configured = myState.elfParserScriptPath
        if (configured.isNotBlank()) return configured
        return BundledPythonService.getInstance(project).parserScript()
    }

    fun setDemangle(value: Boolean) {
        myState.demangle = value
    }

    fun setElfFile(path: String?) {
        myState.elfFilePath = path.orEmpty()
    }

    fun setBridgeScript(path: String?) {
        myState.bridgeScriptPath = path.orEmpty()
    }

    fun setPythonExecutable(path: String?) {
        myState.pythonExecutable = path.orEmpty()
        BundledPythonService.getInstance(project).invalidate()
    }

    fun setElfParserScript(path: String?) {
        myState.elfParserScriptPath = path.orEmpty()
    }

    fun setDeviceName(name: String?) {
        myState.deviceName = name.orEmpty()
    }

    fun setJlinkSpeed(khz: Int) {
        myState.jlinkSpeedKhz = khz
    }

    companion object {
        fun getInstance(project: Project): LiveExpressionSettings = project.service()
    }
}
