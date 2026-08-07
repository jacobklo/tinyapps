package net.jacoblo.autoclicker

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Worst possible per-pixel difference across three channels.
private const val MAX_PIXEL_DIFF = 255 * 3

// A downscaled score is blurrier than the real one, so a true match sits a
// little below its full-resolution similarity.
private const val COARSE_SLACK = 0.12f

// Refining every plausible offset would cost more than the coarse pass saved.
private const val MAX_CANDIDATES = 20

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

// Cells the reduced template keeps across its shorter side. Four left a 38px
// tall area as four rows, too few to tell one word from another, and it ranked
// 9th on a screen full of similar labels.
private const val MIN_COARSE_CELLS = 6

/**
 * Finds a saved region anywhere on screen.
 *
 * A full search is (W-w)*(H-h) offsets, far too many to score directly, so the
 * work happens on an averaged downscale first and only the best few offsets are
 * re-scored at full resolution.
 *
 * Three things decide whether the real match survives the coarse pass, and all
 * three were learned by getting them wrong:
 *
 * 1. The reduction must average *every* pixel of a block. Point sampling on a
 *    grid looked much cheaper, but an offset that misses the true position by a
 *    few pixels then scores terribly for detailed content and barely changes for
 *    flat content, so blank regions outranked the real match. Sampling a subset
 *    of each block is the same failure in milder form.
 * 2. Blocks must stay small enough to be distinctive. Coarse scores bunch up --
 *    a blank stretch of a white screen scored 0.934 against a word, and the word
 *    itself only 0.933 -- so once the template blurs to a handful of cells the
 *    ranking is close to arbitrary.
 * 3. Candidates must be spread out. The best offset and its neighbours all score
 *    alike, so an unsuppressed top-N spent the whole refine budget on one place.
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

	/** [threshold] is the similarity required, 0..1. */
	fun find(
		frame: ScreenCapture.Frame,
		template: Template,
		threshold: Float
	): Match? {
		if (template.width <= 0 || template.height <= 0) return null
		if (template.width > frame.width || template.height > frame.height) return null

		val maxX = frame.width - template.width
		val maxY = frame.height - template.height

		val factor = reduceFactor(frame, template)
		val smallFrame = reduceFrame(frame, factor)
		val smallTemplate = reduceTemplate(template, factor)
		if (smallTemplate.width == 0 || smallTemplate.height == 0) return null

		val candidates = coarsePass(
			smallFrame, smallTemplate, threshold - COARSE_SLACK, factor, maxX, maxY
		)
		if (candidates.isEmpty()) return null

		val refineStep = sampleStep(template, REFINE_SAMPLES)
		val finalStep = sampleStep(template, FINAL_SAMPLES)

		var best: Match? = null
		for ((cx, cy) in candidates) {
			val placed = bestInWindow(
				frame, template, cx, cy,
				radius = factor, step = refineStep,
				maxX = maxX, maxY = maxY,
				floor = threshold - COARSE_SLACK
			) ?: continue

			val scored = bestInWindow(
				frame, template, placed.x, placed.y,
				radius = FINAL_RADIUS, step = finalStep,
				maxX = maxX, maxY = maxY,
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
		// Keep enough cells across the template, or it blurs into every other
		// area of roughly the same brightness.
		val byTemplate = min(template.width, template.height) / MIN_COARSE_CELLS
		return max(1, min(byWidth, byTemplate))
	}

	/**
	 * Mean of each factor-sized block.
	 *
	 * This reads every pixel of the frame exactly once, which sounds expensive
	 * for 8 megabytes but is the only part of the search whose cost does not
	 * depend on the factor, and a partial sample of each block ranks a real
	 * match no better than chance.
	 */
	private fun reduceFrame(frame: ScreenCapture.Frame, factor: Int): Reduced {
		val width = frame.width / factor
		val height = frame.height / factor
		val rgb = IntArray(width * height)
		val n = factor * factor

		for (y in 0 until height) {
			for (x in 0 until width) {
				var r = 0
				var g = 0
				var b = 0
				for (dy in 0 until factor) {
					var i = ((y * factor + dy) * frame.width + x * factor) * 4
					for (dx in 0 until factor) {
						r += frame.pixels[i].toInt() and 0xFF
						g += frame.pixels[i + 1].toInt() and 0xFF
						b += frame.pixels[i + 2].toInt() and 0xFF
						i += 4
					}
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
		val n = factor * factor

		for (y in 0 until height) {
			for (x in 0 until width) {
				var r = 0
				var g = 0
				var b = 0
				for (dy in 0 until factor) {
					var i = (y * factor + dy) * template.width + x * factor
					for (dx in 0 until factor) {
						val p = template.pixels[i]
						r += (p shr 16) and 0xFF
						g += (p shr 8) and 0xFF
						b += p and 0xFF
						i++
					}
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
		maxX: Int,
		maxY: Int
	): List<Pair<Int, Int>> {
		val samples = template.width * template.height
		if (samples == 0) return emptyList()
		val budget = ((1f - floor) * samples * MAX_PIXEL_DIFF).toInt()

		val lastX = min(frame.width - template.width, maxX / factor)
		val lastY = min(frame.height - template.height, maxY / factor)
		if (lastX < 0 || lastY < 0) return emptyList()

		val cols = lastX + 1
		val rows = lastY + 1
		val scores = FloatArray(cols * rows)

		for (y in 0..lastY) {
			for (x in 0..lastX) {
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
				scores[y * cols + x] =
					if (aborted) 0f else 1f - total.toFloat() / (samples * MAX_PIXEL_DIFF)
			}
		}

		return peaks(scores, cols, rows, template.width, template.height)
			.map { (x, y) -> x * factor to y * factor }
	}

	/**
	 * Best offsets, at most one per template-sized neighbourhood.
	 *
	 * An offset and its neighbours score almost identically, so a plain top-N
	 * would hand the refine stage several views of the same place and leave the
	 * rest of the screen unexamined.
	 */
	private fun peaks(scores: FloatArray, cols: Int, rows: Int, spanX: Int, spanY: Int): List<Pair<Int, Int>> {
		val found = mutableListOf<Pair<Int, Int>>()
		repeat(MAX_CANDIDATES) {
			var bestIndex = -1
			var bestScore = 0f
			for (i in scores.indices) {
				if (scores[i] > bestScore) {
					bestScore = scores[i]
					bestIndex = i
				}
			}
			if (bestIndex < 0) return found

			val x = bestIndex % cols
			val y = bestIndex / cols
			found.add(x to y)
			for (sy in max(0, y - spanY + 1)..min(rows - 1, y + spanY - 1)) {
				for (sx in max(0, x - spanX + 1)..min(cols - 1, x + spanX - 1)) {
					scores[sy * cols + sx] = 0f
				}
			}
		}
		return found
	}

	private fun bestInWindow(
		frame: ScreenCapture.Frame,
		template: Template,
		centreX: Int,
		centreY: Int,
		radius: Int,
		step: Int,
		maxX: Int,
		maxY: Int,
		floor: Float
	): Match? {
		var best: Match? = null
		for (y in max(0, centreY - radius)..min(maxY, centreY + radius)) {
			for (x in max(0, centreX - radius)..min(maxX, centreX + radius)) {
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
