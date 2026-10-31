package com.novcloud.echo.data

import java.io.File

interface ArtistImageSource {
    fun image(artist: Artist, custom: Map<String, String>, playCounts: Map<Long, Int>): Any?
}

class LocalArtistImageSource : ArtistImageSource {
    override fun image(artist: Artist, custom: Map<String, String>, playCounts: Map<Long, Int>): Any? {
        custom.forName(artist.name)?.let { p -> File(p).takeIf { it.exists() }?.let { return it } }
        val best = artist.albums.maxByOrNull { al -> al.songs.sumOf { playCounts[it.id] ?: 0 } * 1000 + al.songs.size }
        return best?.art ?: artist.art
    }
}

object ArtistImages {
    var source: ArtistImageSource = LocalArtistImageSource()
}
