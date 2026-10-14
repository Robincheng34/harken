// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.playback

/**
 * The kind of list the queue was loaded from (iOS `PlaySource`).
 *
 * [sourceID] carries the concrete playlist/album/artist id (0 for DAILY /
 * CLOUD / NONE); [PlaybackQueue.load] assigns it from the play context and the
 * player layer reads it for scrobbling (`content: id=<sourceID>`).
 */
enum class PlaySource {
    PLAYLIST,
    ALBUM,
    ARTIST,
    DAILY,
    CLOUD,
    NONE,
    ;

    var sourceID: Long = 0L
}
