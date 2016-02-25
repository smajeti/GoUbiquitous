package com.example.android.sunshine.app.sync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.MainActivity;
import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

public class SunshineSyncService extends Service {
    private static final Object sSyncAdapterLock = new Object();
    private static SunshineSyncAdapter sSunshineSyncAdapter = null;
    private static WearUpdater sWearUpdater = null;

    @Override
    public void onCreate() {
        Log.d("SunshineSyncService", "onCreate - SunshineSyncService");
        synchronized (sSyncAdapterLock) {
            if (sSunshineSyncAdapter == null) {
                sSunshineSyncAdapter = new SunshineSyncAdapter(getApplicationContext(), true);
            }

            if (sWearUpdater == null) {
                sWearUpdater = new WearUpdater(getApplicationContext());
                sSunshineSyncAdapter.setWearNotifyHandler(sWearUpdater);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sWearUpdater != null) {
            if (sSunshineSyncAdapter != null) {
                sSunshineSyncAdapter.setWearNotifyHandler(null);
            }
            sWearUpdater.cleanup();
            sWearUpdater = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return sSunshineSyncAdapter.getSyncAdapterBinder();
    }

    class WearUpdater implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            NodeApi.NodeListener, SunshineSyncAdapter.WearNotifyIface {

        private final String LOG_TAG = WearUpdater.class.getSimpleName();
        private GoogleApiClient mGoogleApiClient;
        private Context context;

        public WearUpdater(Context applicationContext) {
            this.context = applicationContext;
            mGoogleApiClient = new GoogleApiClient.Builder(applicationContext)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();
        }

        public void cleanup() {
            if (mGoogleApiClient.isConnected()) {
                Wearable.NodeApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }
        }

        // Connection related functions

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "onConnected");
            Wearable.NodeApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "onConnectionSuspended");
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
            Wearable.NodeApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void notifyWearDevices() {
            Log.d(LOG_TAG, "notifyWearDevices");
            new SendForecastAsync().execute();
        }

        class SendForecastAsync extends AsyncTask<Void, Void, Void> {
            @Override
            protected Void doInBackground(Void... voids) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                String lastLowTempKey = context.getString(R.string.pref_last_low_temp);
                String lastHighTempKey = context.getString(R.string.pref_last_high_temp);
                String lastTempIconIdKey = context.getString(R.string.pref_last_temperature_icon_id);
                String prefLowTempStr = prefs.getString(lastLowTempKey, "");
                String prefHighTempStr = prefs.getString(lastHighTempKey, "");
                int prefIconId = prefs.getInt(lastTempIconIdKey, -1);

                SunshineSyncAdapter.WeatherInfo weatherInfo = SunshineSyncAdapter.getCurrentWeatherInfo(context);
                String latestLowTemp = Utility.formatTemperature(context, weatherInfo.lowTemperature);
                String latestHighTemp = Utility.formatTemperature(context, weatherInfo.highTemperature);
                int latestIconId = Utility.getIconResourceForWeatherCondition(weatherInfo.weatherId);

                // if none one of the params does not match with what we already sent then resend them
                if (!(prefLowTempStr.equals(latestLowTemp) &&
                        prefHighTempStr.equals(latestHighTemp) && (prefIconId == latestIconId))) {

                    Log.d(LOG_TAG, "SendForecastAsync lastLow = " + prefLowTempStr + " currentHigh = " + latestHighTemp + " currIcon = " + latestIconId);
                    Utility.sendCurrentForecastToWear(context, mGoogleApiClient, weatherInfo);

                    // update preference
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString(lastLowTempKey, latestLowTemp);
                    editor.putString(lastHighTempKey, latestHighTemp);
                    editor.putInt(lastTempIconIdKey, latestIconId);
                    editor.commit();
                }

                return null;
            }
        }

    }
}