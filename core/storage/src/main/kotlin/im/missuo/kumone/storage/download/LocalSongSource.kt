// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.download

/**
 * 离线音源查询接口（零改 ExoPlayer 的离线播放关键）。
 *
 * [SongUrlResolver] 在解析播放地址时优先询问本接口：若命中本地文件，直接返回
 * `file://` URI，ExoPlayer 即可从本地读取，无需任何播放器层改动。
 */
interface LocalSongSource {

    /**
     * 该曲目在指定音质下若存在本地文件，返回 `file://` URI；否则返回 null。
     */
    fun localUri(trackId: Long, level: String): String?

    /**
     * 该曲目任意已下载音质下的本地 `file://` URI（用于离线播放兜底）；无则 null。
     */
    fun localUriAny(trackId: Long): String?
}
