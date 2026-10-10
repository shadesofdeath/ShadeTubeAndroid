package com.app.shadetube.youtube.explode

/**
 * Minimal XML element tree (sufficient for DASH manifests). Namespace prefixes are stripped from element and
 * attribute names. Port of YoutubeExplode Utils/Xml.
 */
internal class XmlNode(
    val isText: Boolean = false,
    val name: String = "",
    var text: String = "",
) {
    val attributes = ArrayList<Pair<String, String>>()
    val children = ArrayList<XmlNode>()

    fun attribute(attributeName: String): String? = attributes.firstOrNull { it.first == attributeName }?.second

    /** Concatenated text of all descendant text nodes (like casting an XElement to string). */
    fun innerText(): String = StringBuilder().also { appendText(this, it) }.toString()

    /** Direct child elements with the given name. */
    fun elements(elementName: String): List<XmlNode> = children.filter { !it.isText && it.name == elementName }
    fun element(elementName: String): XmlNode? = children.firstOrNull { !it.isText && it.name == elementName }

    /** All descendant elements with the given name, in document order. */
    fun descendants(elementName: String): List<XmlNode> =
        ArrayList<XmlNode>().also { collectDescendants(this, elementName, it) }

    private companion object {
        fun appendText(node: XmlNode, out: StringBuilder) {
            for (c in node.children) if (c.isText) out.append(c.text) else appendText(c, out)
        }

        fun collectDescendants(node: XmlNode, name: String, out: MutableList<XmlNode>) {
            for (c in node.children) {
                if (c.isText) continue
                if (c.name == name) out.add(c)
                collectDescendants(c, name, out)
            }
        }
    }
}

internal object Xml {
    /** Parses a document and returns its root element. Throws YoutubeExplodeException on malformed input. */
    fun parse(source: String): XmlNode = Parser(source).parseDocument()

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseDocument(): XmlNode {
            skipProlog()
            if (pos >= s.length || s[pos] != '<') fail("expected root element")
            return parseElement()
        }

        private fun fail(what: String): Nothing =
            throw YoutubeExplodeException("Failed to parse XML: $what at offset $pos.")

        private fun stripPrefix(name: String): String {
            val colon = name.indexOf(':')
            return if (colon < 0) name else name.substring(colon + 1)
        }

        private fun lookingAt(token: String) = s.startsWith(token, pos)

        private fun skipUntil(terminator: String) {
            val end = s.indexOf(terminator, pos)
            if (end < 0) fail("unterminated construct")
            pos = end + terminator.length
        }

        private fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) ++pos
        }

        private fun skipProlog() {
            if (s.startsWith("\uFEFF")) pos = 1
            while (true) {
                skipWhitespace()
                if (lookingAt("<?")) skipUntil("?>")
                else if (lookingAt("<!--")) skipUntil("-->")
                else if (lookingAt("<!")) skipUntil(">")
                else break
            }
        }

        private fun readName(): String {
            val start = pos
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/' || c == '>' || c == '=') break
                ++pos
            }
            if (pos == start) fail("expected name")
            return s.substring(start, pos)
        }

        private fun parseElement(): XmlNode {
            ++pos // '<'
            val node = XmlNode(name = stripPrefix(readName()))
            while (true) {
                skipWhitespace()
                if (pos >= s.length) fail("unexpected end of input in tag")
                if (lookingAt("/>")) {
                    pos += 2
                    return node
                }
                if (s[pos] == '>') {
                    ++pos
                    break
                }
                val attrName = readName()
                skipWhitespace()
                if (pos >= s.length || s[pos] != '=') fail("expected '=' after attribute name")
                ++pos
                skipWhitespace()
                if (pos >= s.length || (s[pos] != '"' && s[pos] != '\'')) fail("expected quoted attribute value")
                val quote = s[pos++]
                val end = s.indexOf(quote, pos)
                if (end < 0) fail("unterminated attribute value")
                node.attributes.add(stripPrefix(attrName) to Str.htmlDecode(s.substring(pos, end)))
                pos = end + 1
            }
            while (true) {
                if (pos >= s.length) fail("unexpected end of input in element content")
                if (lookingAt("</")) {
                    pos += 2
                    readName()
                    skipWhitespace()
                    if (pos >= s.length || s[pos] != '>') fail("malformed closing tag")
                    ++pos
                    return node
                }
                if (lookingAt("<!--")) {
                    skipUntil("-->")
                } else if (lookingAt("<![CDATA[")) {
                    pos += 9
                    val end = s.indexOf("]]>", pos)
                    if (end < 0) fail("unterminated CDATA")
                    addText(node, s.substring(pos, end))
                    pos = end + 3
                } else if (lookingAt("<?")) {
                    skipUntil("?>")
                } else if (s[pos] == '<') {
                    node.children.add(parseElement())
                } else {
                    var end = s.indexOf('<', pos)
                    if (end < 0) end = s.length
                    addText(node, Str.htmlDecode(s.substring(pos, end)))
                    pos = end
                }
            }
        }

        private fun addText(parent: XmlNode, text: String) {
            val last = parent.children.lastOrNull()
            if (last != null && last.isText) {
                last.text += text
                return
            }
            parent.children.add(XmlNode(isText = true, text = text))
        }
    }
}
