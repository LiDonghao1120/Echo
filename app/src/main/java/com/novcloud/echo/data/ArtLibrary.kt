package com.novcloud.echo.data

import com.novcloud.echo.data.db.ArtAsset

data class ParsedArtName(val name: String, val kind: ArtKind)

/**
 * The artist name and kind a picked image file most likely stands for: "Nova_Lark_logo.png" is a logo of Nova Lark,
 * "Juniper Vale - poster (2).jpg" a poster of Juniper Vale. Strips the extension, copy numbering ("(2)", "_1",
 * "copy", "副本"), long timestamps and hash tails, and logo/poster words at either end. Numbers that can be part of
 * a name ("Harbor 5", "Ward-21") stay. Underscores stand for spaces when the name has no spaces of its own.
 */
object ArtLibraryNames {
    private val ignore = RegexOption.IGNORE_CASE
    private val imageExt = Regex("""\.(png|webp|jpe?g|gif|bmp|heic|heif|avif|tiff?)$""", ignore)
    private val anyExt = Regex("""\.[A-Za-z0-9]{1,5}$""")
    private const val SEP = """[\s_\-.·・]"""
    private const val NUM = """(?:[\s_\-]*\d{1,3})?"""
    private const val LOGO_WORDS = """logo|wordmark|word ?mark|name ?mark|logotype|标志|字标|名称标志|艺人名|标识"""
    private const val POSTER_WORDS = """poster|background|bg|banner|海报|背景"""
    private val numbering = listOf(
        Regex("""[\s_]*[(（\[]\s*\d{1,3}\s*[)）\]]$"""),
        Regex("""$SEP*(copy|副本|拷贝)(\s*\d{1,3})?$""", ignore),
        Regex("""_\d{1,3}$"""),
        Regex("""[\s_\-]+\d{6,}$"""),
        Regex("""-[0-9a-fA-F]{8,}$"""),
    )
    private val logoTail = listOf(
        Regex("""$SEP*[(（\[]\s*($LOGO_WORDS)\s*[)）\]]$NUM$""", ignore),
        Regex("""$SEP+($LOGO_WORDS)$NUM$""", ignore),
        Regex("""(标志|字标|名称标志|标识)$NUM$"""),
    )
    private val posterTail = listOf(
        Regex("""$SEP*[(（\[]\s*($POSTER_WORDS)\s*[)）\]]$NUM$""", ignore),
        Regex("""$SEP+($POSTER_WORDS)$NUM$""", ignore),
        Regex("""(海报|背景)$NUM$"""),
    )
    private val logoHead = Regex("""^($LOGO_WORDS)$SEP+""", ignore)
    private val posterHead = Regex("""^($POSTER_WORDS)$SEP+""", ignore)
    private val edges = Regex("""^[\s_\-]+|[\s_\-]+$""")
    private val spaces = Regex("""[\s\p{Z}]+""")

    fun parse(fileName: String): ParsedArtName {
        val base = Names.clean(fileName.substringAfterLast('/')).trim()
        val stem = base.replace(imageExt, "").let { if (it == base) it.replace(anyExt, "") else it }.trim()
        var s = stem
        var kind: ArtKind? = null
        repeat(4) {
            val before = s
            numbering.forEach { r -> s = s.replace(r, "") }
            if (kind != ArtKind.Logo) posterTail.firstOrNull { it.containsMatchIn(s) }?.let { r -> s = s.replace(r, ""); kind = ArtKind.Poster }
            if (kind != ArtKind.Poster) logoTail.firstOrNull { it.containsMatchIn(s) }?.let { r -> s = s.replace(r, ""); kind = ArtKind.Logo }
            if (kind != ArtKind.Logo && posterHead.containsMatchIn(s)) { s = s.replace(posterHead, ""); kind = ArtKind.Poster }
            if (kind != ArtKind.Poster && logoHead.containsMatchIn(s)) { s = s.replace(logoHead, ""); kind = ArtKind.Logo }
            s = s.replace(edges, "")
            if (s == before) return@repeat
        }
        if (s.isEmpty()) s = stem.replace(edges, "").ifEmpty { stem }
        if ('_' in s && !s.contains(' ')) s = s.replace('_', ' ')
        s = s.replace(spaces, " ").trim()
        return ParsedArtName(s.ifEmpty { stem }, kind ?: ArtKind.Logo)
    }
}

/** Which artwork library images belong to which artist, for the picker and for filling in missing art. */
object ArtLibraryMatch {
    /** Letters and digits of [Names.key]: "Nova Lark", "nova_lark" and "Ｎｏｖａ Ｌａｒｋ\u200B" are one artist. */
    fun key(name: String): String = ArtistFileMatcher.key(name).ifEmpty { Names.key(name) }

    fun kindOf(a: ArtAsset): ArtKind = if (a.kind == ArtKind.Poster.name) ArtKind.Poster else ArtKind.Logo

    /** Marks an image the user took off an artist, so it is not put back automatically. */
    fun declined(kind: ArtKind, artist: String, hash: String) = "${kind.name}|${key(artist)}|$hash"

    data class Apply(val artist: String, val kind: ArtKind, val asset: ArtAsset)

    /**
     * Library images to put on [artists] that have no poster or logo of their own ([posters] and [logos] hold
     * [Names.key]s of artists that do). The newest matching image of the right kind wins; declined ones are skipped.
     */
    fun plan(
        artists: Collection<String>,
        posters: Set<String>,
        logos: Set<String>,
        assets: List<ArtAsset>,
        declined: Set<String> = emptySet(),
    ): List<Apply> {
        if (assets.isEmpty()) return emptyList()
        val byKey = assets.sortedWith(compareByDescending<ArtAsset> { it.updatedAt }.thenByDescending { it.id })
            .groupBy { key(it.name) to kindOf(it) }
        val out = ArrayList<Apply>()
        val seen = HashSet<String>()
        for (artist in artists) {
            if (artist == UNKNOWN || artist.isBlank()) continue
            val nk = Names.key(artist)
            if (!seen.add(nk)) continue
            val k = key(artist)
            if (k.isEmpty()) continue
            for (kind in ArtKind.entries) {
                if (nk in (if (kind == ArtKind.Logo) logos else posters)) continue
                val pick = byKey[k to kind]?.firstOrNull { declined(kind, artist, it.hash) !in declined } ?: continue
                out += Apply(artist, kind, pick)
            }
        }
        return out
    }

    /** True when [text] finds [name]: pinyin, initials and Han characters (see [SearchText]) or the folded key. */
    fun matches(name: String, text: String): Boolean {
        if (text.isBlank()) return true
        if (SearchText.of(name).match(text) >= 0) return true
        val q = key(text)
        return q.isNotEmpty() && key(name).contains(q)
    }

    /**
     * Picker order for [artist]: images with the artist's name first, then images of the [kind] being picked, then by
     * [sortKey] of the name. Filtered by [query]. The flag says whether the image carries the artist's name.
     */
    fun forPicker(
        assets: List<ArtAsset>,
        artist: String,
        kind: ArtKind,
        query: String = "",
        sortKey: (String) -> String = { it.lowercase() },
    ): List<Pair<ArtAsset, Boolean>> {
        val k = key(artist)
        return assets.asSequence()
            .filter { matches(it.name, query) }
            .map { it to (k.isNotEmpty() && key(it.name) == k) }
            .sortedWith(
                compareByDescending<Pair<ArtAsset, Boolean>> { it.second }
                    .thenByDescending { kindOf(it.first) == kind }
                    .thenBy { sortKey(it.first.name) }
                    .thenByDescending { it.first.updatedAt },
            )
            .toList()
    }
}
