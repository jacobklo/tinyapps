package net.jacoblo.ocrtotypst

import android.graphics.Bitmap
import android.os.Environment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object OcrManager {
    fun recognizeText(bitmap: Bitmap, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onSuccess(visionText.text)
            }
            .addOnFailureListener { e ->
                onFailure(e)
            }
    }

    fun saveTextToFile(text: String): Boolean {
        val path = File(Environment.getExternalStorageDirectory(), "Notes")
        if (!path.exists()) {
            if (!path.mkdirs()) return false
        }
        val file = File(path, "ocr.txt")
        return try {
            FileOutputStream(file, true).use { stream -> 
                stream.write((text + "\n").toByteArray())
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }
}