package com.rhp.mediaplayer.model

/**
 * Search is plain, case-insensitive substring matching over the two fields
 * people actually search by: the title and the artist.
 *
 * Deliberately not fuzzy. A music player that answers "be" with B-sides and
 * Beatles alike is answering a question nobody asked, and the failure mode of
 * over-eager matching -- results you have to read in order to reject -- is worse
 * than the failure mode of exact matching, which is retyping.
 *
 * [lowercaseQuery] is expected to be already trimmed and lowercased, so a list
 * can be filtered without re-normalizing the needle per song.
 */
fun Song.matchesSearchQuery(lowercaseQuery: String): Boolean =
    title.lowercase().contains(lowercaseQuery) ||
        artist?.lowercase()?.contains(lowercaseQuery) == true

/**
 * Narrows [songs] to those matching [query]. A blank query keeps everything,
 * which is what makes clearing the search box restore the full list.
 */
fun filterBySearchQuery(songs: List<Song>, query: String): List<Song> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return songs
    return songs.filter { it.matchesSearchQuery(needle) }
}
