package com.shy.zqximageloader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Created by Administrator on 2016/10/28.
 */

public class zqxImageLoader {
    private static final int MESSAGE_POST_RESULT = 1;


    private static final int DISK_CACHE_SIZE = 100 * 1024 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final String TAG = "zqxImageLoader";
    private static final int TAG_KEY_URI = 0x123456;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;


    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;
    private Context mContext;
    private boolean mIsDiskLruCacheCreate = false;

    private ImageResizer mImageResizer = new ImageResizer();

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    /**
     * 构造一个线程池，核心线程数量为CORE_POOL_SIZE，最大线程数量为MAXIMUM_POOL_SIZE，线程保活时间为KEEP_ALIVE
     * 时间单位为秒，线程队列为一个LinkedBlockingDeque，线程工厂为sThreadFactory
     */
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE
            , KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingDeque<Runnable>(), sThreadFactory);


    /**
     * 这是UI线程的handler
     */
    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            String url = (String) imageView.getTag(TAG_KEY_URI);
            if (url.equals(result.url)){
                imageView.setImageBitmap(result.bitmap);
            }else {
                Log.w(TAG,"set image bitmap,but url has changed,ignore");
            }
        }
    };

    public static class Builder{
        public static zqxImageLoader build(Context context){
            return new zqxImageLoader(context);
        }
    }


    private zqxImageLoader(Context context) {
        mContext = context;
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {

                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };
        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreate = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 获取当前设备可用的本地存储空间大小
     * @param diskCacheDir
     * @return
     */
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File diskCacheDir) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return diskCacheDir.getUsableSpace();
        }
        final StatFs stats = new StatFs(diskCacheDir.getPath());
        return stats.getBlockSize() * stats.getAvailableBlocks();
    }

    /**
     * 获取本地缓存路径
     * @param context
     * @param uniqueName
     * @return
     */
    private File getDiskCacheDir(Context context, String uniqueName) {
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 添加bitmap到内存缓存当中
     * @param key
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    /**
     * 根据key从LruCache中获取Bitmap
     *
     * @param key
     * @return
     */
    private Bitmap getBitmapFromMemCache(String key) {
        return mMemoryCache.get(key);
    }

    /**
     * 从网络上加载数据，首先存放到本地缓存中，再从本地缓存中加载出bitmap对象{@loadBitmapFromDiskCache))
     *
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     * @throws IOException
     */
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("can not visit network from UI Thread");
        }
        if (mDiskLruCache == null)
            return null;

        String key = hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            //从url获取到数据流，并放到editor中的outputStream中
            if (downloadUrlToStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    /**
     * 下载数据到输出流outputStream中
     *
     * @param urlString
     * @param outputStream
     * @return
     */
    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection connection = null;
        BufferedInputStream input = null;
        BufferedOutputStream output = null;
        try {
            final URL url = new URL(urlString);

            connection = (HttpURLConnection) url.openConnection();
            input = new BufferedInputStream(connection.getInputStream(), IO_BUFFER_SIZE);
            output = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int b;
            while ((b = input.read()) != -1) {
                output.write(b);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG, "Image Download failed");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (input != null)
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            if (output != null)
                try {
                    output.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
        return false;
    }

    /**
     * 从本地缓存中加载Bitmap
     *
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     * @throws IOException
     */
    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("can not visit network from UI Thread");
        }
        if (mDiskLruCache == null)
            return null;
        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor descriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampledBitmapFromFileDescriptor(descriptor, reqWidth, reqHeight);
            if (bitmap != null) {
                addBitmapToMemoryCache(key, bitmap);
            }

        }
        return bitmap;

    }

    /**
     * 同步加载接口
     *
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap loadBitmap(String url, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(url);
        if (bitmap != null) {
            Log.d(TAG, "loadBitmapFromMemCache,url=" + url.toString());
            return bitmap;
        }
        try {
            bitmap = loadBitmapFromDiskCache(url, reqWidth, reqHeight);
            if (bitmap != null) {
                Log.d(TAG, "loadBitmapFromDiskCache,url=" + url.toString());
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(url, reqWidth, reqHeight);
            Log.d(TAG, "loadBitmapFromHttp,url=" + url.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bitmap == null && !mIsDiskLruCacheCreate) {
            Log.w(TAG, "encounter error ,DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(url);
        }
        return bitmap;
    }

    /**
     * 异步加载接口
     *
     * @param url
     * @param imageView
     * @param reqWidth
     * @param reqHeight
     */
    public void bindBitmap(final String url, final ImageView imageView, final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URI, url);
        Bitmap bitmap = loadBitmapFromMemCache(url);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmaTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(url, reqWidth, reqHeight);
                if (bitmap != null) {
                    LoaderResult result = new LoaderResult(bitmap,imageView, url );
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result).
                    sendToTarget();
                }
            }
        };

        THREAD_POOL_EXECUTOR.execute(loadBitmaTask);
    }

    /**
     * 直接通过Url获取一个InputStream，并使用BitmapFactory.decodeStream来构造Bitmap对象
     *
     * @param urlString
     * @return
     */
    private Bitmap downloadBitmapFromUrl(String urlString) {
        Bitmap bitmap = null;
        HttpURLConnection connect = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);

            connect = (HttpURLConnection) url.openConnection();

            in = new BufferedInputStream(connect.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            Log.e(TAG, "Error in downloading Bitmap:" + e);
            e.printStackTrace();
        } finally {
            if (connect != null) {
                connect.disconnect();
            }
            try {
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return bitmap;
    }

    /**
     * 通过内存加载bitmap
     * @param url
     * @return
     */
    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = hashKeyFromUrl(url);
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;
    }


    /**
     * 获取一个url链接的hash值
     * @param url
     * @return
     */
    private String hashKeyFromUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = byteToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }


    private String byteToHexString(byte[] digest) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            String hex = Integer.toHexString(0xFF & digest[i]);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }
}
