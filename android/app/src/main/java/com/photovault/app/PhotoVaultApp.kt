package com.photovault.app

import android.app.Application
import android.net.Uri
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.video.VideoFrameDecoder
import com.photovault.app.backup.BackupWorker
import com.photovault.app.data.Repo
import okio.Path.Companion.toOkioPath

class PhotoVaultApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        BackupWorker.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { Repo.api.client }))
            add(VideoFrameDecoder.Factory())
            add(LocalThumbKeyer())
            add(LocalThumbFetcher.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.3).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images").toOkioPath()).maxSizeBytes(1500L * 1024 * 1024).build() }
        .build()
}

/** Grid thumbnail of a phone photo/video, using Android's fast built-in thumbnails. */
data class LocalThumb(val uri: Uri, val size: Int = 384)

class LocalThumbKeyer : Keyer<LocalThumb> {
    override fun key(data: LocalThumb, options: Options) = "${data.uri}#${data.size}"
}

class LocalThumbFetcher(private val data: LocalThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bmp = options.context.contentResolver.loadThumbnail(data.uri, android.util.Size(data.size, data.size), null)
        return ImageFetchResult(image = bmp.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<LocalThumb> {
        override fun create(data: LocalThumb, options: Options, imageLoader: ImageLoader): Fetcher = LocalThumbFetcher(data, options)
    }
}
