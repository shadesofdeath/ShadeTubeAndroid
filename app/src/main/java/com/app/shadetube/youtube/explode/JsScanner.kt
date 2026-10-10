package com.app.shadetube.youtube.explode

/**
 * Tiny helpers for scanning (not executing) minified JavaScript (port of Bridge/Cipher/JsScanner). They understand
 * just enough syntax (strings, template literals, regex literals, comments) to find the boundaries of functions and
 * object literals inside YouTube's player. Indices are char offsets; "not found" is -1 (the C++ npos).
 */
internal object Js {
    const val NPOS = -1

    fun isIdentifierChar(c: Char) = Str.isAsciiAlnum(c) || c == '_' || c == '$'

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** Previous non-whitespace character before [index], or '\u0000'. */
    private fun previousSignificant(code: CharSequence, index: Int): Char {
        var i = index
        while (i > 0) {
            --i
            if (!isSpace(code[i])) return code[i]
        }
        return '\u0000'
    }

    /** Whether a '/' at [index] starts a regex literal (as opposed to a division operator). */
    private fun startsRegex(code: CharSequence, index: Int): Boolean {
        val prev = previousSignificant(code, index)
        if (prev == '\u0000') return true
        if ("(,=:[!&|?{};+-*%<>~^".indexOf(prev) >= 0) return true
        // Keywords that can precede an expression: return, typeof, case, void, in, of, delete...
        if (isIdentifierChar(prev)) {
            var end = index
            while (end > 0 && isSpace(code[end - 1])) --end
            var start = end
            while (start > 0 && isIdentifierChar(code[start - 1])) --start
            return when (code.subSequence(start, end).toString()) {
                "return", "typeof", "case", "void", "in", "of", "delete", "instanceof", "new", "throw", "else", "do",
                "yield", "await" -> true
                else -> false
            }
        }
        return false
    }

    private fun skipQuoted(code: CharSequence, i: Int, quote: Char): Int {
        var j = i + 1
        while (j < code.length) {
            if (code[j] == '\\') { j += 2; continue }
            if (code[j] == quote) return j
            if (code[j] == '\n' && quote != '`') return NPOS
            ++j
        }
        return NPOS
    }

    private fun skipTemplate(code: CharSequence, i: Int): Int {
        var j = i + 1
        while (j < code.length) {
            if (code[j] == '\\') { j += 2; continue }
            if (code[j] == '`') return j
            if (code[j] == '$' && j + 1 < code.length && code[j + 1] == '{') {
                val close = findMatchingBracket(code, j + 1)
                if (close == NPOS) return NPOS
                j = close
            }
            ++j
        }
        return NPOS
    }

    private fun skipRegex(code: CharSequence, i: Int): Int {
        var inClass = false
        var j = i + 1
        while (j < code.length) {
            val c = code[j]
            if (c == '\\') { j += 2; continue }
            if (c == '\n') return NPOS
            if (inClass) {
                if (c == ']') inClass = false
                ++j
                continue
            }
            if (c == '[') { inClass = true; ++j; continue }
            if (c == '/') {
                while (j + 1 < code.length && isIdentifierChar(code[j + 1])) ++j // flags
                return j
            }
            ++j
        }
        return NPOS
    }

    /**
     * Skips a literal/comment starting at [i] (if any). Returns the index of the last character of the literal, or [i]
     * unchanged if no literal starts there. Returns NPOS on unterminated input.
     */
    private fun skipLiteral(code: CharSequence, i: Int): Int {
        val c = code[i]
        if (c == '"' || c == '\'') return skipQuoted(code, i, c)
        if (c == '`') return skipTemplate(code, i)
        if (c == '/' && i + 1 < code.length) {
            if (code[i + 1] == '/') {
                val nl = indexOf(code, '\n', i)
                return if (nl < 0) code.length - 1 else nl
            }
            if (code[i + 1] == '*') {
                val end = indexOf(code, "*/", i + 2)
                return if (end < 0) NPOS else end + 1
            }
            if (startsRegex(code, i)) return skipRegex(code, i)
        }
        return i
    }

    private fun isLiteralStart(code: CharSequence, i: Int): Boolean {
        val c = code[i]
        return c == '"' || c == '\'' || c == '`' || c == '/'
    }

    private fun indexOf(code: CharSequence, ch: Char, from: Int): Int {
        for (k in maxOf(from, 0) until code.length) if (code[k] == ch) return k
        return NPOS
    }

    private fun indexOf(code: CharSequence, s: String, from: Int): Int =
        if (code is String) code.indexOf(s, from) else code.toString().indexOf(s, from)

    /**
     * Given the index of an opening '{', '(' or '[', returns the index of the matching closer, skipping over
     * string/template/regex literals and comments. Returns NPOS if unbalanced.
     */
    fun findMatchingBracket(code: CharSequence, openIndex: Int): Int {
        if (openIndex < 0 || openIndex >= code.length) return NPOS
        val stack = StringBuilder()
        var i = openIndex
        while (i < code.length) {
            val c = code[i]
            if (isLiteralStart(code, i)) {
                val end = skipLiteral(code, i)
                if (end == NPOS) return NPOS
                i = end + 1
                continue
            }
            if (c == '{' || c == '(' || c == '[') {
                stack.append(c)
            } else if (c == '}' || c == ')' || c == ']') {
                if (stack.isEmpty()) return NPOS
                val open = stack[stack.length - 1]
                if ((open == '{' && c != '}') || (open == '(' && c != ')') || (open == '[' && c != ']')) return NPOS
                stack.setLength(stack.length - 1)
                if (stack.isEmpty()) return i
            }
            ++i
        }
        return NPOS
    }

    /** Splits [code] on [separator] at nesting depth 0 (outside brackets and literals). */
    fun splitTopLevel(code: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (isLiteralStart(code, i)) {
                val end = skipLiteral(code, i)
                if (end == NPOS) break
                i = end + 1
                continue
            }
            if (c == '{' || c == '(' || c == '[') ++depth
            else if (c == '}' || c == ')' || c == ']') --depth
            else if (c == separator && depth == 0) {
                parts.add(code.substring(start, i))
                start = i + 1
            }
            ++i
        }
        parts.add(code.substring(start))
        return parts
    }

    /**
     * Parses a JS string literal starting at [index] (which must be a quote character). Handles standard escapes.
     * Returns the decoded value and the index just past the closing quote.
     */
    fun parseStringLiteralAt(code: String, index: Int): Pair<String, Int>? {
        if (index < 0 || index >= code.length) return null
        val quote = code[index]
        if (quote != '"' && quote != '\'' && quote != '`') return null
        val out = StringBuilder()
        fun hex(h: Char): Int = when (h) {
            in '0'..'9' -> h - '0'
            in 'a'..'f' -> h - 'a' + 10
            in 'A'..'F' -> h - 'A' + 10
            else -> -1
        }
        var i = index + 1
        while (i < code.length) {
            val c = code[i]
            if (c == quote) return out.toString() to (i + 1)
            if (c != '\\') {
                out.append(c)
                ++i
                continue
            }
            if (++i >= code.length) return null
            when (val e = code[i]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'v' -> out.append('\u000B')
                '0' -> out.append('\u0000')
                'x' -> {
                    if (i + 2 >= code.length) return null
                    val hi = hex(code[i + 1])
                    val lo = hex(code[i + 2])
                    if (hi < 0 || lo < 0) return null
                    out.appendCodePoint(hi * 16 + lo)
                    i += 2
                }
                'u' -> {
                    var cp = 0L
                    if (i + 1 < code.length && code[i + 1] == '{') {
                        val close = code.indexOf('}', i)
                        if (close < 0) return null
                        for (k in i + 2 until close) {
                            val v = hex(code[k])
                            if (v < 0) return null
                            cp = cp * 16 + v
                        }
                        i = close
                    } else {
                        if (i + 4 >= code.length) return null
                        for (k in 1..4) {
                            val v = hex(code[i + k])
                            if (v < 0) return null
                            cp = cp * 16 + v
                        }
                        i += 4
                        // Surrogate pair
                        if (cp in 0xD800..0xDBFF && i + 6 < code.length && code[i + 1] == '\\' && code[i + 2] == 'u') {
                            var low = 0L
                            var ok = true
                            for (k in 3..6) {
                                val v = hex(code[i + k])
                                if (v < 0) { ok = false; break }
                                low = low * 16 + v
                            }
                            if (ok && low in 0xDC00..0xDFFF) {
                                cp = 0x10000 + ((cp - 0xD800) shl 10) + (low - 0xDC00)
                                i += 6
                            }
                        }
                    }
                    if (cp > 0x10FFFF) return null
                    out.appendCodePoint(cp.toInt())
                }
                '\n' -> Unit // line continuation
                else -> out.append(e)
            }
            ++i
        }
        return null
    }

    fun parseStringLiteral(code: String, index: Int): String? = parseStringLiteralAt(code, index)?.first

    /** Encodes a value as a double-quoted JS string literal. */
    fun toStringLiteral(value: String): String {
        val hexDigits = "0123456789abcdef"
        val out = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) {
                    out.append("\\x").append(hexDigits[c.code shr 4]).append(hexDigits[c.code and 0xF])
                } else {
                    out.append(c)
                }
            }
        }
        return out.append('"').toString()
    }

    /**
     * Finds [name] as a whole identifier (not part of a longer identifier, not a property access like `.name`),
     * starting at [from]. Returns NPOS if not found.
     */
    fun findIdentifier(code: String, name: String, from: Int = 0): Int {
        var f = from
        while (true) {
            val pos = code.indexOf(name, f)
            if (pos < 0) return NPOS
            val leftOk = pos == 0 || (!isIdentifierChar(code[pos - 1]) && code[pos - 1] != '.')
            val rightOk = pos + name.length >= code.length || !isIdentifierChar(code[pos + name.length])
            if (leftOk && rightOk) return pos
            f = pos + 1
        }
    }

    /** A function located in the source. */
    class FunctionSpan(
        /** raw parameter list without parentheses */
        val params: String,
        /** raw body without the outer braces */
        var body: String,
        /** index of the definition start (the name or `function` keyword) */
        var begin: Int,
        /** index just past the closing brace */
        val end: Int,
    ) {
        /** "function(params){body}" */
        fun toExpression() = "function($params){$body}"
    }

    /** Parses the function expression starting at [index] ("function(...){...}"). */
    fun parseFunctionAt(code: String, index: Int): FunctionSpan? {
        if (index < 0 || !code.startsWith("function", index)) return null
        val open = code.indexOf('(', index)
        if (open < 0) return null
        val close = findMatchingBracket(code, open)
        if (close == NPOS) return null
        var brace = close + 1
        while (brace < code.length && isSpace(code[brace])) ++brace
        if (brace >= code.length || code[brace] != '{') return null
        val end = findMatchingBracket(code, brace)
        if (end == NPOS) return null
        return FunctionSpan(code.substring(open + 1, close), code.substring(brace + 1, end), index, end + 1)
    }

    /**
     * Locates the definition of a function named [name], in any of these forms:
     *   name=function(a){...}      var name=function(a){...}      function name(a){...}
     */
    fun findFunctionDefinition(code: String, name: String): FunctionSpan? {
        var from = 0
        while (true) {
            val pos = findIdentifier(code, name, from)
            if (pos == NPOS) return null
            from = pos + 1
            // function name(...){...}
            if (pos >= 9 && code.startsWith("function ", pos - 9)) {
                parseFunctionAt(code, pos - 9)?.let { return it }
                continue
            }
            // name=function(...){...}
            var k = pos + name.length
            while (k < code.length && isSpace(code[k])) ++k
            if (k >= code.length || code[k] != '=' || (k + 1 < code.length && code[k + 1] == '=')) continue
            ++k
            while (k < code.length && isSpace(code[k])) ++k
            parseFunctionAt(code, k)?.let {
                it.begin = pos
                return it
            }
        }
    }

    /**
     * Locates `name={...}` (object literal assignment, optionally preceded by var/let/const) and returns the literal
     * including braces.
     */
    fun findObjectLiteral(code: String, name: String): String? {
        var from = 0
        while (true) {
            val pos = findIdentifier(code, name, from)
            if (pos == NPOS) return null
            from = pos + 1
            var k = pos + name.length
            while (k < code.length && isSpace(code[k])) ++k
            if (k >= code.length || code[k] != '=' || (k + 1 < code.length && code[k + 1] == '=')) continue
            ++k
            while (k < code.length && isSpace(code[k])) ++k
            if (k >= code.length || code[k] != '{') continue
            val end = findMatchingBracket(code, k)
            if (end == NPOS) continue
            return code.substring(k, end + 1)
        }
    }

    /** Locates `name=[...]` and returns the element expressions (top-level split, trimmed). */
    fun findArrayLiteral(code: String, name: String): List<String>? {
        var from = 0
        while (true) {
            val pos = findIdentifier(code, name, from)
            if (pos == NPOS) return null
            from = pos + 1
            var k = pos + name.length
            while (k < code.length && isSpace(code[k])) ++k
            if (k >= code.length || code[k] != '=' || (k + 1 < code.length && code[k + 1] == '=')) continue
            ++k
            while (k < code.length && isSpace(code[k])) ++k
            if (k >= code.length || code[k] != '[') continue
            val end = findMatchingBracket(code, k)
            if (end == NPOS) continue
            return splitTopLevel(code.substring(k + 1, end), ',').map { Str.trim(it) }
        }
    }

    // ---- statements ----------------------------------------------------------------------------------------------

    private fun skipSpaceAndComments(code: String, start: Int): Int {
        var i = start
        while (i < code.length) {
            if (isSpace(code[i])) {
                ++i
            } else if (code.startsWith("//", i)) {
                val nl = code.indexOf('\n', i)
                i = if (nl < 0) code.length else nl + 1
            } else if (code.startsWith("/*", i)) {
                val end = code.indexOf("*/", i + 2)
                i = if (end < 0) code.length else end + 2
            } else {
                break
            }
        }
        return i
    }

    private fun wordAt(code: String, i: Int): String {
        if (i < 0 || i >= code.length) return ""
        var j = i
        while (j < code.length && isIdentifierChar(code[j])) ++j
        return code.substring(i, j)
    }

    /** Index just past a parenthesized group starting at the first '(' at/after [i]. */
    private fun afterParens(code: String, start: Int): Int {
        val i = skipSpaceAndComments(code, start)
        if (i >= code.length || code[i] != '(') return NPOS
        val close = findMatchingBracket(code, i)
        return if (close == NPOS) NPOS else close + 1
    }

    private fun afterBlock(code: String, start: Int): Int {
        val i = skipSpaceAndComments(code, start)
        if (i >= code.length || code[i] != '{') return NPOS
        val close = findMatchingBracket(code, i)
        return if (close == NPOS) NPOS else close + 1
    }

    private fun simpleStatementEnd(code: String, i: Int): Int {
        var depth = 0
        var k = i
        while (k < code.length) {
            val c = code[k]
            if (isLiteralStart(code, k)) {
                val end = skipLiteral(code, k)
                if (end == NPOS) return NPOS
                k = end + 1
                continue
            }
            if (c == '{' || c == '(' || c == '[') ++depth
            else if (c == '}' || c == ')' || c == ']') {
                if (depth == 0) return k // end of the enclosing block; statement had no ';'
                --depth
            } else if (c == ';' && depth == 0) {
                return k + 1
            }
            ++k
        }
        return code.length
    }

    /** Index just past the end of the statement starting at [start] (including its ';'). */
    private fun statementEnd(code: String, start: Int): Int {
        val i = skipSpaceAndComments(code, start)
        if (i >= code.length) return i
        if (code[i] == '{') return afterBlock(code, i)
        if (code[i] == ';') return i + 1

        val word = wordAt(code, i)
        val afterWord = i + word.length
        when (word) {
            "function" -> return parseFunctionAt(code, i)?.end ?: NPOS
            "if" -> {
                var k = afterParens(code, afterWord)
                if (k == NPOS) return k
                k = statementEnd(code, k)
                if (k == NPOS) return k
                val next = skipSpaceAndComments(code, k)
                if (wordAt(code, next) == "else") return statementEnd(code, next + 4)
                return k
            }
            "for", "while", "with" -> {
                val k = afterParens(code, afterWord)
                return if (k == NPOS) k else statementEnd(code, k)
            }
            "switch" -> {
                val k = afterParens(code, afterWord)
                return if (k == NPOS) k else afterBlock(code, k)
            }
            "do" -> {
                var k = statementEnd(code, afterWord)
                if (k == NPOS) return k
                k = skipSpaceAndComments(code, k)
                if (wordAt(code, k) != "while") return NPOS
                k = afterParens(code, k + 5)
                if (k == NPOS) return k
                val next = skipSpaceAndComments(code, k)
                return if (next < code.length && code[next] == ';') next + 1 else k
            }
            "try" -> {
                var k = afterBlock(code, afterWord)
                if (k == NPOS) return k
                var next = skipSpaceAndComments(code, k)
                if (wordAt(code, next) == "catch") {
                    var m = skipSpaceAndComments(code, next + 5)
                    if (m < code.length && code[m] == '(') m = afterParens(code, m)
                    if (m == NPOS) return m
                    k = afterBlock(code, m)
                    if (k == NPOS) return k
                    next = skipSpaceAndComments(code, k)
                }
                if (wordAt(code, next) == "finally") k = afterBlock(code, next + 7)
                return k
            }
        }
        // Labeled statement: `label: statement`
        if (word.isNotEmpty() && word != "var" && word != "let" && word != "const" && word != "return" &&
            word != "throw" && word != "new" && word != "typeof" && word != "void" && word != "delete"
        ) {
            val k = skipSpaceAndComments(code, afterWord)
            if (k < code.length && code[k] == ':' && (k + 1 >= code.length || code[k + 1] != ':'))
                return statementEnd(code, k + 1)
        }
        return simpleStatementEnd(code, i)
    }

    /**
     * Splits a sequence of statements (e.g. a function body) into top-level statements. Understands function
     * declarations and block statements (if/else, for, while, do, try, switch, bare blocks), which do not need a
     * terminating semicolon. Each returned statement excludes the trailing ';' and surrounding whitespace.
     */
    fun splitStatements(code: String): List<String> {
        val statements = ArrayList<String>()
        var i = 0
        while (true) {
            i = skipSpaceAndComments(code, i)
            if (i >= code.length) break
            if (code[i] == ';') {
                ++i
                continue
            }
            var end = statementEnd(code, i)
            if (end == NPOS || end <= i) end = code.length
            var e = end
            while (e > i && (code[e - 1] == ';' || isSpace(code[e - 1]))) --e
            if (e > i) statements.add(code.substring(i, e))
            i = end
        }
        return statements
    }

    /** Leading identifier/keyword of a statement ("" if it starts with punctuation). */
    fun leadingWord(statement: String) = wordAt(statement, 0)

    /**
     * Whether an expression statement is a plain assignment (`a.b=...`, `x+=...`), as opposed to a call, a sequence
     * expression or a logical expression.
     */
    fun isAssignmentStatement(statement: String): Boolean {
        var depth = 0
        var k = 0
        while (k < statement.length) {
            val c = statement[k]
            if (isLiteralStart(statement, k)) {
                val end = skipLiteral(statement, k)
                if (end == NPOS) return false
                k = end + 1
                continue
            }
            if (c == '{' || c == '(' || c == '[') { ++depth; ++k; continue }
            if (c == '}' || c == ')' || c == ']') { --depth; ++k; continue }
            if (depth != 0) { ++k; continue }
            // Anything but an assignment at the top level before '=' makes this another kind of expression.
            if (c == ',' || c == '?' || (c == '&' && k + 1 < statement.length && statement[k + 1] == '&') ||
                (c == '|' && k + 1 < statement.length && statement[k + 1] == '|')
            ) return false
            if (c == '=') {
                val next = if (k + 1 < statement.length) statement[k + 1] else '\u0000'
                val prev = if (k > 0) statement[k - 1] else '\u0000'
                if (next == '=' || next == '>') return false // comparison or arrow function
                if (prev == '!' || prev == '<' || prev == '>' || prev == '=') {
                    // `<<=` and `>>=` are assignments; `<=`, `>=`, `!=` are comparisons.
                    if (!(k >= 2 && (statement.startsWith("<<", k - 2) || statement.startsWith(">>", k - 2)))) return false
                }
                // Sequence expressions (`a=1,b()`) are not plain assignments.
                var j = k + 1
                var d = 0
                while (j < statement.length) {
                    if (isLiteralStart(statement, j)) {
                        val end = skipLiteral(statement, j)
                        if (end == NPOS) return false
                        j = end + 1
                        continue
                    }
                    val cj = statement[j]
                    if (cj == '{' || cj == '(' || cj == '[') ++d
                    else if (cj == '}' || cj == ')' || cj == ']') {
                        if (d == 0) break
                        --d
                    } else if (cj == ',' && d == 0) return false
                    ++j
                }
                return true
            }
            ++k
        }
        return false
    }
}
