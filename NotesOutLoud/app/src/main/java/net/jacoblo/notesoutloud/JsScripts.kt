package net.jacoblo.notesoutloud

object JsScripts {

    val DEFAULT_BLANKING_SCRIPT = """
window.AndroidBlanker = {
    _getTextElements: function() {
        const tags = new Set(['P','H1','H2','H3','H4','H5','H6']);
        const found = Array.from(document.querySelectorAll('p, h1, h2, h3, h4, h5, h6'));
        const foundSet = new Set(found);
        // Walk all elements looking for ones with direct text nodes
        // that aren't already captured and aren't inside a captured element
        document.querySelectorAll('div, span, li, td, th, dd, dt, blockquote, label, a, em, strong, b, i, section, article').forEach(el => {
            if (foundSet.has(el)) return;
            // Skip if this element is inside an already-found element
            let ancestor = el.parentElement;
            let insideFound = false;
            while (ancestor) {
                if (foundSet.has(ancestor)) { insideFound = true; break; }
                ancestor = ancestor.parentElement;
            }
            if (insideFound) return;
            // Check if it has direct text children with real content
            let hasDirectText = false;
            for (let child of el.childNodes) {
                if (child.nodeType === 3 && child.nodeValue.trim().length > 0) {
                    hasDirectText = true;
                    break;
                }
            }
            if (hasDirectText) {
                found.push(el);
                foundSet.add(el);
            }
        });
        // Sort by document order
        found.sort((a, b) => a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1);
        return found;
    },
    toggle: function(enable, percentage) {
        const targets = this._getTextElements();
        targets.forEach(el => {
            if (enable) {
                if (el.dataset.originalHtml) {
                    el.innerHTML = el.dataset.originalHtml;
                } else {
                    el.dataset.originalHtml = el.innerHTML;
                }
                const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null, false);
                let node;
                const nodes = [];
                while(node = walker.nextNode()) nodes.push(node);
                nodes.forEach(n => {
                    const text = n.nodeValue;
                    const words = text.split(' ');
                    const newWords = words.map(w => {
                        if (!w.trim()) return w;
                        if (Math.random() * 100 < percentage) {
                            return '_'.repeat(w.length);
                        }
                        return w;
                    });
                    n.nodeValue = newWords.join(' ');
                });
            } else {
                if (el.dataset.originalHtml) {
                    el.innerHTML = el.dataset.originalHtml;
                    delete el.dataset.originalHtml;
                }
            }
        });
    }
};
    """.trimIndent()

    // Unified TTS + TOC script.
    // Extracts all visible text from the page, chunks it for TTS,
    // and sends the chunk list to Android as the TOC/list view.
    val TTS_HELPER_SCRIPT = """
window.AndroidTtsHelper = {
    chunks: [],
    MAX_LEN: 3000,
    MIN_LEN: 20,

    init: function() {
        var segs = this._extractSegments();
        this.chunks = this._buildChunks(segs);
        this._assignIds();
        this._sendToc();
    },

    // -- Text extraction --
    // Walk every text node in the body, group by nearest block ancestor.
    // This captures <p>, <div> with raw text, <li>, <td>, headings, etc.
    _nearestBlock: function(el) {
        var B = {DIV:1,P:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,LI:1,TD:1,TH:1,
            BLOCKQUOTE:1,PRE:1,ARTICLE:1,SECTION:1,ASIDE:1,HEADER:1,FOOTER:1,
            MAIN:1,DD:1,DT:1,FIGCAPTION:1,BODY:1};
        while (el && !B[el.tagName]) el = el.parentElement;
        return el || document.body;
    },

    _isVisible: function(el) {
        if (!el || el === document.body) return true;
        var s = getComputedStyle(el);
        return s.display !== 'none' && s.visibility !== 'hidden';
    },

    _extractSegments: function() {
        var self = this;
        var segments = [];
        var skip = {SCRIPT:1, STYLE:1, NOSCRIPT:1, SVG:1, CANVAS:1};

        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ALL, {
            acceptNode: function(node) {
                if (node.nodeType === 1) {
                    if (skip[node.tagName]) return NodeFilter.FILTER_REJECT;
                    if (node.tagName === 'BR') return NodeFilter.FILTER_ACCEPT;
                    return NodeFilter.FILTER_SKIP;
                }
                if (node.nodeType === 3) {
                    var p = node.parentElement;
                    if (!p) return NodeFilter.FILTER_REJECT;
                    if (skip[p.tagName]) return NodeFilter.FILTER_REJECT;
                    if (node.nodeValue.trim().length === 0) return NodeFilter.FILTER_REJECT;
                    return NodeFilter.FILTER_ACCEPT;
                }
                return NodeFilter.FILTER_REJECT;
            }
        });

        var node;
        var curBlock = null;
        var curText = '';

        while (node = walker.nextNode()) {
            if (node.nodeType === 1) {
                if (curText && curBlock && self._isVisible(curBlock)) {
                    segments.push({text: curText.trim(), element: curBlock});
                }
                curText = '';
                continue;
            }
            var block = self._nearestBlock(node.parentElement);
            if (block === curBlock && curText) {
                curText += ' ' + node.nodeValue.trim();
            } else {
                if (curText && curBlock && self._isVisible(curBlock)) {
                    segments.push({text: curText.trim(), element: curBlock});
                }
                curBlock = block;
                curText = node.nodeValue.trim();
            }
        }
        if (curText && curBlock && self._isVisible(curBlock)) {
            segments.push({text: curText.trim(), element: curBlock});
        }
        return segments;
    },

    // -- Chunking: merge short segments, split long ones --
    _splitToFit: function(text, max) {
        if (text.length <= max) return [text];
        var parts = [];
        var remaining = text;
        while (remaining.length > max) {
            var cut = remaining.substring(0, max);
            // Try sentence boundary
            var pos = Math.max(
                cut.lastIndexOf('. '),
                cut.lastIndexOf('! '),
                cut.lastIndexOf('? '),
                cut.lastIndexOf('\n')
            );
            if (pos > max * 0.3) {
                parts.push(remaining.substring(0, pos + 1).trim());
                remaining = remaining.substring(pos + 1).trim();
            } else {
                // Fall back to word boundary
                var sp = cut.lastIndexOf(' ');
                if (sp > max * 0.3) {
                    parts.push(remaining.substring(0, sp).trim());
                    remaining = remaining.substring(sp).trim();
                } else {
                    parts.push(cut.trim());
                    remaining = remaining.substring(max).trim();
                }
            }
        }
        if (remaining.trim()) parts.push(remaining.trim());
        return parts;
    },

    _buildChunks: function(segments) {
        var MAX = this.MAX_LEN;
        var MIN = this.MIN_LEN;
        var result = [];
        var buf = '';
        var bufEls = [];
        var self = this;

        function flush() {
            if (buf) {
                result.push({text: buf, elements: bufEls.slice()});
                buf = '';
                bufEls = [];
            }
        }

        for (var i = 0; i < segments.length; i++) {
            var text = segments[i].text;
            var el = segments[i].element;

            // Oversized segment: flush buffer, then split
            if (text.length > MAX) {
                flush();
                var parts = self._splitToFit(text, MAX);
                for (var j = 0; j < parts.length; j++) {
                    result.push({text: parts[j], elements: [el]});
                }
                continue;
            }

            // Try appending to buffer
            var combined = buf ? buf + ' ' + text : text;
            if (combined.length <= MAX) {
                buf = combined;
                bufEls.push(el);
            } else {
                // Would overflow: flush then start new buffer
                flush();
                buf = text;
                bufEls = [el];
            }

            // Flush if buffer is big enough, unless next segment is short and fits
            if (buf.length >= MIN) {
                var next = segments[i + 1];
                if (!next || next.text.length >= MIN || buf.length + 1 + next.text.length > MAX) {
                    flush();
                }
            }
        }
        flush();
        return result;
    },

    // -- ID assignment and TOC push --
    _assignIds: function() {
        for (var i = 0; i < this.chunks.length; i++) {
            var el = this.chunks[i].elements[0];
            if (el && !el.id) {
                el.id = 'android_chunk_' + i;
            }
        }
    },

    _sendToc: function() {
        if (!window.AndroidToc) return;
        var toc = [];
        for (var i = 0; i < this.chunks.length; i++) {
            toc.push({
                id: '' + i,
                text: this.chunks[i].text,
                level: 1
            });
        }
        window.AndroidToc.updateToc(JSON.stringify(toc));
    },

    // -- Public API (same interface as before) --
    getCount: function() { return this.chunks.length; },

    getParaText: function(index) {
        if (index < 0 || index >= this.chunks.length) return null;
        return this.chunks[index].text;
    },

    highlight: function(index) {
        document.querySelectorAll('.ext-tts-highlight').forEach(function(e) {
            e.classList.remove('ext-tts-highlight');
        });
        if (index >= 0 && index < this.chunks.length) {
            var chunk = this.chunks[index];
            for (var i = 0; i < chunk.elements.length; i++) {
                chunk.elements[i].classList.add('ext-tts-highlight');
            }
            if (chunk.elements[0]) {
                chunk.elements[0].scrollIntoView({behavior: 'smooth', block: 'center'});
            }
        }
    },

    getParaIndexAfter: function(elementId) {
        var el = document.getElementById(elementId);
        if (!el) return -1;
        for (var i = 0; i < this.chunks.length; i++) {
            for (var j = 0; j < this.chunks[i].elements.length; j++) {
                if (this.chunks[i].elements[j] === el) return i;
            }
        }
        return -1;
    }
};
(function(){
    var style = document.createElement('style');
    style.textContent = ".ext-tts-highlight { outline: 3px solid #4285F4 !important; background-color: rgba(66, 133, 244, 0.05) !important; transition: all 0.3s ease-in-out; }";
    document.head.appendChild(style);
    window.AndroidTtsHelper.init();
})();
    """.trimIndent()

    fun darkModeCss(): String {
        return """
            html, body { background:#121212 !important; color:#e0e0e0 !important; }
            body * { color: inherit; }
            a { color:#8ab4f8 !important; }
            pre, code { background:#1e1e1e !important; }
            input, textarea, select { background:#1e1e1e !important; color:#e0e0e0 !important; border:1px solid #333 !important; }
            img, video { filter: brightness(0.9) contrast(1.05); }
            #ext-toc-container { background:rgba(32,32,32,0.92) !important; color:#e0e0e0 !important; border-color:#333 !important; }
            #ext-toc-container a { color:#e0e0e0 !important; }
            #ext-toc-container a:hover { background:rgba(255,255,255,0.08) !important; }
            #ext-toc-container a[style*='font-weight: 700'] { background:rgba(138,180,248,0.25) !important; }
        """.trimIndent().replace("\n", " ")
    }

    fun darkModeToggleScript(enable: Boolean): String {
        val css = darkModeCss()
        return """
            (function() {
                var styleId = 'android-dark-mode-style';
                var style = document.getElementById(styleId);
                if ($enable) {
                    if (!style) {
                        style = document.createElement('style');
                        style.id = styleId;
                        style.textContent = `$css`;
                        document.head.appendChild(style);
                    }
                } else {
                    if (style) {
                        style.remove();
                    }
                }
            })();
        """.trimIndent()
    }
}
