package com.bloomfield.mupdfdemo;

import android.content.Context;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

/**
 * Created by bloomfield on 2015-09-20.
 */
public class ZoomScroller extends android.widget.Scroller {
    Interpolator mInterpolator;
    float mCurrScale;
    float mStartScale;
    float mdScale;

    public ZoomScroller(Context context) {
        this(context, null);
    }
    public ZoomScroller(Context context, Interpolator interpolator) {
        super(context, (interpolator == null)? interpolator = new LinearInterpolator(): interpolator);
        mInterpolator = interpolator;
    }

    @Override
    public void abortAnimation() {
        super.abortAnimation();
    }

    @Override
    public boolean computeScrollOffset() {
        float progress = mInterpolator.getInterpolation((float)timePassed()/getDuration());
        mCurrScale = mStartScale + (mdScale)*progress;
        if(mCurrScale > mStartScale + mdScale) mCurrScale = mStartScale + mdScale;
        return super.computeScrollOffset();
    }

    public final float getCurrScale() {
        return mCurrScale;
    }

    public void startZoom(int startX, int startY, int dx, int dy, float startScale, float endScale) {
        super.startScroll(startX, startY, dx, dy);
        mStartScale = startScale;
        mdScale = (endScale - startScale);
    }
    public void startZoom(int startX, int startY, int dx, int dy, float startScale, float endScale, int duration) {
        super.startScroll(startX, startY, dx, dy, duration);
        mStartScale = startScale;
        mdScale = (endScale - startScale);
    }

    @Override
    public void startScroll(int startX, int startY, int dx, int dy) {
        super.startScroll(startX, startY, dx, dy);
        mStartScale = -1;
        mdScale = 0;
    }

    @Override
    public void startScroll(int startX, int startY, int dx, int dy, int duration) {
        super.startScroll(startX, startY, dx, dy, duration);
        mStartScale = -1;
        mdScale = 0;
    }

    @Override
    public void fling(int startX, int startY, int velocityX, int velocityY, int minX, int maxX, int minY, int maxY) {
        super.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY);
        mStartScale = -1;
        mdScale = 0;
    }
}
