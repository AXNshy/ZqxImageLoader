package com.shy.zqximageloader;

import android.graphics.Bitmap;
import android.widget.ImageView;

/**
 * Created by Administrator on 2016/10/28.
 */

public class LoaderResult {
    public ImageView imageView;

    public String url;

    public Bitmap bitmap;

    public LoaderResult(Bitmap bitmap, ImageView imageView, String url) {
        this.bitmap = bitmap;
        this.imageView = imageView;
        this.url = url;
    }
}
