package remix.myplayer.lyric

import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.reactivex.Observable
import io.reactivex.functions.Function
import remix.myplayer.App
import remix.myplayer.bean.kugou.KLrcResponse
import remix.myplayer.bean.kugou.KSearchResponse
import remix.myplayer.bean.misc.LyricPriority
import remix.myplayer.bean.mp3.Song
import remix.myplayer.bean.netease.NLrcResponse
import remix.myplayer.bean.netease.NSongSearchResponse
import remix.myplayer.lyric.bean.LrcRow
import remix.myplayer.misc.cache.DiskCache
import remix.myplayer.misc.tageditor.TagEditor
import remix.myplayer.request.network.HttpClient
import remix.myplayer.request.network.RxUtil
import remix.myplayer.util.*
import java.io.*
import java.nio.charset.Charset
import java.util.*

/**
 * Created by Remix on 2015/12/7.
 */

/**
 * 根据歌曲名和歌手名 搜索歌词并解析成固定格式
 */
class SearchLrc(private val song: Song) {
    private val lrcParser: ILrcParser
    private var displayName: String? = null
    private var cacheKey: String? = null
    private var searchKey: String? = null
    private var localLyricPath: String = ""


    init {
        try {
            if (!TextUtils.isEmpty(song.displayname)) {
                val temp = song.displayname
                displayName = if (temp.indexOf('.') > 0) temp.substring(0, temp.lastIndexOf('.')) else temp
            }
            searchKey = getLyricSearchKey(song)
        } catch (e: Exception) {
            LogUtil.d(TAG, e.toString())
            displayName = song.title
        }

        lrcParser = DefaultLrcParser()
    }

    /**
     * 根据歌词id,发送请求并解析歌词
     *
     * @return 歌词
     */
    fun getLyric(manualPath: String, clearCache: Boolean): Observable<List<LrcRow>> {
        val type = SPUtil.getValue(App.getContext(), SPUtil.LYRIC_KEY.NAME, song.id, SPUtil.LYRIC_KEY.LYRIC_DEFAULT)

        val observable = when (type) {
            SPUtil.LYRIC_KEY.LYRIC_IGNORE -> {
                LogUtil.d(TAG, "Ignore Lyric")
                Observable.error(Throwable("Ignore Lyric"))
            }
            SPUtil.LYRIC_KEY.LYRIC_EMBEDDED -> {
                getEmbeddedObservable()
            }
            SPUtil.LYRIC_KEY.LYRIC_LOCAL -> {
                getLocalObservable()
            }
            SPUtil.LYRIC_KEY.LYRIC_NETEASE -> {
                getNeteaseObservable()
            }
            SPUtil.LYRIC_KEY.LYRIC_KUGOU -> {
                getKuGouObservable()
            }
            SPUtil.LYRIC_KEY.LYRIC_MANUAL -> {
                getManualObservable(manualPath)
            }
            SPUtil.LYRIC_KEY.LYRIC_DEFAULT -> {
                //默认优先级排序 网易-酷狗-本地-内嵌
                val priority = Gson().fromJson<List<LyricPriority>>(SPUtil.getValue(App.getContext(), SPUtil.LYRIC_KEY.NAME, SPUtil.LYRIC_KEY.PRIORITY_LYRIC, SPUtil.LYRIC_KEY.DEFAULT_PRIORITY),
                        object : TypeToken<List<LyricPriority>>() {}.type)

                val observables = mutableListOf<Observable<List<LrcRow>>>()
                priority.forEach {
                    when (it.priority) {
                        LyricPriority.NETEASE.priority -> observables.add(getNeteaseObservable())
                        LyricPriority.KUGOU.priority -> observables.add(getKuGouObservable())
                        LyricPriority.LOCAL.priority -> observables.add(getLocalObservable())
                        LyricPriority.EMBEDED.priority -> observables.add(getEmbeddedObservable())
                    }
                }
                Observable.concat(observables).firstOrError().toObservable()
            }
            else -> {
                Observable.error<List<LrcRow>>(Throwable("Not support type"))
            }
        }

        return if (isTypeAvailable(type)) Observable.concat(getCacheObservable(), observable)
                .firstOrError()
                .toObservable()
                .doOnSubscribe { disposable ->
                    localLyricPath = ""
                    cacheKey = Util.hashKeyForDisk(song.id.toString() + "-" +
                            (if (!TextUtils.isEmpty(song.artist)) song.artist else "") + "-" +
                            if (!TextUtils.isEmpty(song.title)) song.title else "")
                    LogUtil.d(TAG, "CacheKey: $cacheKey SearchKey: $searchKey")
                    if (clearCache) {
                        LogUtil.d(TAG, "clearCache")
                        DiskCache.getLrcDiskCache().remove(cacheKey)
                    }
                }.compose(RxUtil.applyScheduler())
        else
            observable
    }

    private fun isTypeAvailable(type: Int): Boolean {
        return type != SPUtil.LYRIC_KEY.LYRIC_IGNORE
    }

    /**
     * 根据歌词id,发送请求并解析歌词
     *
     * @return 歌词
     */
    fun getLyric(): Observable<List<LrcRow>> {
        return getLyric("", false)
    }

    /**
     * 内嵌歌词
     *
     * @return
     */
    private fun getEmbeddedObservable(): Observable<List<LrcRow>> {
        return Observable.create { e ->
            val lyric = TagEditor(song.url).lyric
            if (!lyric.isNullOrEmpty()) {
                e.onNext(lrcParser.getLrcRows(getBufferReader(lyric!!.toByteArray(UTF_8)), true, cacheKey, searchKey))
                LogUtil.d(TAG, "EmbeddedLyric")
            }
            e.onComplete()
        }
    }

    /**
     * 缓存
     */
    private fun getCacheObservable(): Observable<List<LrcRow>> {
        return Observable.create { e ->
            DiskCache.getLrcDiskCache().get(cacheKey)?.run {
                BufferedReader(InputStreamReader(getInputStream(0))).use {
                    it.readLine().run {
                        e.onNext(Gson().fromJson(this, object : TypeToken<List<LrcRow>>() {}.type))
                        LogUtil.d(TAG, "CacheLyric")
                    }
                }
            }
            e.onComplete()
        }
    }

    private val isCN: Boolean
        get() = "zh".equals(Locale.getDefault().language, ignoreCase = true)

    /**
     * 搜索本地所有歌词文件
     */
    private val allLocalLrcPath: List<String>
        get() {
            val results = ArrayList<String>()
//            val searchPath = SPUtil.getValue(App.getContext(), SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.LOCAL_LYRIC_SEARCH_DIR, "")
            //没有设置歌词路径 搜索所有可能的歌词文件
            App.getContext().contentResolver.query(MediaStore.Files.getContentUri("external"), null,
                    MediaStore.Files.FileColumns.DATA + " like ? or " +
                            MediaStore.Files.FileColumns.DATA + " like ? or " +
                            MediaStore.Files.FileColumns.DATA + " like ? or " +
                            MediaStore.Files.FileColumns.DATA + " like ? ",
                    getLocalSearchKey(),
                    null)
                    .use { filesCursor ->
                        while (filesCursor.moveToNext()) {
                            val file = File(filesCursor.getString(filesCursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)))
                            LogUtil.d(TAG, "file: " + file.absolutePath)
                            if (file.exists() && file.isFile && file.canRead()) {
                                //非翻译文件只保留一个
                                if (results.isEmpty() || (results.size >= 1 && file.absolutePath.contains("translate")))
                                    results.add(file.absolutePath)
                            }
                            if (results.size == 2)
                                break
                        }
                        return results
                    }
//            if (!TextUtils.isEmpty(searchPath)) {//已设置歌词搜索路径
//                localLyricPath = LyricUtil.searchLyric(displayName, song.title, song.artist, File(searchPath))
//                if (!TextUtils.isEmpty(localLyricPath))
//                    results.add(localLyricPath)
//                return results
//            } else {
//                //没有设置歌词路径 搜索所有可能的歌词文件
//                App.getContext().contentResolver.query(MediaStore.Files.getContentUri("external"), null,
//                        MediaStore.Files.FileColumns.DATA + " like ? or " +
//                                MediaStore.Files.FileColumns.DATA + " like ? or " +
//                                MediaStore.Files.FileColumns.DATA + " like ? or " +
//                                MediaStore.Files.FileColumns.DATA + " like ? ",
//                        getLocalSearchKey(),
//                        null)
//                        .use { filesCursor ->
//                            while (filesCursor.moveToNext()) {
//                                val file = File(filesCursor.getString(filesCursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)))
//                                LogUtil.d(TAG,"file: " + file.absolutePath)
//                                if (file.exists() && file.isFile && file.canRead()) {
//                                    results.add(file.absolutePath)
//                                }
//                            }
//                            return results
//                        }
//            }

        }

    /**
     * @param searchPath 设置的本地歌词搜索路径
     * 本地歌词搜索的关键字
     * displayName.lrc
     * title.lrc
     * artist-title.lrc
     * aritst-displayName.lrc
     */
    private fun getLocalSearchKey(searchPath: String? = null): Array<String> {
        return arrayOf("%$displayName%$SUFFIX_LYRIC",
                "%" + song.title + "%" + SUFFIX_LYRIC,
                "%" + song.Artist + "%" + song.Title + "%" + SUFFIX_LYRIC,
                "%" + song.Artist + "%" + displayName + "%" + SUFFIX_LYRIC)
    }

    /**
     * 网络或者本地歌词
     *
     * @param type
     * @return
     */
    @Deprecated("")
    private fun getContentObservable(type: Int): Observable<List<LrcRow>> {
        val networkObservable = getNetworkObservable(type)
        val localObservable = getLocalObservable()
        val onlineFirst = SPUtil.getValue(App.getContext(), SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.ONLINE_LYRIC_FIRST, false)
        return Observable.concat(if (onlineFirst) networkObservable else localObservable, if (onlineFirst) localObservable else networkObservable).firstOrError().toObservable()
    }

    /**
     * 手动设置歌词
     */
    private fun getManualObservable(manualPath: String): Observable<List<LrcRow>> {
        return Observable.create { e ->
            //手动设置的歌词
            if (!TextUtils.isEmpty(manualPath)) {
                LogUtil.d(TAG, "ManualLyric")
                e.onNext(lrcParser.getLrcRows(getBufferReader(manualPath), true, cacheKey, searchKey))
            }
            e.onComplete()
        }
    }


    /**
     * 本地歌词
     *
     * @param type
     * @return
     */
    private fun getLocalObservable(): Observable<List<LrcRow>> {
        return Observable.create { e ->
            val localPaths = ArrayList<String>(allLocalLrcPath)
            if (localPaths.isEmpty()) {
                e.onComplete()
                return@create
            }
            if (localPaths.size > 1 && isCN) {
                var localPath = ""
                var translatePath: String? = null
                for (path in localPaths) {
                    if (path.contains("translate") && path != localPath) {
                        translatePath = path
                    } else {
                        localPath = path
                    }
                }
                LogUtil.d(TAG, "LocalLyric")
                if (translatePath == null) {
                    e.onNext(lrcParser.getLrcRows(getBufferReader(localPath), true, cacheKey, searchKey))
                } else {
                    //合并歌词
                    val source = lrcParser.getLrcRows(getBufferReader(localPath), false, cacheKey, searchKey)
                    val translate = lrcParser.getLrcRows(getBufferReader(translatePath), false, cacheKey, searchKey)
                    if (translate != null && translate.size > 0) {
                        for (i in translate.indices) {
                            for (j in source.indices) {
                                if (isTranslateCanUse(translate[i].content) && translate[i].time == source[j].time) {
                                    source[j].translate = translate[i].content
                                    break
                                }
                            }
                        }
                        lrcParser.saveLrcRows(source, cacheKey, searchKey)
                        e.onNext(source)
                    }
                }
            } else {
                e.onNext(lrcParser.getLrcRows(getBufferReader(localPaths[0]), true, cacheKey, searchKey))
            }
            e.onComplete()
        }
    }

    /**
     * 网易歌词
     */
    private fun getNeteaseObservable(): Observable<List<LrcRow>> {
        return HttpClient.getNeteaseApiservice()
                .getNeteaseSearch(searchKey, 0, 1, 1)
                .flatMap { it ->
                    HttpClient.getInstance()
                            .getNeteaseLyric(Gson().fromJson(it.string(), NSongSearchResponse::class.java).result.songs[0].id)
                            .map {
                                val lrcResponse = Gson().fromJson(it.string(), NLrcResponse::class.java)
                                val combine = lrcParser.getLrcRows(getBufferReader(lrcResponse.lrc.lyric.toByteArray()), false, cacheKey, searchKey)
                                if (isCN && lrcResponse.tlyric != null && !lrcResponse.tlyric.lyric.isNullOrEmpty()) {
                                    val translate = lrcParser.getLrcRows(getBufferReader(lrcResponse.tlyric.lyric.toByteArray()), false, cacheKey, searchKey)
                                    if (translate != null && translate.size > 0) {
                                        for (i in translate.indices) {
                                            for (j in combine.indices) {
                                                if (translate[i].time == combine[j].time) {
                                                    combine[j].translate = translate[i].content
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                LogUtil.d(TAG, "NeteaseLyric")
                                lrcParser.saveLrcRows(combine, cacheKey, searchKey)
                                combine
                            }
                }.onErrorResumeNext(Function {
                    Observable.empty()
                })
    }

    /**
     * 酷狗歌词
     */
    private fun getKuGouObservable(): Observable<List<LrcRow>> {
        //酷狗歌词
        return HttpClient.getKuGouApiservice().getKuGouSearch(1, "yes", "pc", searchKey, song.duration, "")
                .flatMap { body ->
                    val searchResponse = Gson().fromJson(body.string(), KSearchResponse::class.java)
                    HttpClient.getKuGouApiservice().getKuGouLyric(1, "pc", "lrc", "utf8",
                            searchResponse.candidates[0].id,
                            searchResponse.candidates[0].accesskey)
                            .map { lrcBody ->
                                val lrcResponse = Gson().fromJson(lrcBody.string(), KLrcResponse::class.java)
                                LogUtil.d(TAG, "KugouLyric")
                                lrcParser.getLrcRows(getBufferReader(Base64.decode(lrcResponse.content, Base64.DEFAULT)), true, cacheKey, searchKey)
                            }
                }
                .onErrorResumeNext(Function {
                    Observable.empty()
                })
    }

    /**
     * 在线歌词
     *
     * @param type
     * @return
     */
    @Deprecated("")
    private fun getNetworkObservable(type: Int): Observable<List<LrcRow>> {
        var newType = type

        if (TextUtils.isEmpty(searchKey)) {
            return Observable.error(Throwable("no available key"))
        }
        if (newType == SPUtil.LYRIC_KEY.LYRIC_DEFAULT)
            newType = SPUtil.LYRIC_KEY.LYRIC_NETEASE
        if (newType == SPUtil.LYRIC_KEY.LYRIC_KUGOU) {
            //酷狗歌词
            return getKuGouObservable()
        } else {
            //网易歌词
            return getNeteaseObservable()
        }
    }

    private fun isTranslateCanUse(translate: String): Boolean {
        return !TextUtils.isEmpty(translate) && !translate.startsWith("词") && !translate.startsWith("曲")
    }

    /**
     * 获得搜索歌词的关键字
     *
     * @param song
     * @return
     */
    private fun getLyricSearchKey(song: Song?): String {
        if (song == null)
            return ""
        val isTitleAvailable = !ImageUriUtil.isSongNameUnknownOrEmpty(song.title)
        val isAlbumAvailable = !ImageUriUtil.isAlbumNameUnknownOrEmpty(song.album)
        val isArtistAvailable = !ImageUriUtil.isArtistNameUnknownOrEmpty(song.artist)

        //歌曲名合法
        return if (isTitleAvailable) {
            when {
                isArtistAvailable -> song.artist + "-" + song.title //艺术家合法
                isAlbumAvailable -> //专辑名合法
                    song.album + "-" + song.title
                else -> song.title
            }
        } else ""
    }

    @Throws(FileNotFoundException::class, UnsupportedEncodingException::class)
    private fun getBufferReader(path: String): BufferedReader {
        return BufferedReader(InputStreamReader(FileInputStream(path), LyricUtil.getCharset(path)))
    }

    private fun getBufferReader(bytes: ByteArray): BufferedReader {
        return BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), UTF_8))
    }

    companion object {
        private const val TAG = "SearchLrc"
        private const val SUFFIX_LYRIC = ".lrc"
        private val UTF_8 = Charset.forName("UTF-8")
    }
}
