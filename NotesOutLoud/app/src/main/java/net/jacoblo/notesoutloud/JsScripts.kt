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

    val TTS_HELPER_SCRIPT = """
window.AndroidTtsHelper = {
    paragraphs: [],
    _getTextElements: function() {
        const tags = new Set(['P','H1','H2','H3','H4','H5','H6']);
        const found = Array.from(document.querySelectorAll('p, h1, h2, h3, h4, h5, h6'));
        const foundSet = new Set(found);
        document.querySelectorAll('div, span, li, td, th, dd, dt, blockquote, label, a, em, strong, b, i, section, article').forEach(el => {
            if (foundSet.has(el)) return;
            let ancestor = el.parentElement;
            let insideFound = false;
            while (ancestor) {
                if (foundSet.has(ancestor)) { insideFound = true; break; }
                ancestor = ancestor.parentElement;
            }
            if (insideFound) return;
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
        found.sort((a, b) => a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1);
        return found;
    },
    init: function() {
        this.paragraphs = this._getTextElements()
            .filter(p => p.innerText.trim().length > 0);
    },
    getParaText: function(index) {
        if(index < 0 || index >= this.paragraphs.length) return null;
        return this.paragraphs[index].innerText;
    },
    highlight: function(index) {
         document.querySelectorAll('.ext-tts-highlight').forEach(e => e.classList.remove('ext-tts-highlight'));
         if(index >= 0 && index < this.paragraphs.length) {
             const el = this.paragraphs[index];
             el.classList.add('ext-tts-highlight');
             el.scrollIntoView({behavior: 'smooth', block: 'center'});
         }
    },
    getParaIndexAfter: function(elementId) {
        const target = document.getElementById(elementId);
        if(!target) return -1;
        return this.paragraphs.findIndex(p =>
            (target.compareDocumentPosition(p) & Node.DOCUMENT_POSITION_FOLLOWING)
        );
    },
    getCount: function() { return this.paragraphs.length; }
};
(function(){
    const style = document.createElement('style');
    style.textContent = ".ext-tts-highlight { outline: 3px solid #4285F4 !important; background-color: rgba(66, 133, 244, 0.05) !important; transition: all 0.3s ease-in-out; }";
    document.head.appendChild(style);
    window.AndroidTtsHelper.init();
})();
    """.trimIndent()

    val TOC_EXTRACTION_SCRIPT = """
(function() {
    var headings = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6, details > summary'));
    var toc = [];
    var idCounter = 0;
    var headingSet = new Set(headings);

    for (var i = 0; i < headings.length; i++) {
        var el = headings[i];
        if (!el.id) {
            el.id = 'android_toc_' + (idCounter++);
        }
        var level = 1;
        if (el.tagName.match(/^H\d$/)) {
            level = parseInt(el.tagName.substring(1));
        } else if (el.tagName === 'SUMMARY') {
            var current = el.parentElement;
            var depth = 0;
            while (current && current !== document.body) {
                if (current.tagName === 'DETAILS') depth++;
                current = current.parentElement;
            }
            level = Math.min(6, depth);
        }
        toc.push({id: el.id, text: el.innerText.trim(), level: level});
    }

    // Also capture elements with direct text that aren't inside any heading
    // (e.g. <div id="content">"some text"<br>"more text"</div>)
    // These appear as level 6 entries in the TOC
    document.querySelectorAll('div, section, article, blockquote, li, td, dd').forEach(function(el) {
        // Skip if it IS a heading or is inside one
        var ancestor = el;
        var insideHeading = false;
        while (ancestor) {
            if (headingSet.has(ancestor)) { insideHeading = true; break; }
            ancestor = ancestor.parentElement;
        }
        if (insideHeading) return;
        // Skip if it contains child headings (it's a wrapper, not a text block)
        if (el.querySelector('h1, h2, h3, h4, h5, h6')) return;
        // Check for direct text node children with real content
        var hasDirectText = false;
        for (var c = 0; c < el.childNodes.length; c++) {
            if (el.childNodes[c].nodeType === 3 && el.childNodes[c].nodeValue.trim().length > 0) {
                hasDirectText = true;
                break;
            }
        }
        if (!hasDirectText) return;
        var text = el.innerText.trim();
        if (text.length === 0 || text.length > 200) return;
        if (!el.id) {
            el.id = 'android_toc_' + (idCounter++);
        }
        toc.push({id: el.id, text: text, level: 6});
    });

    // Sort by document order
    toc.sort(function(a, b) {
        var elA = document.getElementById(a.id);
        var elB = document.getElementById(b.id);
        if (!elA || !elB) return 0;
        return (elA.compareDocumentPosition(elB) & Node.DOCUMENT_POSITION_FOLLOWING) ? -1 : 1;
    });

    if (window.AndroidToc) {
        window.AndroidToc.updateToc(JSON.stringify(toc));
    }
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
