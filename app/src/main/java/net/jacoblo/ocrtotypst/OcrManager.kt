package net.jacoblo.ocrtotypst

import android.graphics.Bitmap
import android.os.Environment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object OcrManager {
    fun recognizeText(bitmap: Bitmap, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        // 1) Use Latin options which covers English
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // 2) Process structured text to preserve layout and add styling
                val typstText = processToTypst(visionText)
                onSuccess(typstText)
            }
            .addOnFailureListener { e ->
                onFailure(e)
            }
    }

    private fun processToTypst(text: Text): String {
        val sb = StringBuilder()
        
        // Collect all lines to calculate median height for Bold heuristic
        val allLines = text.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return ""
        
        // Heuristic: Text significantly larger than median is treated as Bold (Headings)
        val heights = allLines.mapNotNull { it.boundingBox?.height() }.sorted()
        val medianHeight = if (heights.isNotEmpty()) heights[heights.size / 2] else 0
        val boldThreshold = (medianHeight * 1.25).toInt()

        // Sort blocks by vertical position to ensure correct reading order
        val blocks = text.textBlocks.sortedBy { it.boundingBox?.top ?: 0 }

        for (block in blocks) {
            // Sort lines within the block
            val lines = block.lines.sortedBy { it.boundingBox?.top ?: 0 }
            
            for (line in lines) {
                val lineBox = line.boundingBox
                val blockBox = block.boundingBox
                
                if (lineBox != null && blockBox != null && line.text.isNotEmpty()) {
                    // Calculate indentation based on horizontal position relative to block
                    val charWidth = lineBox.width().toFloat() / line.text.length
                    // Avoid division by zero or negative width
                    if (charWidth > 0) {
                        val indentPixels = (lineBox.left - blockBox.left).coerceAtLeast(0)
                        val spacesCount = (indentPixels / charWidth).toInt()
                        sb.append(" ".repeat(spacesCount))
                    }
                    
                    // Styling logic
                    var lineText = line.text
                    
                    // Apply Bold if line height exceeds threshold
                    if (lineBox.height() > boldThreshold) {
                        lineText = "*$lineText*"
                    }
                    
                    // ML Kit does not support italic/underline detection natively.
                    // Placeholder logic if heuristics become available:
                    // if (isItalic) lineText = "_${lineText}_"
                    // if (isUnderline) lineText = "#underline[${lineText}]"
                    
                    sb.append(lineText)
                } else {
                    sb.append(line.text)
                }
                sb.append("\n")
            }
            sb.append("\n\n") // Typst paragraph break
        }
        return sb.toString()
    }

    fun saveTextToFile(text: String): Boolean {
        val path = File(Environment.getExternalStorageDirectory(), "Notes")
        if (!path.exists()) {
            if (!path.mkdirs()) return false
        }
        // 3) Save as .typ file
        val file = File(path, "ocr.typ")
        return try {
            FileOutputStream(file, true).use { stream -> 
                stream.write((text + "\n\n").toByteArray())
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }
}