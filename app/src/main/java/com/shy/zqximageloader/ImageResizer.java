package com.shy.zqximageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.FileDescriptor;

/**
 * Created by Administrator on 2016/10/28.
 */

public class ImageResizer {
    private static final String TAG = "ImageResizer";

    public ImageResizer() {

    }

    public Bitmap decodeSampleBitmapFromResource(Resources res, int resId, int reqWidth, int reqHeight) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);
        options.inSampleSize = caculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeResource(res,resId,options);
    }

    private int caculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        if(reqHeight==0||reqWidth==0){
            return 1;
        }
        final int height = options.outHeight;
        final int width = options.outWidth;
        Log.d(TAG,"origin,w="+width+",h="+height);
        int inSampleSize = 1;
        if (reqWidth > width || reqHeight > height) {
            final int halfWidth = reqWidth / 2;
            final int halfHeight = reqHeight / 2;
            while ((halfHeight / inSampleSize) > reqHeight && (halfWidth / inSampleSize > reqWidth)) {
                inSampleSize *= 2;
            }
        }
        Log.d(TAG,"sampleSize="+inSampleSize);
        return inSampleSize;
    }

    public Bitmap decodeSampledBitmapFromFileDescriptor(FileDescriptor fd,int reqWidth,int reqHeight){
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd,null, options);
        options.inSampleSize = caculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeFileDescriptor(fd,null,options);
    }
}
