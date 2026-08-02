package net.jacoblo.autoclicker

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Worst possible per-pixel difference across three channels.
private const val MAX_PIXEL_DIFF = 255 * 3

// A downscaled score is blurrier than the real one, so a true match sits a
// little below its full-resolution similarity.
private const val COARSE_SLACK = 0.12f

// Refining every plausible offset would cost more than the coarse pass saved.
private const val MAX_CANDIDATES = 8

// Roughly how many template pixels each full-resolution pass compares. Scoring
// every pixel of a large area is what made an early version take ~4.9s.
private const val REFINE_SAMPLES = 1200
private const val FINAL_SAMPLES = 30000

// The placing pass already checks every offset in its window, so the scoring
// pass only has to cover rounding.
private const val FINAL_RADIUS = 2

// Target width of the downscaled search space. Smaller is faster but blurs
// small areas away entirely.
private const val COARSE_WIDTH = 120

/**
 * Finds a saved region anywhere on screen.
 *
 * A full search is (W-w)*(H-h) offsets, far too many to score directly, so the
 * work happens on an averaged downscale first and only the best few offsets are
 * re-scored at full resolution.
 *
 * The coarse pass must *average* rather than point-sample. Point sampling on a
 * grid looked much cheaper, but an offset that misses the true position by a
 * few pixels then scores terribly for detailed content and barely changes for
 * flat content, so blank regions outranked the real match and the real match
 * never reached the candidate list. Averaging makes the coarse score tolerant
 * of sub-block misalignment, which is the whole point of the pass.
 */
object TemplateMatcher {

	class Template(val width: Int, val height: Int, val pixels: IntArray) {
		companion object {
			fun fromBitmap(bitmap: Bitmap): Template {
				val pixels = IntArray(bitmap.width * bitmap.height)
				bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
				return Template(bitmap.width, bitmap.height, pixels)
			}
		}
	}

	data class Match(val x: Int, val y: Int, val similarity: Float)

	/** Averaged downscale; each entry is packed 0xRRGGBB. */
	private class Reduced(val width: Int, val height: Int, val rgb: IntArray)

	/**
	 * [threshold] is the similarity required, 0..1. [roi] limits where the
	 * top-left corner may land.
	 */
	fun find(
		frame: ScreenCapture.Frame,
		template: Template,
		threshold: Float,
		roi: Rect? = null
	): Match? {
		if (template.width <= 0 || template.height <= 0) return null
		if (template.width > frame.width || template.height > frame.height) return null

		val minX = max(0, roi?.left ?: 0)
		val minY = max(0, roi?.top ?: 0)
		val maxX = min(frame.width - template.width, (roi?.right ?: frame.width) - template.width)
		val maxY = min(frame.height - template.height, (roi?.bottom ?: frame.height) - template.height)
		if (maxX < minX || maxY < minY) return null

		val factor = reduceFactor(frame, template)
		val smallFrame = reduceFrame(frame, factor)
		val smallTemplate = reduceTemplate(template, factor)
		if (smallTemplate.width == 0 || smallTemplate.height == 0) return null

		val candidates = coarsePass(
			smallFrame, smallTemplate, threshold - COARSE_SLACK, factor, minX, minY, maxX, maxY
		)
		if (candidates.isEmpty()) return null

		val refineStep = sampleStep(template, REFINE_SAMPLES)
		val finalStep = sampleStep(template, FINAL_SAMPLES)

		var best: Match? = null
		for ((cx, cy) in candidates) {
			val placed = bestInWindow(
				frame, template, cx, cy,
				radius = factor, step = refineStep,
				minX = minX, minY = minY, maxX = maxX, maxY = maxY,
				floor = threshold - COARSE_SLACK
			) ?: continue

			val scored = bestInWindow(
				frame, template, placed.x, placed.y,
				radius = FINAL_RADIUS, step = finalStep,
				minX = minX, minY = minY, maxX = maxX, maxY = maxY,
				floor = threshold
			) ?: continue

			if (scored.similarity >= threshold && (best == null || scored.similarity > best.similarity)) {
				best = scored
			}
		}
		return best
	}

	private fun reduceFactor(frame: ScreenCapture.Frame, template: Template): Int {
		val byWidth = frame.width / COARSE_WIDTH
		// Keep at least a few cells across the template, or it blurs to nothing.
		val byTemplate = min(template.width, template.height) / 4
		return max(1, min(byWidth, byTemplate))
	}

	/**
	 * Averages each factor-sized block. Only a 2x2 sub-sample of each block is
	 * read: enough to smooth out misalignment without touching every pixel of
	 * an 8 megabyte frame.
	 */
	private fun reduceFrame(frame: ScreenCapture.Frame, factor: Int): Reduced {
		val width = frame.width / factor
		val height = frame.height / factor
		val rgb = IntArray(width * height)
		val half = max(1, factor / 2)

		for (y in 0 until height) {
			for (x in 0 until width) {
				var r = 0
				var g = 0
				var b = 0
				var n = 0
				var dy = 0
				while (dy < factor) {
					var dx = 0
					while (dx < factor) {
						val i = ((y * factor + dy) * frame.width + (x * factor + dx)) * 4
						r += frame.pixels[i].toInt() and 0xFF
						g += frame.pixels[i + 1].toInt() and 0xFF
						b += frame.pixels[i + 2].toInt() and 0xFF
						n++
						dx += half
					}
					dy += half
				}
				rgb[y * width + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
			}
		}
		return Reduced(width, height, rgb)
	}

	private fun reduceTemplate(template: Template, factor: Int): Reduced {
		val width = template.width / factor
		val height = template.height / factor
		val rgb = IntArray(max(0, width * height))
		val half = max(1, factor / 2)

		for (y in 0 until height) {
			for (x in 0 until width) {
				var r = 0
				var g = 0
				var b = 0
				var n = 0
				var dy = 0
				while (dy < factor) {
					var dx = 0
					while (dx < factor) {
						val p = template.pixels[(y * factor + dy) * template.width + (x * factor + dx)]
						r += (p shr 16) and 0xFF
						g += (p shr 8) and 0xFF
						b += p and 0xFF
						n++
						dx += half
					}
					dy += half
				}
				rgb[y * width + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
			}
		}
		return Reduced(width, height, rgb)
	}

	/** Every offset in the reduced space, mapped back to full-resolution. */
	private fun coarsePass(
		frame: Reduced,
		template: Reduced,
		floor: Float,
		factor: Int,
		minX: Int,
		minY: Int,
		maxX: Int,
		maxY: Int
	): List<Pair<Int, Int>> {
		val samples = template.width * template.height
		if (samples == 0) return emptyList()
		val budget = ((1f - floor) * samples * MAX_PIXEL_DIFF).toInt()

		val scored = mutableListOf<Triple<Int, Int, Float>>()
		val lastX = min(frame.width - template.width, maxX / factor)
		val lastY = min(frame.height - template.height, maxY / factor)

		for (y in (minY / factor)..lastY) {
			for (x in (minX / factor)..lastX) {
				var total = 0
				var aborted = false
				loop@ for (ty in 0 until template.height) {
					val frameRow = (y + ty) * frame.width + x
					val templateRow = ty * template.width
					for (tx in 0 until template.width) {
						val f = frame.rgb[frameRow + tx]
						val t = template.rgb[templateRow + tx]
						total += abs(((f shr 16) and 0xFF) - ((t shr 16) and 0xFF)) +
							abs(((f shr 8) and 0xFF) - ((t shr 8) and 0xFF)) +
							abs((f and 0xFF) - (t and 0xFF))
						if (total > budget) {
							aborted = true
							break@loop
						}
					}
				}
				if (!aborted) {
					val similarity = 1f - total.toFloat() / (samples * MAX_PIXEL_DIFF)
					scored.add(Triple(x * factor, y * factor, similarity))
				}
			}
		}

		return scored.sortedByDescending { it.third }
			.take(MAX_CANDIDATES)
			.map { it.first to it.second }
	}

	private fun bestInWindow(
		frame: ScreenCapture.Frame,
		template: Template,
		centreX: Int,
		centreY: Int,
		radius: Int,
		step: Int,
		minX: Int,
		minY: Int,
		maxX: Int,
		maxY: Int,
		floor: Float
	): Match? {
		var best: Match? = null
		for (y in max(minY, centreY - radius)..min(maxY, centreY + radius)) {
			for (x in max(minX, centreX - radius)..min(maxX, centreX + radius)) {
				val score = similarityAt(frame, template, x, y, step, floor)
				if (score > 0f && (best == null || score > best.similarity)) {
					best = Match(x, y, score)
				}
			}
		}
		return best
	}

	/**
	 * Spacing that samples about [target] pixels of the template, so cost per
	 * offset stays flat no matter how large the saved area is.
	 */
	private fun sampleStep(template: Template, target: Int): Int {
		if (template.width.toLong() * template.height <= target) return 1
		var step = 1
		while ((template.width / (step + 1)) * (template.height / (step + 1)) > target) step++
		return step.coerceAtMost(min(template.width, template.height).coerceAtLeast(1))
	}

	/**
	 * Mean similarity over every [step]-th pixel, or zero as soon as the running
	 * difference makes reaching [floor] impossible.
	 */
	private fun similarityAt(
		frame: ScreenCapture.Frame,
		template: Template,
		originX: Int,
		originY: Int,
		step: Int,
		floor: Float
	): Float {
		var samples = 0
		var ty = 0
		while (ty < template.height) {
			var tx = 0
			while (tx < template.width) {
				samples++
				tx += step
			}
			ty += step
		}
		if (samples == 0) return 0f

		val budget = ((1f - floor) * samples * MAX_PIXEL_DIFF).toInt()
		var total = 0

		ty = 0
		while (ty < template.height) {
			val frameRow = (originY + ty) * frame.width
			val templateRow = ty * template.width
			var tx = 0
			while (tx < template.width) {
				val fi = (frameRow + originX + tx) * 4
				val t = template.pixels[templateRow + tx]
				total += abs((frame.pixels[fi].toInt() and 0xFF) - ((t shr 16) and 0xFF)) +
					abs((frame.pixels[fi + 1].toInt() and 0xFF) - ((t shr 8) and 0xFF)) +
					abs((frame.pixels[fi + 2].toInt() and 0xFF) - (t and 0xFF))
				if (total > budget) return 0f
				tx += step
			}
			ty += step
		}

		return 1f - total.toFloat() / (samples * MAX_PIXEL_DIFF)
	}
}
