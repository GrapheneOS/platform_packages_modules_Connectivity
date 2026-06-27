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

import static android.ext.settings.CertTransparencyDownloaderSetting.VAL_OFF;
import static com.android.server.net.ct.Config.TAG;

import android.annotation.RequiresApi;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.ext.settings.CertTransparencyDownloaderSetting;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.modules.utils.BackgroundThread;
import com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState;
import com.android.server.net.ct.DownloadHelper.DownloadStatus;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Helper class to download certificate transparency log files. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final Intent INSTALL_COMPLETE = new Intent(Config.INSTALL_COMPLETE_ACTION);

    private final Context mContext;
    private final DownloadHelper mDownloadHelper;
    private final SignatureVerifier mSignatureVerifier;
    private final CertificateTransparencyLogger mLogger;
    private final Collection<CompatibilityVersion> mCompatVersions;

    private final Map<String, Long> mDownloadIds = new HashMap<>();

    CertificateTransparencyDownloader(
            Context context,
            DownloadHelper downloadHelper,
            SignatureVerifier signatureVerifier,
            CertificateTransparencyLogger logger,
            Collection<CompatibilityVersion> compatVersions) {
        mContext = context;
        mSignatureVerifier = signatureVerifier;
        mDownloadHelper = downloadHelper;
        mLogger = logger;
        mCompatVersions = compatVersions;

        CertTransparencyDownloaderSetting.SETTING.registerObserver(context, BackgroundThread.getHandler(), setting -> {
            int newValue = CertTransparencyDownloaderSetting.SETTING.get(mContext);
            Log.i(TAG, "setting value changed to " + newValue + ", re-checking pending downloads");
            checkDownloadRequests();
            if (newValue != VAL_OFF) {
                Log.i(TAG, "starting new download sequence");
                startPublicKeyDownload();
            }
        });
    }

    void checkDownloadRequests() {
        mDownloadHelper.checkDownloadRequests(mContext);
    }

    long startPublicKeyDownload() {
        long downloadId = download(Config.publicKeyUrl(mContext));
        if (downloadId != -1) {
            mDownloadIds.put(Config.PUBLIC_KEY_DOWNLOAD_ID, downloadId);
        }
        return downloadId;
    }

    private long startMetadataDownload(CompatibilityVersion compatVersion) {
        long downloadId = download(compatVersion.getMetadataUrl());
        if (downloadId != -1) {
            mDownloadIds.put(compatVersion.getMetadataPropertyName(), downloadId);
        }
        return downloadId;
    }

    @VisibleForTesting
    void startMetadataDownload() {
        for (CompatibilityVersion compatVersion : mCompatVersions) {
            if (startMetadataDownload(compatVersion) == -1) {
                Log.e(TAG, "Metadata download not started for " + compatVersion.getCompatVersion());
            } else if (Config.DEBUG) {
                Log.d(TAG, "Metadata download started for " + compatVersion.getCompatVersion());
            }
        }
    }

    @VisibleForTesting
    long startContentDownload(CompatibilityVersion compatVersion) {
        long downloadId = download(compatVersion.getContentUrl());
        if (downloadId != -1) {
            mDownloadIds.put(compatVersion.getContentPropertyName(), downloadId);
        }
        return downloadId;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            Log.w(TAG, "Received unexpected broadcast with action " + action);
            return;
        }

        long completedId =
                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, /* defaultValue= */ -1);
        if (completedId == -1) {
            Log.e(TAG, "Invalid completed download Id");
            return;
        }

        if (getPublicKeyDownloadId() == completedId) {
            handlePublicKeyDownloadCompleted(completedId);
            return;
        }

        for (CompatibilityVersion compatVersion : mCompatVersions) {
            if (getMetadataDownloadId(compatVersion) == completedId) {
                handleMetadataDownloadCompleted(compatVersion, completedId);
                return;
            }

            if (getContentDownloadId(compatVersion) == completedId) {
                handleContentDownloadCompleted(context, compatVersion, completedId);
                return;
            }
        }

        Log.i(TAG, "Download id " + completedId + " is not recognized.");
    }

    private void handlePublicKeyDownloadCompleted(long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }

        Uri publicKeyUri = getPublicKeyDownloadUri();
        if (publicKeyUri == null) {
            Log.e(TAG, "Invalid public key URI");
            return;
        }

        LogListUpdateStatus updateStatus = mSignatureVerifier.setPublicKeyFrom(publicKeyUri);
        if (!updateStatus.isPublicKeySet()) {
            mLogger.logCTLogListUpdateStateChangedEvent(updateStatus);
            return;
        }

        startMetadataDownload();
    }

    private void handleMetadataDownloadCompleted(
            CompatibilityVersion compatVersion, long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }
        if (startContentDownload(compatVersion) == -1) {
            Log.e(TAG, "Content download failed for" + compatVersion.getCompatVersion());
        } else if (Config.DEBUG) {
            Log.d(TAG, "Content download started for" + compatVersion.getCompatVersion());
        }
    }

    private void handleContentDownloadCompleted(
            Context context, CompatibilityVersion compatVersion, long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }

        final byte[] contentBytes;
        final byte[] signatureBytes;
        {
            Uri contentUri = getContentDownloadUri(compatVersion);
            Uri metadataUri = getMetadataDownloadUri(compatVersion);
            if (contentUri == null || metadataUri == null) {
                Log.e(TAG, "Invalid URIs");
                return;
            }
            ContentResolver cr = mContext.getContentResolver();
            try (var contentStream = cr.openInputStream(contentUri);
                 var metadataStream = cr.openInputStream(metadataUri)) {
                if (contentStream == null) {
                    throw new IOException();
                }
                if (metadataStream == null) {
                    throw new IOException();
                }
                contentBytes = contentStream.readAllBytes();
                signatureBytes = metadataStream.readAllBytes();
            } catch (IOException e) {
                Log.e(TAG, "unable to read content or metadata Uri", e);
                return;
            }
        }

        LogListUpdateStatus updateStatus = mSignatureVerifier.verify(contentBytes, signatureBytes);

        if (!updateStatus.isSignatureVerified()) {
            Log.w(TAG, "Log list did not pass verification");

            mLogger.logCTLogListUpdateStateChangedEvent(updateStatus);

            return;
        }

        try {
            updateStatus = compatVersion.install(contentBytes, updateStatus.toBuilder());
        } catch (IOException e) {
            Log.e(TAG, "Could not install new content", e);
            return;
        }

        if (updateStatus.isLogListAvailable()) {
            context.sendBroadcast(INSTALL_COMPLETE);
        }

        mLogger.logCTLogListUpdateStateChangedEvent(updateStatus);
    }

    private void handleDownloadFailed(DownloadStatus status) {
        Log.e(TAG, "Download failed with " + status);

        LogListUpdateStatus.Builder updateStatusBuilder = LogListUpdateStatus.builder();
        if (status.isHttpError()) {
            updateStatusBuilder
                    .setState(CTLogListUpdateState.HTTP_ERROR)
                    .setHttpErrorStatusCode(status.reason());
        } else {
            // TODO(b/384935059): handle blocked domain logging
            updateStatusBuilder.setDownloadStatus(Optional.of(status.reason()));
        }

        mLogger.logCTLogListUpdateStateChangedEvent(updateStatusBuilder.build());
    }

    private long download(String url) {
        if (CertTransparencyDownloaderSetting.SETTING.get(mContext) == VAL_OFF) {
            Log.i(TAG, "setting is set to OFF, skipping download of " + url);
            return -1;
        }

        Log.d(TAG, "enqueueing download of " + url);

        try {
            return mDownloadHelper.startDownload(url);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Download request failed", e);
            return -1;
        }
    }

    @VisibleForTesting
    long getPublicKeyDownloadId() {
        return mDownloadIds.getOrDefault(Config.PUBLIC_KEY_DOWNLOAD_ID, -1L);
    }

    @VisibleForTesting
    long getMetadataDownloadId(CompatibilityVersion compatVersion) {
        return mDownloadIds.getOrDefault(compatVersion.getMetadataPropertyName(), -1L);
    }

    @VisibleForTesting
    long getContentDownloadId(CompatibilityVersion compatVersion) {
        return mDownloadIds.getOrDefault(compatVersion.getContentPropertyName(), -1L);
    }

    @VisibleForTesting
    boolean hasPublicKeyDownloadId() {
        return getPublicKeyDownloadId() != -1;
    }

    @VisibleForTesting
    boolean hasMetadataDownloadId() {
        return mCompatVersions.stream()
                .map(this::getMetadataDownloadId)
                .anyMatch(downloadId -> downloadId != -1);
    }

    @VisibleForTesting
    boolean hasContentDownloadId() {
        return mCompatVersions.stream()
                .map(this::getContentDownloadId)
                .anyMatch(downloadId -> downloadId != -1);
    }

    private Uri getPublicKeyDownloadUri() {
        return mDownloadHelper.getUri(getPublicKeyDownloadId());
    }

    private Uri getMetadataDownloadUri(CompatibilityVersion compatVersion) {
        return mDownloadHelper.getUri(getMetadataDownloadId(compatVersion));
    }

    private Uri getContentDownloadUri(CompatibilityVersion compatVersion) {
        return mDownloadHelper.getUri(getContentDownloadId(compatVersion));
    }
}
