/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.net.ct;

import static com.android.server.net.ct.Config.TAG;

import android.annotation.RequiresApi;
import android.app.AlarmManager;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ConfigUpdate;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Implementation of the Certificate Transparency job */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class CertificateTransparencyJob extends BroadcastReceiver {

    private final Context mContext;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;
    private final SignatureVerifier mSignatureVerifier;
    private final Collection<CompatibilityVersion> mCompatVersions;
    private final AlarmManager mAlarmManager;
    private final PendingIntent mPendingIntent;
    private final IntentFilter mUpdateLogsIntentFilter;
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    private boolean mScheduled = false;
    private boolean mDependenciesReady = false;

    /** Creates a new {@link CertificateTransparencyJob} object. */
    public CertificateTransparencyJob(
            Context context,
            CertificateTransparencyDownloader certificateTransparencyDownloader,
            SignatureVerifier signatureVerifier,
            Collection<CompatibilityVersion> compatVersions) {
        mContext = context;
        mCertificateTransparencyDownloader = certificateTransparencyDownloader;
        mSignatureVerifier = signatureVerifier;
        mCompatVersions = compatVersions;

        mAlarmManager = context.getSystemService(AlarmManager.class);
        mPendingIntent =
                PendingIntent.getBroadcast(
                        mContext,
                        /* requestCode= */ 0,
                        new Intent(ConfigUpdate.ACTION_UPDATE_CT_LOGS)
                                .setPackage(mContext.getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE);
        mUpdateLogsIntentFilter = new IntentFilter(ConfigUpdate.ACTION_UPDATE_CT_LOGS);
    }

    void schedule() {
        synchronized (this) {
            if (!mScheduled) {
                mCertificateTransparencyDownloader.checkDownloadRequests();
                mContext.registerReceiver(
                        this,
                        mUpdateLogsIntentFilter,
                        Context.RECEIVER_EXPORTED);
                mAlarmManager.setInexactRepeating(
                        AlarmManager.ELAPSED_REALTIME,
                        SystemClock
                                .elapsedRealtime(), // schedule first job at earliest convenient time.
                        AlarmManager.INTERVAL_DAY,
                        mPendingIntent);
                mScheduled = true;
                if (Config.DEBUG) {
                    Log.d(TAG, "CertificateTransparencyJob scheduled.");
                }
            } else {
                if (Config.DEBUG) {
                    Log.d(TAG, "CertificateTransparencyJob is already scheduled.");
                }
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!mUpdateLogsIntentFilter.hasAction(intent.getAction())) {
            Log.w(TAG, "Received unexpected broadcast with action " + intent);
            return;
        }
        Future<?> unused =
                mExecutorService.submit(
                        intent.getBooleanExtra("delete", false)
                                ? this::deleteLogListDirectory
                                : this::startJob);
    }

    private void startJob() {
        if (Config.DEBUG) {
            Log.d(TAG, "Starting CT daily job.");
        }
        if (!mDependenciesReady) {
            startDependencies();
            mDependenciesReady = true;
        }

        if (mCertificateTransparencyDownloader.startPublicKeyDownload() == -1) {
            Log.e(TAG, "Public key download not started.");
        } else if (Config.DEBUG) {
            Log.d(TAG, "Public key download started successfully.");
        }
    }

    private void deleteLogListDirectory() {
        Log.i(TAG, "Deleting log list directory.");
        DirectoryUtils.removeDir(new File(Config.CT_ROOT_DIRECTORY_PATH));
    }

    private void startDependencies() {
        mSignatureVerifier.loadAllowedKeys();
        mContext.registerReceiver(
                mCertificateTransparencyDownloader,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED);

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyJob dependencies ready.");
        }
    }

    private void stopDependencies() {
        mContext.unregisterReceiver(mCertificateTransparencyDownloader);
        mSignatureVerifier.clearAllowedKeys();

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyJob dependencies stopped.");
        }
    }
}
