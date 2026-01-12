package net.jacoblo.notepad

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.StringReader
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

object TextFormatter {

    fun isSupported(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("html", "htm", "xml", "json", "jsonl", "md", "markdown")
    }

    fun format(fileName: String, content: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return try {
            when (ext) {
                "json" -> formatJson(content)
                "jsonl" -> formatJsonL(content)
                "xml" -> formatXml(content)
                "html", "htm" -> formatHtml(content)
                "md", "markdown" -> formatMarkdown(content)
                else -> content // Should be guarded by isSupported
            }
        } catch (e: Exception) {
            // If formatting fails (e.g. invalid syntax), return original or rethrow
            // For a Notepad app, keeping original with a toast (handled by caller) is better, 
            // but here we just throw or return original. Caller should catch.
            throw e
        }
    }

    private fun formatJson(content: String): String {
        val trimmed = content.trim()
        return if (trimmed.startsWith("{")) {
            JSONObject(trimmed).toString(2)
        } else if (trimmed.startsWith("[")) {
            JSONArray(trimmed).toString(2)
        } else {
            content
        }
    }

    private fun formatJsonL(content: String): String {
        return content.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                try {
                    formatJson(line)
                } catch (e: Exception) {
                    line
                }
            }
            .joinToString("\n")
    }

    private fun formatXml(content: String): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        
        val source = StreamSource(StringReader(content))
        val writer = StringWriter()
        val result = StreamResult(writer)
        transformer.transform(source, result)
        return writer.toString()
    }

    private fun formatHtml(content: String): String {
        // Requires org.jsoup:jsoup dependency
        val doc = Jsoup.parse(content)
        doc.outputSettings().indentAmount(2)
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.html)
        return doc.html()
    }

    private fun formatMarkdown(content: String): String {
        // Basic Markdown formatting: 
        // 1. Ensure empty lines around headers
        // 2. Ensure lists are indented (rudimentary)
        // 3. Trim trailing whitespace
        val sb = StringBuilder()
        val lines = content.lines()
        var previousLineWasEmpty = false
        
        for (i in lines.indices) {
            var line = lines[i].trimEnd()
            
            // Heuristic: Add empty line before headers if missing
            if (line.startsWith("#")) {
                if (i > 0 && !previousLineWasEmpty) {
                    sb.append("\n")
                }
            }
            
            sb.append(line).append("\n")
            previousLineWasEmpty = line.isEmpty()
        }
        return sb.toString().trimEnd()
    }
}
