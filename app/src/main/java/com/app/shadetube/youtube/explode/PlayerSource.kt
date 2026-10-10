package com.app.shadetube.youtube.explode

/**
 * Parsed YouTube player JavaScript (base.js). Port of Bridge/PlayerSource.
 *
 * Everything here is heuristic, reverse-engineered knowledge of how YouTube's minified player is laid out. It is THE
 * part of the library that breaks when YouTube ships a new player structure.
 *
 * How the player is dissected:
 *
 * 1. Signature timestamp: `signatureTimestamp:19834` (or `sts:19834`) anywhere in the source.
 *
 * 2. Global lookup array (newer players): right after "use strict" the player declares
 *      `var XX="split;reverse;length;...".split(";")`      or      `var XX=["split","reverse",...]`
 *    and later refers to strings/method names as XX[12]. We decode that array so that code like `a[XX[3]](XX[0])`
 *    can be read as `a["reverse"]("")`.
 *
 * 3. Signature cipher "callsite": a small function that splits the scrambled signature into characters, applies a
 *    sequence of helper calls, and joins it again:
 *      `Xy=function(a){a=a.split("");Ab.cd(a,3);Ab.ef(a,41);Ab.gh(a,2);return a.join("")}`
 *    Each statement `Ab.cd(a,3)` is one operation; `Ab` is the helper object.
 *
 * 4. Helper object: `var Ab={cd:function(a,b){a.splice(0,b)},ef:function(a){a.reverse()},
 *    gh:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}}`. Each member is classified by what its body
 *    does: reverse -> Reverse, splice -> Splice(n), `%`/[0] juggling -> Swap(n).
 *
 * 5. n-parameter function (throttling): located via the code that reads the "n" query parameter from a stream URL and
 *    passes it through a transform, e.g.
 *      `.get("n"))&&(b=Xy[0](b)`          `b=String.fromCharCode(110),c=a.get(b))&&(c=Xy[0](c)`
 *      `c=a.get(b))&&(c=Xy(c)`            `d=Xy[0](c),a.set("n",d)`
 *    or, as a last resort, by the function whose catch block returns "enhanced_except_..._w8_". If the name has an
 *    index (Xy[0]) we resolve `var Xy=[Real]`. The function body is copied verbatim, together with the global array
 *    it depends on, and handed to a JS engine.
 *
 * 6. Whole-player solver (JS engine only; port of yt-dlp's "EJS" solver). Since 2025, YouTube flattens the control
 *    flow of the cipher/n code (e.g. `P[l[h^315]](l[h^273])`), so no snippet can be cut out reliably. Instead:
 *      - take the body of the player IIFE `(function(g){var window=this; ... })(_yt_player)`,
 *      - drop `var window=this` and every top-level expression statement that is not an assignment (those are the
 *        side effects that would need a real browser),
 *      - find the function whose body contains the statement `x.set("alr","yes")`: it builds a stream URL object and
 *        applies the signature transform to the parameter it is given,
 *      - generate `_yte_solve(sig, n)` that builds such a URL with `s=<sig>`, sets `n`, and invokes the URL object's
 *        first own prototype method (which applies the n transform).
 *
 * The player source is large (several MB); only what was extracted is kept. [buildSolver] = false skips step 6
 * (Android addition: the solver script is a copy of the whole player and only useful to a JS engine).
 */
class PlayerSource(val url: String, content: String, buildSolver: Boolean = true) {
    /** Signature timestamp ("sts"), sent to the player endpoint so that the returned ciphers match this player. */
    var signatureTimestamp: String? = null
        private set

    /** Signature transform plan (reverse / splice / swap), for the built-in interpreter. */
    var cipherManifest: CipherManifest? = null
        private set

    /** Self-contained script prefix that defines `__yte_sig(s)`, for a JS engine (fallback path). */
    var signatureScript: String? = null
        private set

    /** Self-contained script prefix that defines `__yte_n(n)`, for a JS engine. */
    var nScript: String? = null
        private set

    /**
     * Whole-player solver (for a JS engine): the approach yt-dlp uses since 2025. Defines `_yte_solve(sig, n)`, which
     * returns `{sig, n}`. Robust against the control-flow flattening used by modern players.
     */
    var solverScript: String? = null
        private set

    /** Human-readable description of what failed during extraction (for error messages). */
    var diagnostics: String = ""
        private set

    /** Name of the global lookup array (`var XX="...".split(";")`), if the player has one. */
    var globalArrayName: String? = null
        private set
    var globalArray: List<String> = emptyList()
        private set
    private var globalArrayCode: String? = null

    init {
        extractSignatureTimestamp(content)
        extractGlobalArray(content)
        extractSignatureCipher(content)
        extractNFunction(content)
        if (buildSolver) buildPlayerSolver(content)
    }

    private fun note(message: String) {
        if (diagnostics.isNotEmpty()) diagnostics += " "
        diagnostics += message
    }

    private fun window(s: String, begin: Int, end: Int): String {
        val b = begin.coerceIn(0, s.length)
        val e = end.coerceIn(b, s.length)
        return s.substring(b, e)
    }

    // ---- 1. Signature timestamp ----------------------------------------------------------------------------------

    private fun extractSignatureTimestamp(content: String) {
        for (key in listOf("signatureTimestamp:", "sts:")) {
            var from = 0
            while (true) {
                val pos = content.indexOf(key, from)
                if (pos < 0) break
                from = pos + 1
                if (pos > 0 && Js.isIdentifierChar(content[pos - 1])) continue
                var k = pos + key.length
                val digits = StringBuilder()
                while (k < content.length && Str.isAsciiDigit(content[k])) digits.append(content[k++])
                if (digits.length >= 5) {
                    signatureTimestamp = digits.toString()
                    return
                }
            }
        }
        note("Signature timestamp not found.")
    }

    // ---- 2. Global lookup array ----------------------------------------------------------------------------------

    private fun extractGlobalArray(content: String) {
        for (marker in listOf("\"use strict\";", "'use strict';")) {
            var from = 0
            while (true) {
                val pos = content.indexOf(marker, from)
                if (pos < 0) break
                from = pos + 1
                val start = pos + marker.length
                val head = window(content, start, start + 64)
                val m = DECLARATION.find(head) ?: continue
                val name = m.groupValues[1]
                val valueStart = start + m.value.length
                if (valueStart >= content.length) continue

                val values = ArrayList<String>()
                val valueEnd: Int
                val c = content[valueStart]
                if (c == '"' || c == '\'') {
                    // "a;b;c".split(";")
                    val (joined, afterString) = Js.parseStringLiteralAt(content, valueStart) ?: continue
                    if (!content.startsWith(".split(", afterString)) continue
                    val (separator, afterSep) = Js.parseStringLiteralAt(content, afterString + 7) ?: continue
                    if (afterSep >= content.length || content[afterSep] != ')') continue
                    if (separator.isEmpty()) {
                        for (ch in joined) values.add(ch.toString())
                    } else {
                        var p = 0
                        while (true) {
                            val q = joined.indexOf(separator, p)
                            values.add(if (q < 0) joined.substring(p) else joined.substring(p, q))
                            if (q < 0) break
                            p = q + separator.length
                        }
                    }
                    valueEnd = afterSep + 1
                } else if (c == '[') {
                    val close = Js.findMatchingBracket(content, valueStart)
                    if (close == Js.NPOS) continue
                    var ok = true
                    for (item in Js.splitTopLevel(content.substring(valueStart + 1, close), ',')) {
                        val t = Str.trim(item)
                        val v = Js.parseStringLiteral(t, 0)
                        if (v == null) {
                            ok = false
                            break
                        }
                        values.add(v)
                    }
                    if (!ok) continue
                    valueEnd = close + 1
                } else {
                    continue
                }

                globalArrayName = name
                globalArray = values
                globalArrayCode = "var " + name + "=" + content.substring(valueStart, valueEnd) + ";"
                return
            }
        }
        // Older players have no global array; that's fine.
    }

    /** Replaces NAME[123] with the corresponding string literal. */
    private fun resolveGlobalReferences(code: String): String {
        val name = globalArrayName ?: return code
        val out = StringBuilder()
        var from = 0
        while (true) {
            val pos = Js.findIdentifier(code, name, from)
            if (pos == Js.NPOS) {
                out.append(code, from, code.length)
                break
            }
            val k = pos + name.length
            var close = Js.NPOS
            val digits = StringBuilder()
            if (k < code.length && code[k] == '[') {
                var j = k + 1
                while (j < code.length && Str.isAsciiDigit(code[j])) digits.append(code[j++])
                if (j < code.length && code[j] == ']') close = j
            }
            val idx = Str.tryParseInt(digits.toString())
            if (close == Js.NPOS || idx == null || idx < 0 || idx >= globalArray.size) {
                out.append(code, from, k)
                from = k
                continue
            }
            out.append(code, from, pos)
            out.append(Js.toStringLiteral(globalArray[idx]))
            from = close + 1
        }
        return out.toString()
    }

    // ---- 3 & 4. Signature cipher ---------------------------------------------------------------------------------

    private fun extractSignatureCipher(content: String) {
        // Find the callsite: <name>=function(<a>){<a>=<a>.split("")... return <a>.join("")}
        // We anchor on `.split(` occurrences and validate the surrounding code with a small regex, which is far faster
        // and more robust than running a regex over the whole multi-MB file.
        var callsite: Js.FunctionSpan? = null
        var argument = ""
        val splitToken = ".split("
        var from = 0
        while (callsite == null) {
            val pos = content.indexOf(splitToken, from)
            if (pos < 0) break
            from = pos + 1
            val afterSplit = pos + splitToken.length
            val prefixStart = if (pos >= 160) pos - 160 else 0
            val prefix = window(content, prefixStart, afterSplit)
            val m = CALLSITE_HEAD.find(prefix) ?: continue
            // Argument of split() must be "" or a global-array entry equal to "".
            val argEnd = content.indexOf(')', afterSplit)
            if (argEnd < 0) continue
            val splitArg = resolveGlobalReferences(Str.trim(content.substring(afterSplit, argEnd)))
            if (splitArg != "\"\"" && splitArg != "''") continue

            val funcStart = prefixStart + m.range.first
            val fnPos = content.indexOf("function", funcStart)
            if (fnPos < 0 || fnPos > pos) continue
            val span = Js.parseFunctionAt(content, fnPos) ?: continue
            val body = resolveGlobalReferences(span.body)
            val arg = m.groupValues[3]
            if (!body.contains("$arg.join(\"\")") && !body.contains("$arg[\"join\"](\"\")") && !body.contains("$arg.join('')"))
                continue
            span.body = body
            callsite = span
            argument = arg
        }

        if (callsite == null) {
            note("Signature cipher callsite (a=a.split(\"\")...return a.join(\"\")) not found.")
            return
        }

        // Parse operations: Obj.fn(a,3)   Obj["fn"](a,3)   Obj.fn(a)
        class Call(val container: String, val function: String, val index: Int)
        val calls = ArrayList<Call>()
        var containerName = ""
        for (statement in Js.splitTopLevel(callsite.body, ';')) {
            // Statements may also be comma-joined: Ab.cd(a,3),Ab.ef(a,41)
            for (expression in Js.splitTopLevel(statement, ',')) {
                // A comma split breaks "Ab.cd(a,3)" only at top level, so calls stay intact.
                val m = CALL.matchEntire(expression) ?: continue
                if (m.groupValues[4] != argument) continue
                val fn = m.groups[2]?.value ?: m.groupValues[3]
                val c = Call(m.groupValues[1], fn, m.groups[5]?.value?.toIntOrNull() ?: 0)
                if (containerName.isEmpty()) containerName = c.container
                if (c.container == containerName) calls.add(c)
            }
        }

        val containerCode = if (containerName.isNotEmpty()) Js.findObjectLiteral(content, containerName) else null

        // JS engine script: everything the callsite needs, verbatim.
        if (containerCode != null) {
            signatureScript = (globalArrayCode ?: "") + "var " + containerName + "=" + containerCode + ";" +
                "var __yte_sig=function(" + callsite.params + "){" + callsite.body + "};"
        }

        if (calls.isEmpty() || containerCode == null) {
            note(
                if (calls.isEmpty()) "Signature cipher operations could not be parsed."
                else "Signature cipher helper object '$containerName' not found.",
            )
            return
        }

        // Classify helper members.
        val roles = ArrayList<Pair<String, CipherOperation.Kind?>>()
        val container = resolveGlobalReferences(containerCode)
        for (memberView in Js.splitTopLevel(container.substring(1, container.length - 1), ',')) {
            val member = Str.trim(memberView)
            val colon = member.indexOf(':')
            if (colon < 0) continue
            var key = Str.trim(member.substring(0, colon))
            if (key.isNotEmpty() && (key[0] == '"' || key[0] == '\'')) Js.parseStringLiteral(key, 0)?.let { key = it }
            val value = member.substring(colon + 1)
            val role = when {
                value.contains("reverse") -> CipherOperation.Kind.Reverse
                value.contains("splice") || value.contains("slice") -> CipherOperation.Kind.Splice
                value.contains("%") || value.contains("[0]") -> CipherOperation.Kind.Swap
                else -> null
            }
            roles.add(key to role)
        }

        val operations = ArrayList<CipherOperation>()
        for (c in calls) {
            var role: CipherOperation.Kind? = null
            for ((name, r) in roles) if (name == c.function) role = r
            when (role) {
                CipherOperation.Kind.Reverse -> operations.add(CipherOperation(CipherOperation.Kind.Reverse, 0))
                CipherOperation.Kind.Splice -> operations.add(CipherOperation(CipherOperation.Kind.Splice, c.index))
                CipherOperation.Kind.Swap -> operations.add(CipherOperation(CipherOperation.Kind.Swap, c.index))
                null -> {
                    note("Signature cipher helper '$containerName.${c.function}' could not be classified.")
                    return
                }
            }
        }
        cipherManifest = CipherManifest(signatureTimestamp ?: "", operations)
    }

    // ---- 5. n-parameter function ---------------------------------------------------------------------------------

    private fun extractNFunction(content: String) {
        class Candidate(val name: String, val index: Int?)
        var found: Candidate? = null

        fun tryPattern(anchor: String, pattern: Regex, lookBehind: Boolean) {
            var from = 0
            while (found == null) {
                val pos = content.indexOf(anchor, from)
                if (pos < 0) return
                from = pos + 1
                val text = if (lookBehind) window(content, if (pos >= 120) pos - 120 else 0, pos + anchor.length + 80)
                else window(content, pos, pos + anchor.length + 160)
                val m = pattern.find(text) ?: continue
                found = Candidate(m.groupValues[1], m.groups[2]?.value?.toIntOrNull())
            }
        }

        // a) .get("n"))&&(b=Xy[0](b)   /   .get("n"))&&(b=Xy(b)
        tryPattern(".get(\"n\"))&&(", N_PATTERN_1, false)

        // b) b=String.fromCharCode(110),c=a.get(b))&&(c=Xy[0](c)
        //    "nn"[+a.D]),c=a.get(b))&&(c=Xy[0](c)
        if (found == null) tryPattern("String.fromCharCode(110)", N_PATTERN_2, false)
        if (found == null) tryPattern("\"nn\"[+", N_PATTERN_2, false)

        // c) d=Xy[0](c),a.set("n",d)   /   d=Xy[0](c),a.set(b,d)
        if (found == null) {
            var from = 0
            while (found == null) {
                val pos = content.indexOf(".set(", from)
                if (pos < 0) break
                from = pos + 1
                val text = window(content, if (pos >= 80) pos - 80 else 0, pos + 40)
                val m = N_PATTERN_3.find(text) ?: continue
                found = Candidate(m.groupValues[2], m.groups[3]?.value?.toIntOrNull())
            }
        }

        // d) Fallback: the function whose catch block returns "..._w8_" + a.
        if (found == null) {
            var from = 0
            while (found == null) {
                val anchor = content.indexOf("_w8_", from)
                if (anchor < 0) break
                from = anchor + 1
                // Walk back over "=function(" occurrences, nearest first.
                var back = anchor
                var attempts = 0
                while (attempts < 64 && found == null) {
                    ++attempts
                    val fn = content.lastIndexOf("=function(", back)
                    if (fn < 0 || anchor - fn > 60000) break
                    back = if (fn == 0) 0 else fn - 1
                    val span = Js.parseFunctionAt(content, fn + 1)
                    if (span == null || span.end <= anchor) continue
                    val nameEnd = fn
                    var nameStart = nameEnd
                    while (nameStart > 0 && Js.isIdentifierChar(content[nameStart - 1])) --nameStart
                    if (nameStart < nameEnd) found = Candidate(content.substring(nameStart, nameEnd), null)
                }
            }
        }

        val candidate = found
        if (candidate == null) {
            note("n-parameter function reference not found.")
            return
        }

        var functionName = candidate.name
        if (candidate.index != null) {
            val items = Js.findArrayLiteral(content, candidate.name)
            if (items == null || candidate.index >= items.size) {
                note("n-parameter function array '${candidate.name}' not found.")
                return
            }
            functionName = items[candidate.index]
        }

        val span = Js.findFunctionDefinition(content, functionName)
        if (span == null) {
            note("n-parameter function '$functionName' definition not found.")
            return
        }

        // Remove the early-return guard `;if(typeof X==="undefined")return a;` that makes the function a no-op when
        // evaluated outside the full player context.
        var body = span.body
        val params = Js.splitTopLevel(span.params, ',')
        val firstParam = if (params.isEmpty()) "" else Str.trim(params[0])
        var undefinedRef = "\"undefined\""
        val arrayName = globalArrayName
        for ((i, v) in globalArray.withIndex()) if (v == "undefined" && arrayName != null) undefinedRef += "|$arrayName\\[$i\\]"
        if (firstParam.isNotEmpty()) {
            val escapedParam = firstParam.replace("$", "\\$")
            val escapedUndefined = undefinedRef.replace("$", "\\$")
            val guard = Regex(
                """;\s*if\s*\(\s*typeof\s+[\w$]+\s*===?\s*(?:""" + escapedUndefined +
                    """|'undefined')\s*\)\s*return\s+""" + escapedParam + """\s*;""",
            )
            val cleaned = StringBuilder()
            var last = 0
            var search = 0
            while (true) {
                val t = body.indexOf("typeof", search)
                if (t < 0) break
                search = t + 1
                val semi = body.lastIndexOf(';', t)
                if (semi < 0 || semi < last || t - semi > 12) continue
                val candidateText = window(body, semi, semi + 120 + undefinedRef.length)
                val m = guard.find(candidateText)
                if (m != null && m.range.first == 0) {
                    cleaned.append(body, last, semi)
                    cleaned.append(";")
                    last = semi + m.value.length
                    search = last
                }
            }
            cleaned.append(body, last, body.length)
            body = cleaned.toString()
        }

        nScript = (globalArrayCode ?: "") + "var __yte_n=function(" + span.params + "){" + body + "};"
    }

    // ---- 6. Whole-player solver ----------------------------------------------------------------------------------

    private fun buildPlayerSolver(content: String) {
        // Locate the player IIFE: var _yt_player={};(function(g){var window=this;...})(_yt_player);
        val iife = content.indexOf("(function(")
        if (iife < 0 || iife > 512) {
            note("Player IIFE not found (whole-player solver unavailable).")
            return
        }
        val wrapper = Js.parseFunctionAt(content, iife + 1)
        if (wrapper == null) {
            note("Player IIFE could not be parsed (whole-player solver unavailable).")
            return
        }
        val prefix = content.substring(0, iife + 1) // keeps the opening "("
        val suffix = content.substring(wrapper.end)

        val statements = Js.splitStatements(wrapper.body)
        val body = StringBuilder(wrapper.body.length)
        val candidates = ArrayList<String>()
        var first = true

        for (statement in statements) {
            if (first) {
                first = false
                if (statement.take(16) == "var window=this" || statement == "var window=this")
                    continue // `window` must resolve to our global stub
            }
            val word = Js.leadingWord(statement)
            val c0 = if (statement.isEmpty()) '\u0000' else statement[0]
            val isDeclarationOrControl = word == "var" || word == "let" || word == "const" || word == "function" ||
                word == "class" || word == "if" || word == "for" || word == "while" || word == "do" || word == "try" ||
                word == "switch" || c0 == '{'
            val isLiteral = c0 == '"' || c0 == '\''
            if (!isDeclarationOrControl && !isLiteral && !Js.isAssignmentStatement(statement))
                continue // side-effect expression statement: drop it

            body.append(statement)
            body.append(if (word == "function") "\n" else ";\n")

            // Candidate URL helpers: NAME=function(...){...}, function NAME(...){...}, var NAME=function(...){...}
            if (!statement.contains("\"alr\"")) continue
            if (word == "function") {
                val span = Js.parseFunctionAt(statement, 0)
                if (span != null && bodyHasAlrYesStatement(span.body)) {
                    val open = statement.indexOf('(')
                    candidates.add(Str.trim(statement.substring(8, open)))
                }
                continue
            }
            val declarators = if (word == "var" || word == "let" || word == "const")
                Js.splitTopLevel(statement.substring(word.length), ',') else listOf(statement)
            for (declarator in declarators) {
                val eq = declarator.indexOf('=')
                if (eq < 0) continue
                val target = Str.trim(declarator.substring(0, eq))
                val value = Str.trim(declarator.substring(eq + 1))
                if (!value.startsWith("function")) continue
                val span = Js.parseFunctionAt(value, 0)
                if (span != null && bodyHasAlrYesStatement(span.body)) candidates.add(target)
            }
        }

        if (candidates.isEmpty()) {
            note("URL helper setting alr=yes not found (whole-player solver unavailable).")
            return
        }

        val registration = candidates.joinToString("") { "_yte_solvers.push($it);\n" }
        solverScript = buildString(content.length + 4096) {
            append(SOLVER_PRELUDE)
            append(prefix)
            append("function(").append(wrapper.params).append("){\n").append(body).append(registration).append("}")
            append(suffix)
            append("\n")
            append(SOLVER_EPILOGUE)
        }
    }

    private companion object {
        val DECLARATION = Regex("""^\s*var\s+([\w$]+)\s*=\s*""")
        val CALLSITE_HEAD = Regex(
            """(?:([\w$]+)\s*=\s*function|function\s+([\w$]+))\s*\(\s*([\w$]+)\s*\)\s*\{\s*(?:var\s+)?\3\s*=\s*\3\.split\(\s*$""",
        )
        val CALL = Regex("""^\s*([\w$]+)\s*(?:\.\s*([\w$]+)|\[\s*"([\w$]+)"\s*\])\s*\(\s*([\w$]+)\s*(?:,\s*(\d+)\s*)?\)\s*$""")
        val N_PATTERN_1 = Regex("""^\.get\("n"\)\)&&\(\s*[\w$]+\s*=\s*([\w$]+)(?:\[(\d+)\])?\(\s*[\w$]+\s*\)""")
        val N_PATTERN_2 = Regex(
            """^(?:String\.fromCharCode\(110\)|"nn"\[\+[\w$.]+\])(?:,[\w$]+\(a\))?,[\w$]+=[\w$]+\.(?:get\([\w$]+\)|[\w$]+\[[\w$]+\]\|\|null)\)&&\(\s*[\w$]+\s*=\s*([\w$]+)(?:\[(\d+)\])?\(\s*[\w$]+\s*\)""",
        )
        val N_PATTERN_3 = Regex("""([\w$]+)=([\w$]+)(?:\[(\d+)\])?\([\w$]+\),[\w$]+\.set\((?:"n+"|[\w$]+),\1\)""")
        val ALR_YES = Regex("""^[\w$]+\s*(?:\.\s*[\w$]+|\[[^\]]+\])\s*\(\s*"alr"\s*,\s*"yes"\s*\)$""")

        fun isAlrYesStatement(statement: String): Boolean {
            if (!statement.contains("\"alr\"")) return false
            return ALR_YES.matches(statement)
        }

        fun bodyHasAlrYesStatement(body: String): Boolean {
            if (!body.contains("\"alr\"")) return false
            return Js.splitStatements(body).any { isAlrYesStatement(it) }
        }

        const val SOLVER_PRELUDE = """
if (typeof globalThis.XMLHttpRequest === "undefined") { globalThis.XMLHttpRequest = { prototype: {} }; }
if (typeof URL === "undefined") {
  globalThis.location = { hash: "", host: "www.youtube.com", hostname: "www.youtube.com",
    href: "https://www.youtube.com/watch?v=yt-explode", origin: "https://www.youtube.com", password: "",
    pathname: "/watch", port: "", protocol: "https:", search: "?v=yt-explode", username: "" };
} else { globalThis.location = new URL("https://www.youtube.com/watch?v=yt-explode"); }
if (typeof globalThis.document === "undefined") { globalThis.document = Object.create(null); }
if (typeof globalThis.navigator === "undefined") { globalThis.navigator = Object.create(null); }
if (typeof globalThis.self === "undefined") { globalThis.self = globalThis; }
if (typeof globalThis.window === "undefined") { globalThis.window = globalThis; }
var _yte_solvers = [];
"""

        // Calls one candidate URL helper and extracts the transformed parameters.
        const val SOLVER_EPILOGUE = """
globalThis._yte_solve = function (sig, n) {
  var results = [], errors = [];
  for (var i = 0; i < _yte_solvers.length; i++) {
    try {
      var url = _yte_solvers[i]("https://youtube.com/watch?v=yt-explode", "s", sig ? encodeURIComponent(sig) : undefined);
      url.set("n", n);
      var proto = Object.getPrototypeOf(url);
      var keys = Object.keys(proto).concat(Object.getOwnPropertyNames(proto));
      for (var k = 0; k < keys.length; k++) {
        if (["constructor", "set", "get", "clone"].indexOf(keys[k]) < 0) { url[keys[k]](); break; }
      }
      var s = url.get("s");
      results.push(JSON.stringify({ sig: s ? decodeURIComponent(s) : null, n: url.get("n") || null }));
    } catch (e) { errors.push(String(e)); }
  }
  var unique = results.filter(function (v, idx) { return results.indexOf(v) === idx; });
  if (unique.length !== 1) { throw new Error(unique.length ? "ambiguous solutions: " + unique.join(" | ") : "no solutions: " + errors.join(", ")); }
  return JSON.parse(unique[0]);
};
"""
    }
}
