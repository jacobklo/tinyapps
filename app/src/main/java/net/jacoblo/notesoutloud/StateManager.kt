package net.jacoblo.notesoutloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class StateManager(private val context: Context) {

    private val sharedPref get() = (context as? android.app.Activity)?.getPreferences(Context.MODE_PRIVATE)

    fun save(
        tabs: List<BrowserTab>,
        userScripts: List<UserScript>,
        isDarkMode: Boolean,
        blankingScriptContent: String
    ) {
        val pref = sharedPref ?: return
        with(pref.edit()) {
            val tabsJson = JSONArray()
            tabs.forEach { tab ->
                tabsJson.put(tab.url.value)
            }
            putString("saved_tabs", tabsJson.toString())

            val scriptsJson = JSONArray()
            userScripts.forEach { script ->
                val scriptObj = JSONObject()
                scriptObj.put("url", script.url)
                scriptObj.put("content", script.content)
                scriptsJson.put(scriptObj)
            }
            putString("saved_scripts", scriptsJson.toString())

            putString("is_dark_mode", isDarkMode.toString())
            putString("blanking_script", blankingScriptContent)

            apply()
        }
    }

    data class SavedState(
        val tabUrls: List<String>,
        val scripts: List<UserScript>,
        val isDarkMode: Boolean,
        val blankingScript: String?
    )

    fun load(): SavedState {
        val pref = sharedPref ?: return SavedState(emptyList(), emptyList(), false, null)

        val scripts = mutableListOf<UserScript>()
        val savedScripts = pref.getString("saved_scripts", null)
        if (savedScripts != null) {
            try {
                val jsonArray = JSONArray(savedScripts)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    scripts.add(UserScript(obj.getString("url"), obj.getString("content")))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val tabUrls = mutableListOf<String>()
        val savedTabs = pref.getString("saved_tabs", null)
        if (savedTabs != null) {
            try {
                val jsonArray = JSONArray(savedTabs)
                for (i in 0 until jsonArray.length()) {
                    val url = jsonArray.getString(i)
                    if (url.isNotEmpty()) {
                        tabUrls.add(url)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val isDarkMode = pref.getString("is_dark_mode", "false").toBoolean()
        val blankingScript = pref.getString("blanking_script", null)

        return SavedState(tabUrls, scripts, isDarkMode, blankingScript)
    }
}
