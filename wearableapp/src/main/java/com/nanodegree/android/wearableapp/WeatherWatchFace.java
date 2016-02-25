/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nanodegree.android.wearableapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {

    private final String LOG_TAG = WeatherWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    public static final String GET_FORECAST_PATH = "/weather-forecast-get";
    public static final String POST_FORECAST_PATH = "/weather-forecast-post";
    public static final String TIMELINE_KEY = "timeline";
    public static final String CURRENT_TIME_VAL = "current-time";
    public static final String TIMESTAMP_KEY = "timestamp";
    public static final String LOW_TEMP_KEY = "low-temperature";
    public static final String HIGH_TEMP_KEY = "high-temperature";
    public static final String ICON_KEY = "weather-icon";

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(WeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener, NodeApi.NodeListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mPrimaryTextPaint;
        Paint mSecondaryTextPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        Paint mGrayTempPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mXOffset;
        float mYOffset;
        float mPrimaryTextSize;
        float mSecondaryTextSize;
        float mTempTextSize;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private Bitmap mWeatherBitMap;

        private GoogleApiClient mGoogleApiClient;
        private String temperatureLow;
        private String temperatureHigh;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = WeatherWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background_color));

            mPrimaryTextPaint = createTextPaint(resources.getColor(R.color.primary_text_color));
            mSecondaryTextPaint = createTextPaint(resources.getColor(R.color.secondary_text_color));
            mHighTempPaint = createTextPaint(resources.getColor(R.color.primary_text_color));
            mLowTempPaint = createTextPaint(resources.getColor(R.color.secondary_text_color));
            mGrayTempPaint = createTextPaint(resources.getColor(R.color.gray_temp_text_color));

            mWeatherBitMap = null;

            mGoogleApiClient = new GoogleApiClient.Builder(WeatherWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Wearable.NodeApi.addListener(mGoogleApiClient, this);

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (mGoogleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                Wearable.NodeApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mPrimaryTextSize = resources.getDimension(isRound
                    ? R.dimen.primary_text_size_round : R.dimen.primary_text_size_square);
            mSecondaryTextSize = resources.getDimension(isRound
                    ? R.dimen.secondary_text_size_round : R.dimen.secondary_text_size_square);
            mTempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size_square);

            mPrimaryTextPaint.setTextSize(mPrimaryTextSize);
            mSecondaryTextPaint.setTextSize(mSecondaryTextSize);
            mHighTempPaint.setTextSize(mTempTextSize);
            mLowTempPaint.setTextSize(mTempTextSize);
            mGrayTempPaint.setTextSize(mTempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mPrimaryTextPaint.setAntiAlias(!inAmbientMode);
                    mHighTempPaint.setAntiAlias(!inAmbientMode);
                    mGrayTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String primaryText = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            Rect primaryTextBounds = new Rect();
            mPrimaryTextPaint.getTextBounds(primaryText, 0, primaryText.length(), primaryTextBounds);

            String secondaryText = null;
            Rect secondaryTextBounds = new Rect();
            if (!mAmbient) {
                secondaryText = mTime.format("%a, %b %d %Y");
                mSecondaryTextPaint.getTextBounds(secondaryText, 0, secondaryText.length(), secondaryTextBounds);
            }

            //float timeXLen = mPrimaryTextPaint.measureText(text);

            mYOffset = (bounds.height() / 2.0f) - primaryTextBounds.height();


            float xoffsest = (bounds.width() - primaryTextBounds.width()) / 2.0f;
            canvas.drawText(primaryText, xoffsest, mYOffset, mPrimaryTextPaint);

            Rect lowTempTextBounds = new Rect();
            Rect highTempTextBounds = new Rect();
            if (temperatureHigh != null) {
                mHighTempPaint.getTextBounds(temperatureHigh, 0, temperatureHigh.length(), highTempTextBounds);
            }
            if (temperatureLow != null) {
                mLowTempPaint.getTextBounds(temperatureLow, 0, temperatureLow.length(), lowTempTextBounds);
            }

            if (!mAmbient) {
                xoffsest = (bounds.width() - secondaryTextBounds.width()) / 2.0f;
                mYOffset += 1.5f * secondaryTextBounds.height();
                canvas.drawText(secondaryText.toUpperCase(), xoffsest, mYOffset, mSecondaryTextPaint);

                if (mWeatherBitMap != null) {
                    mYOffset += secondaryTextBounds.height();
                    xoffsest = (bounds.width() - mWeatherBitMap.getWidth() - (1.5f * highTempTextBounds.width()) - (1.5f * lowTempTextBounds.width())) / 2.0f;
                    canvas.drawBitmap(mWeatherBitMap, xoffsest, mYOffset, null);

                    if ((temperatureHigh != null) && (temperatureLow != null)) {
                        mYOffset += (mWeatherBitMap.getHeight() + highTempTextBounds.height()) / 2.0f;
                        xoffsest += mWeatherBitMap.getWidth() + (0.5f * highTempTextBounds.width());
                        canvas.drawText(temperatureHigh, xoffsest, mYOffset, mHighTempPaint);

                        xoffsest += 1.25f * highTempTextBounds.width();
                        canvas.drawText(temperatureLow, xoffsest, mYOffset, mLowTempPaint);
                    }
                }
            } else if ((temperatureHigh != null) && (temperatureLow != null)) {
                xoffsest = (bounds.width() - highTempTextBounds.width() - (1.5f * lowTempTextBounds.width())) / 2.0f;
                mYOffset = (bounds.height() / 2.0f) + (1.5f * highTempTextBounds.height());
                canvas.drawText(temperatureHigh, xoffsest, mYOffset, mHighTempPaint);
                xoffsest += 1.5f * highTempTextBounds.width();
                canvas.drawText(temperatureLow, xoffsest, mYOffset, mGrayTempPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        // Connection related functions

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "onConnected");
            if (temperatureLow == null) {
                new ForecastRequestAsync().execute();
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "onConnectionSuspended");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged " + dataEventBuffer);
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    String path = event.getDataItem().getUri().getPath();
                    if (POST_FORECAST_PATH.equals(path)) {
                        DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                        temperatureLow = dataMapItem.getDataMap().getString(LOW_TEMP_KEY);
                        temperatureHigh = dataMapItem.getDataMap().getString(HIGH_TEMP_KEY);
                        Asset asset = dataMapItem.getDataMap().getAsset(ICON_KEY);
                        new LoadBitmapAsyncTask().execute(asset);
                        Log.d(LOG_TAG, "l: " + temperatureLow + " h: " + temperatureHigh);
                    }
                }
            }
        }

        @Override
        public void onPeerConnected(Node node) {
            Log.d(LOG_TAG, "onPeerConnected");
        }

        @Override
        public void onPeerDisconnected(Node node) {
            Log.d(LOG_TAG, "onPeerDisconnected");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "onConnectionFailed");
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            Wearable.NodeApi.removeListener(mGoogleApiClient, this);
        }

        class ForecastRequestAsync extends AsyncTask<Void, Void, Void> {
            @Override
            protected Void doInBackground(Void... voids) {
                sendRequestForLatestWeather();
                return null;
            }

            public void sendRequestForLatestWeather() {
                PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(GET_FORECAST_PATH);
                putDataMapRequest.getDataMap().putString(TIMELINE_KEY, CURRENT_TIME_VAL);
                putDataMapRequest.getDataMap().putLong(TIMESTAMP_KEY, new Date().getTime());

                final PutDataRequest request = putDataMapRequest.asPutDataRequest();

                Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                        .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                            @Override
                            public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                                if (dataItemResult.getStatus().isSuccess()) {
                                    Log.d(LOG_TAG, "Successfully sent forecast request ");
                                } else {
                                    Log.d(LOG_TAG, "Failed to send forecast request ");
                                }
                            }
                        });
            }
        }

        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {

                if(params.length > 0) {

                    Asset asset = params[0];
                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.w(LOG_TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e(LOG_TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if(bitmap != null) {
                    Log.d(LOG_TAG, "Setting weather image..");
                    mWeatherBitMap = bitmap;
                }
            }
        }

    }
}
