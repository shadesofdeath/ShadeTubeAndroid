package com.app.shadetube.youtube.explode

/**
 * Strongly typed, validated YouTube identifiers (port of YoutubeExplode/src/Ids.cpp and detail/StringId). Each
 * accepts a raw id or any common URL form; `parse` throws IllegalArgumentException, `tryParse` returns null.
 */
private fun extract(input: String, pattern: Regex, isValid: (String) -> Boolean): String? {
    val m = pattern.find(input) ?: return null
    val value = Url.decode(m.groupValues[1])
    if (Str.isBlank(value) || !isValid(value)) return null
    return value
}

private fun isIdChar(c: Char) = Str.isAsciiAlnum(c) || c == '_' || c == '-'
private fun isHandleChar(c: Char) = Str.isAsciiAlnum(c) || c == '_' || c == '-' || c == '.'
private fun isSlugChar(c: Char) = Str.isAsciiAlnum(c)

private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

/**
 * A syntactically valid YouTube video ID. Accepts:
 * ```
 *   yIVRs6YSbOM
 *   https://www.youtube.com/watch?v=yIVRs6YSbOM
 *   https://youtu.be/yIVRs6YSbOM
 *   https://www.youtube.com/embed/yIVRs6YSbOM
 *   https://www.youtube.com/shorts/sKL1vjP0tIo
 *   https://www.youtube.com/live/jfKfPfyJRdk
 *   https://music.youtube.com/watch?v=yIVRs6YSbOM
 * ```
 */
@JvmInline
value class VideoId private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private fun isValid(id: String) = id.length == 11 && id.all(::isIdChar)

        private val patterns = listOf(
            re("""youtube\..+?/watch.*?v=(.*?)(?:&|/|#|$)"""),      // https://www.youtube.com/watch?v=yIVRs6YSbOM
            re("""youtu\.be/watch.*?v=(.*?)(?:\?|&|/|#|$)"""),      // https://youtu.be/watch?v=Fcds0_MrgNU
            re("""youtu\.be/(.*?)(?:\?|&|/|#|$)"""),                // https://youtu.be/yIVRs6YSbOM
            re("""youtube\..+?/embed/(.*?)(?:\?|&|/|#|$)"""),       // https://www.youtube.com/embed/yIVRs6YSbOM
            re("""youtube\..+?/shorts/(.*?)(?:\?|&|/|#|$)"""),      // https://www.youtube.com/shorts/sKL1vjP0tIo
            re("""youtube\..+?/live/(.*?)(?:\?|&|/|#|$)"""),        // https://www.youtube.com/live/jfKfPfyJRdk
            re("""youtube-nocookie\..+?/embed/(.*?)(?:\?|&|/|#|$)"""),
        )

        fun tryNormalize(rawInput: String): String? {
            val input = Str.trim(rawInput)
            if (input.isEmpty()) return null
            if (isValid(input)) return input
            for (p in patterns) extract(input, p, ::isValid)?.let { return it }
            return null
        }

        fun tryParse(input: String): VideoId? = tryNormalize(input)?.let { VideoId(it) }
        fun parse(input: String): VideoId =
            tryParse(input) ?: throw IllegalArgumentException("Invalid YouTube video ID or URL '$input'.")
    }
}

/**
 * A syntactically valid YouTube playlist ID.
 * ```
 *   PLOU2XLYxmsIJGErt5rrCqaSGTMyyqNt2H
 *   https://www.youtube.com/playlist?list=PLOU2XLYxmsIJGErt5rrCqaSGTMyyqNt2H
 *   https://www.youtube.com/watch?v=b8m9zhNAgKs&list=PL9tY0BWXOZFuFEG_GtOBZ8-8wbkH-NVAr
 * ```
 */
@JvmInline
value class PlaylistId private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private fun isValid(id: String) = id.length >= 2 && id.all(::isIdChar)

        private val patterns = listOf(
            re("""youtube\..+?/playlist.*?list=(.*?)(?:&|/|#|$)"""),
            re("""youtube\..+?/watch.*?list=(.*?)(?:&|/|#|$)"""),
            re("""youtu\.be/.*?/.*?list=(.*?)(?:&|/|#|$)"""),
            re("""youtu\.be/.*?list=(.*?)(?:&|/|#|$)"""),
            re("""youtube\..+?/embed/.*?/.*?list=(.*?)(?:&|/|#|$)"""),
        )

        fun tryNormalize(rawInput: String): String? {
            val input = Str.trim(rawInput)
            if (input.isEmpty()) return null
            if (isValid(input)) return input
            for (p in patterns) extract(input, p, ::isValid)?.let { return it }
            return null
        }

        fun tryParse(input: String): PlaylistId? = tryNormalize(input)?.let { PlaylistId(it) }
        fun parse(input: String): PlaylistId =
            tryParse(input) ?: throw IllegalArgumentException("Invalid YouTube playlist ID or URL '$input'.")
    }
}

/** UC3xnGqlcL3y-GXz5N3wiTJQ or https://www.youtube.com/channel/UC3xnGqlcL3y-GXz5N3wiTJQ */
@JvmInline
value class ChannelId private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private fun isValid(id: String) = id.length == 24 && id.startsWith("UC") && id.all(::isIdChar)
        private val pattern = re("""youtube\..+?/channel/(.*?)(?:\?|&|/|#|$)""")

        fun tryNormalize(rawInput: String): String? {
            val input = Str.trim(rawInput)
            if (isValid(input)) return input
            return extract(input, pattern, ::isValid)
        }

        fun tryParse(input: String): ChannelId? = tryNormalize(input)?.let { ChannelId(it) }
        fun parse(input: String): ChannelId =
            tryParse(input) ?: throw IllegalArgumentException("Invalid YouTube channel ID or URL '$input'.")
    }
}

/** Tyrrrz (or @Tyrrrz) or https://www.youtube.com/@Tyrrrz */
@JvmInline
value class ChannelHandle private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private fun isValid(h: String) = h.isNotEmpty() && h.all(::isHandleChar)
        private val pattern = re("""youtube\..+?/@(.*?)(?:\?|&|/|#|$)""")

        fun tryNormalize(rawInput: String): String? {
            val input = Str.trim(rawInput)
            if (input.startsWith("@") && isValid(input.substring(1))) return input.substring(1)
            if (isValid(input)) return input
            return extract(input, pattern, ::isValid)
        }

        fun tryParse(input: String): ChannelHandle? = tryNormalize(input)?.let { ChannelHandle(it) }
        fun parse(input: String): ChannelHandle =
            tryParse(input) ?: throw IllegalArgumentException("Invalid YouTube channel handle or custom URL '$input'.")
    }
}

/** Tyrrrz or https://www.youtube.com/c/Tyrrrz */
@JvmInline
value class ChannelSlug private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private fun isValid(s: String) = s.isNotEmpty() && s.all(::isSlugChar)
        private val pattern = re("""youtube\..+?/c/(.*?)(?:\?|&|/|#|$)""")

        fun tryNormalize(rawInput: String): String? {
            val input = Str.trim(rawInput)
            if (isValid(input)) return input
            return extract(input, pattern, ::isValid)
        }

        fun tryParse(input: String): ChannelSlug? = tryNormalize(input)?.let { ChannelSlug(it) }
        fun parse(input: String): ChannelSlug =
            tryParse(input) ?: throw IllegalArgumentException("Invalid YouTube channel slug or legacy custom URL '$input'.")
    }
}

/** TheTyrrr or https://www.youtube.com/user/TheTyrrr */
@JvmInline
value class UserName private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private fun isValid(s: String) = s.isNotEmpty() && s.length <= 20 && s.all(::isSlugChar)
        private val pattern = re("""youtube\..+?/user/(.*?)(?:\?|&|/|#|$)""")

        fun tryNormalize(rawInput: String): String? {
            val input = Str.trim(rawInput)
            if (isValid(input)) return input
            return extract(input, pattern, ::isValid)
        }

        fun tryParse(input: String): UserName? = tryNormalize(input)?.let { UserName(it) }
        fun parse(input: String): UserName =
            tryParse(input) ?: throw IllegalArgumentException("Invalid YouTube user name or profile URL '$input'.")
    }
}
