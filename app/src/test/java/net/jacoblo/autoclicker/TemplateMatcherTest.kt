package net.jacoblo.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic frames with a patch planted at a known position.
 *
 * The first version of the matcher passed a casual on-device check while
 * actually reporting the wrong location: it point-sampled the coarse pass on a
 * grid, so any true position not sitting on that grid scored badly while blank
 * regions scored well, and blank regions won. These tests pin the position, not
 * just "something matched".
 */
class TemplateMatcherTest {

    private class FrameBuilder(val width: Int, val height: Int) {
        val pixels = ByteArray(width * height * 4)

        fun fill(r: Int, g: Int, b: Int) {
            for (i in 0 until width * height) {
                pixels[i * 4] = r.toByte()
                pixels[i * 4 + 1] = g.toByte()
                pixels[i * 4 + 2] = b.toByte()
                pixels[i * 4 + 3] = 255.toByte()
            }
        }

        fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
            val i = (y * width + x) * 4
            pixels[i] = r.toByte()
            pixels[i + 1] = g.toByte()
            pixels[i + 2] = b.toByte()
            pixels[i + 3] = 255.toByte()
        }

        fun build() = ScreenCapture.Frame(width, height, pixels)
    }

    /** A patch with enough structure that position actually matters. */
    private fun patternedTemplate(width: Int, height: Int, seed: Int): TemplateMatcher.Template {
        val random = Random(seed)
        val pixels = IntArray(width * height) {
            (0xFF shl 24) or (random.nextInt(256) shl 16) or (random.nextInt(256) shl 8) or random.nextInt(256)
        }
        return TemplateMatcher.Template(width, height, pixels)
    }

    private fun stamp(frame: FrameBuilder, template: TemplateMatcher.Template, atX: Int, atY: Int) {
        for (y in 0 until template.height) {
            for (x in 0 until template.width) {
                val p = template.pixels[y * template.width + x]
                frame.put(atX + x, atY + y, (p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            }
        }
    }

    @Test
    fun findsThePatchAtItsExactPosition() {
        val builder = FrameBuilder(400, 600)
        builder.fill(250, 250, 250)
        val template = patternedTemplate(60, 40, seed = 1)
        stamp(builder, template, 137, 251)

        val match = TemplateMatcher.find(builder.build(), template, threshold = 0.95f)

        assertNotNull("expected to find the planted patch", match)
        assertEquals(137, match!!.x)
        assertEquals(251, match.y)
        assertTrue("similarity should be near perfect, was ${match.similarity}", match.similarity > 0.99f)
    }

    /**
     * The position that broke the first implementation: not a multiple of the
     * coarse step, so a grid-aligned search misses it.
     */
    @Test
    fun findsThePatchAtAnAwkwardOffset() {
        val builder = FrameBuilder(400, 600)
        builder.fill(250, 250, 250)
        val template = patternedTemplate(60, 40, seed = 2)
        stamp(builder, template, 41, 143)

        val match = TemplateMatcher.find(builder.build(), template, threshold = 0.95f)

        assertNotNull(match)
        assertEquals(41, match!!.x)
        assertEquals(143, match.y)
    }

    @Test
    fun reportsNoMatchWhenThePatchIsAbsent() {
        val builder = FrameBuilder(400, 600)
        builder.fill(250, 250, 250)
        val template = patternedTemplate(60, 40, seed = 3)

        assertNull(TemplateMatcher.find(builder.build(), template, threshold = 0.95f))
    }

    /**
     * A largely blank template must not latch onto arbitrary blank screen: the
     * failure mode that reported a hit up in the status bar.
     */
    @Test
    fun prefersTheRealPositionOverBlankLookalikeRegions() {
        val builder = FrameBuilder(400, 600)
        builder.fill(250, 250, 250)

        // Mostly flat, with a small distinctive mark, like a UI row.
        val width = 80
        val height = 30
        val pixels = IntArray(width * height) { (0xFF shl 24) or 0xFAFAFA }
        for (x in 10 until 40) pixels[12 * width + x] = (0xFF shl 24) or 0x101010
        val template = TemplateMatcher.Template(width, height, pixels)

        stamp(builder, template, 220, 400)

        val match = TemplateMatcher.find(builder.build(), template, threshold = 0.95f)

        assertNotNull(match)
        assertEquals(220, match!!.x)
        assertEquals(400, match.y)
    }

    @Test
    fun toleratesSmallNoiseButRejectsHeavyChange() {
        val builder = FrameBuilder(300, 300)
        builder.fill(200, 200, 200)
        val template = patternedTemplate(50, 50, seed = 4)
        stamp(builder, template, 100, 100)

        // Nudge every channel slightly, as compression or a subtle theme shift would.
        val noisy = TemplateMatcher.Template(
            template.width,
            template.height,
            IntArray(template.pixels.size) { i ->
                val p = template.pixels[i]
                val r = (((p shr 16) and 0xFF) + 3).coerceAtMost(255)
                val g = (((p shr 8) and 0xFF) + 3).coerceAtMost(255)
                val b = ((p and 0xFF) + 3).coerceAtMost(255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        )
        assertNotNull(TemplateMatcher.find(builder.build(), noisy, threshold = 0.95f))

        val different = patternedTemplate(50, 50, seed = 99)
        assertNull(TemplateMatcher.find(builder.build(), different, threshold = 0.95f))
    }

    @Test
    fun regionOfInterestExcludesMatchesOutsideIt() {
        val builder = FrameBuilder(400, 600)
        builder.fill(250, 250, 250)
        val template = patternedTemplate(40, 40, seed = 5)
        stamp(builder, template, 300, 500)
        val frame = builder.build()

        assertNotNull(TemplateMatcher.find(frame, template, 0.95f))
        assertNull(
            "a region of interest that excludes the patch must not match",
            TemplateMatcher.find(frame, template, 0.95f, android.graphics.Rect(0, 0, 200, 200))
        )
    }

    /**
     * The app-drawer case: a screen full of short dark captions on white, one of
     * which is the saved area. Every caption reduces to nearly the same handful
     * of grey cells, so this is where a too-coarse reduction stopped being able
     * to tell them apart and reported nothing at all.
     */
    @Test
    fun findsOneCaptionAmongManySimilarOnes() {
        val builder = FrameBuilder(1080, 1920)
        builder.fill(255, 255, 255)

        val width = 178
        val height = 38
        // Off-grid on purpose: the position the device actually failed at.
        val targetX = 457
        val targetY = 833

        fun caption(seed: Int): TemplateMatcher.Template {
            val random = Random(seed)
            val pixels = IntArray(width * height) { (0xFF shl 24) or 0xFFFFFF }
            var x = 4 + random.nextInt(10)
            while (x < width - 12) {
                val strokeWidth = 3 + random.nextInt(5)
                val top = 8 + random.nextInt(6)
                val bottom = height - 8 - random.nextInt(6)
                for (y in top until bottom) {
                    for (dx in 0 until strokeWidth) pixels[y * width + x + dx] = (0xFF shl 24) or 0x202020
                }
                x += strokeWidth + 3 + random.nextInt(7)
            }
            return TemplateMatcher.Template(width, height, pixels)
        }

        // A grid of decoys on the same rows and columns a launcher would use.
        var seed = 100
        for (row in 0 until 8) {
            for (column in 0 until 5) {
                val x = 40 + column * 200
                val y = 120 + row * 220
                if (x + width >= 1080 || y + height >= 1920) continue
                stamp(builder, caption(seed++), x, y)
            }
        }

        val target = caption(7)
        stamp(builder, target, targetX, targetY)

        val match = TemplateMatcher.find(builder.build(), target, threshold = 0.90f)

        assertNotNull("the saved caption must be found among the lookalikes", match)
        assertEquals(targetX, match!!.x)
        assertEquals(targetY, match.y)
    }

    @Test
    fun templateLargerThanTheFrameIsRejected() {
        val builder = FrameBuilder(50, 50)
        builder.fill(0, 0, 0)
        assertNull(TemplateMatcher.find(builder.build(), patternedTemplate(80, 80, seed = 6), 0.9f))
    }
}
