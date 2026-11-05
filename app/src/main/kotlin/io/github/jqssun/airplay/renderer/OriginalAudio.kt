package io.github.jqssun.airplay.renderer

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist

/**
 * Picks the original audio track of a multi-language HLS stream.
 *
 * YouTube offers auto-dubbed tracks next to the original one, usually without DEFAULT=YES.
 * ExoPlayer then falls back to the system language, so a Russian video plays with an
 * English AI dub on a receiver whose system language is English. An Apple TV plays the
 * original, and so do we: a rendition tagged `acont=original` (YouTube xtags in the URL)
 * or named "original" wins. Failing that, if the other tracks are tagged dubbed or
 * descriptive and exactly one is left, that one wins. Otherwise ExoPlayer's choice stands.
 */
@UnstableApi
internal object OriginalAudio {

    private const val TAG = "AirPlayVideoPlayer"
    private val ORIGINAL_NAME = Regex("original|оригинал", RegexOption.IGNORE_CASE)
    private val DUBBED_NAME = Regex("dubbed|дубл", RegexOption.IGNORE_CASE)
    private val DESCRIPTIVE_NAME = Regex("descriptive|описани", RegexOption.IGNORE_CASE)
    private val ACONT = Regex("acont[=:/]([A-Za-z-]+)")

    /**
     * Returns true once the decision is made (or there is nothing to decide), false while
     * the tracks are not known yet. Logs names, languages and flags only, never URLs.
     */
    fun choose(player: ExoPlayer, tracks: Tracks): Boolean {
        if (tracks.groups.isEmpty()) return false
        val manifest = player.currentManifest as? HlsManifest ?: return true
        for (s in manifest.multivariantPlaylist.subtitles) {
            val f = s.format
            Log.i(TAG, "subtitle rendition: name='${s.name}' lang=${f.language} " +
                "default=${f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0} " +
                "autoselect=${f.selectionFlags and C.SELECTION_FLAG_AUTOSELECT != 0} " +
                "forced=${f.selectionFlags and C.SELECTION_FLAG_FORCED != 0}")
        }
        if (manifest.multivariantPlaylist.subtitles.isNotEmpty()) {
            val text = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            Log.i(TAG, "subtitles: selected=${text?.getTrackFormat(0)?.label}")
        }
        val audios = manifest.multivariantPlaylist.audios
        for (a in audios) {
            val f = a.format
            Log.i(TAG, "audio rendition: name='${a.name}' lang=${f.language} group=${a.groupId} " +
                "default=${f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0} " +
                "autoselect=${f.selectionFlags and C.SELECTION_FLAG_AUTOSELECT != 0} " +
                "roles=${f.roleFlags} acont=${acont(a.url)}")
        }
        // One language left (UxPlay already filtered a YouTube master playlist down to the sender's
        // choice; the same rendition then sits in several bitrate groups): nothing to decide, and
        // forcing an override over those groups stalled YouTube playback.
        if (audios.distinctBy { it.name }.size < 2) return true
        val original = pick(audios)
        if (original == null) {
            Log.i(TAG, "audio: no original marker, keeping '${selectedLabel(tracks)}'")
            return true
        }
        val group = tracks.groups.firstOrNull { g ->
            g.type == C.TRACK_TYPE_AUDIO && (0 until g.length).any { i -> matches(g.getTrackFormat(i), original) }
        }
        val params = player.trackSelectionParameters.buildUpon()
        if (group != null) {
            val indices = (0 until group.length).filter { group.isTrackSupported(it) }.ifEmpty { listOf(0) }
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, indices))
        } else if (original.format.language != null) {
            params.setPreferredAudioLanguage(original.format.language)
        } else {
            Log.i(TAG, "audio: original '${original.name}' not among the tracks")
            return true
        }
        Log.i(TAG, "audio: original '${original.name}' (${original.format.language}), was '${selectedLabel(tracks)}'")
        player.trackSelectionParameters = params.build()
        return true
    }

    private fun pick(audios: List<HlsMultivariantPlaylist.Rendition>): HlsMultivariantPlaylist.Rendition? {
        audios.firstOrNull { acont(it.url) == "original" }?.let { return it }
        audios.firstOrNull { ORIGINAL_NAME.containsMatchIn(it.name) }?.let { return it }
        val kept = audios.filterNot { a ->
            val tag = acont(a.url)
            (tag != null && tag != "original") ||
                DUBBED_NAME.containsMatchIn(a.name) || DESCRIPTIVE_NAME.containsMatchIn(a.name)
        }
        if (kept.size == audios.size) return null // nothing is marked as a dub: no evidence either way
        return kept.distinctBy { it.name }.singleOrNull()
    }

    /** ExoPlayer labels an HLS rendition's format with its NAME and gives it the id "group:name". */
    private fun matches(f: androidx.media3.common.Format, r: HlsMultivariantPlaylist.Rendition): Boolean =
        f.label == r.name || f.labels.any { it.value == r.name } || f.id?.endsWith(":${r.name}") == true

    private fun acont(url: Uri?): String? =
        url?.let { ACONT.find(Uri.decode(it.toString()))?.groupValues?.get(1)?.lowercase() }

    private fun selectedLabel(tracks: Tracks): String? =
        tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).label }
        }
}
