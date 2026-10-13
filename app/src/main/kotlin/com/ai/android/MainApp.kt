package com.ai.android

import android.app.Application
import com.ai.android.agent.AgentCore
import com.ai.android.agent.PluginGuardBridge
import com.ai.android.agent.ToolRegistry
import com.ai.android.agent.tools.TaskStore
import com.ai.android.i18n.I18nManager
import com.ai.android.plugin.JsPluginRuntime
import com.ai.android.plugin.PluginRegistry
import com.ai.android.plugin.SkillManager
import com.ai.android.provider.ProviderManager
import com.ai.android.service.FloatingService
import com.ai.android.service.terminal.TerminalForegroundService
import com.ai.android.storage.AppDatabase
import com.ai.android.storage.MemoryStore
import com.ai.android.storage.ProjectStore
import com.ai.android.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val providerManager: ProviderManager by lazy { ProviderManager() }
    val settings: SettingsRepository by lazy { SettingsRepository(this, providerManager) }
    val memory: MemoryStore by lazy { MemoryStore(this) }
    val toolRegistry: ToolRegistry by lazy { ToolRegistry(this, settings) }
    val projectStore: ProjectStore by lazy { ProjectStore(database) }

    val agentCore: AgentCore by lazy {
        AgentCore(
            providers = providerManager,
            registry = toolRegistry,
            config = { settings.agentConfig() },
            memoryPromptProvider = { conv ->
                val parts = mutableListOf<String>()
                val global = memory.summaryBlock()
                if (global.isNotBlank()) parts.add(global)
                if (!conv.projectId.isBlank()) {
                    val proj = memory.projectSummaryBlock(conv.projectId)
                    if (proj.isNotBlank()) parts.add(proj)
                }
                parts.joinToString("\n")
            },
            // ⭐ Skill 清单注入
            skillsPromptProvider = {
                runCatching { SkillManager.summaryBlock(this) }.getOrDefault("")
            },
        )
    }

    val pluginRegistry: PluginRegistry by lazy {
        PluginRegistry(applicationContext).also { it.init() }
    }

    val jsPluginRuntime: JsPluginRuntime by lazy {
        JsPluginRuntime(applicationContext).also { rt ->
            rt.onRenderWidget = { pluginId, text, colorHex ->
                FloatingService.jsRenderWidgetHandler?.invoke(pluginId, text, colorHex)
            }
            rt.onSendToAgent = { text ->
                runCatching { AgentBridge.postMessage(text) }
            }
            PluginGuardBridge.jsAskCallback =
                { pluginId, guardName, call ->
                    jsPluginRuntime.askGuard(pluginId, guardName, call)
                }
        }
    }

    @Volatile var agentStopCallback: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        I18nManager.init(this)
        settings.loadIntoManager()
        com.ai.android.util.CrashHandler.install(this)

        toolRegistry.registerDefaultTools(memory)
        toolRegistry.agentCoreRef = agentCore
        toolRegistry.setAppScope(appScope)

        TaskStore.init(this)

        pluginRegistry
        jsPluginRuntime

        FloatingService.jsRenderWidgetHandler = { pluginId, text, colorHex ->
            runCatching { FloatingService.pushJsWidget(applicationContext, text, colorHex) }
        }

        jsPluginRuntime.onRenderJsBall = { pluginId, jsonStr ->
            runCatching { FloatingService.renderJsBall(applicationContext, pluginId, jsonStr) }
        }

        com.ai.android.service.ShizukuManager.initForApp(this)

        TerminalForegroundService.start(this)
    }

    companion object {
        lateinit var instance: MainApp
            private set
    }
}