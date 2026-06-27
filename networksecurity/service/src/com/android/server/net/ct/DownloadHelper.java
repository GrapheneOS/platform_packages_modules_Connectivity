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

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.database.Cursor;
import android.ext.settings.CertTransparencyDownloaderSetting;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.google.auto.value.AutoValue;

import java.util.ArrayList;
import java.util.Objects;

/** Class to handle downloads for Certificate Transparency. */
public class DownloadHelper {

    private final DownloadManager mDownloadManager;

    @VisibleForTesting
    DownloadHelper(DownloadManager downloadManager) {
        mDownloadManager = downloadManager;
    }

    DownloadHelper(Context context) {
        this(context.getSystemService(DownloadManager.class));
    }

    void checkDownloadRequests(Context context) {
        Log.d(TAG, "checkDownloadRequests");

        try (Cursor cursor = mDownloadManager.query(new Query())) {
            if (cursor == null) {
                Log.d(TAG, "cursor is null");
                return;
            }
            Log.d(TAG, "cursor row count: " + cursor.getCount());

            int idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
            int uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
            int statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);

            var downloadsToRemove = new ArrayList<Long>(); // android.util.LongArray is unavailable here

            String currentBaseUrl = Config.baseUrl(context);
            int setting = CertTransparencyDownloaderSetting.SETTING.get(context);
            Log.d(TAG, "current base URL: " + currentBaseUrl + " setting value: " + setting);

            String commonBaseUrlPart = Config.getCommonBaseUrlPart();

            while (cursor.moveToNext()) {
                String url = cursor.getString(uriCol);
                int status = cursor.getInt(statusCol);
                Log.d(TAG, "checking url " + url + " status: " + status);
                if (url == null) {
                    continue;
                }
                if (!url.contains(commonBaseUrlPart)) {
                    // this URL wasn't enqueued by CertificateTransparencyDownloader
                    continue;
                }
                if (setting == CertTransparencyDownloaderSetting.VAL_OFF || !url.startsWith(currentBaseUrl)) {
                    long id = cursor.getLong(idCol);
                    downloadsToRemove.add(Long.valueOf(id));
                }
            }

            int numIds = downloadsToRemove.size();
            if (numIds > 0) {
                long[] idsArray = downloadsToRemove.stream().mapToLong(Long::longValue).toArray();
                int numRemoved = mDownloadManager.remove(idsArray);
                if (numRemoved != numIds) {
                    Log.e(TAG, "numRemoved (" + numRemoved + ") != numIds (" + numIds + ")");
                } else {
                    Log.d(TAG, "removed downloads count: " + numIds);
                }
            }
        }
    }

    /**
     * Sends a request to start the download of a provided url.
     *
     * @param url the url to download
     * @return a downloadId if the request was created successfully, -1 otherwise.
     */
    public long startDownload(String url) {
        long downloadId = isUrlAlreadyScheduled(url);
        if (downloadId != -1) {
            Log.i(TAG, "Download for " + url + " already scheduled.");
            return downloadId;
        }
        return mDownloadManager.enqueue(
                new Request(Uri.parse(url))
                        .setAllowedOverRoaming(false)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                        .setRequiresCharging(true));
    }

    private long isUrlAlreadyScheduled(String url) {
        try (Cursor cursor =
                mDownloadManager.query(
                        new Query()
                                .setFilterByStatus(
                                        DownloadManager.STATUS_PENDING
                                                | DownloadManager.STATUS_RUNNING
                                                | DownloadManager.STATUS_PAUSED))) {
            if (cursor == null) {
                return -1;
            }
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                int idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
                int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
                if (idIndex == -1 || uriIndex == -1) {
                    continue;
                }
                long downloadId = cursor.getLong(idIndex);
                String uri = cursor.getString(uriIndex);
                if (Objects.equals(uri, url)) {
                    return downloadId;
                }
            }
        }
        return -1;
    }

    /**
     * Returns the status of the provided download id.
     *
     * @param downloadId the download.
     * @return {@link DownloadStatus} of the download.
     */
    public DownloadStatus getDownloadStatus(long downloadId) {
        DownloadStatus.Builder builder = DownloadStatus.builder().setDownloadId(downloadId);
        try (Cursor cursor = mDownloadManager.query(new Query().setFilterById(downloadId))) {
            if (cursor != null && cursor.moveToFirst()) {
                builder.setStatus(
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)));
                builder.setReason(
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
            }
        }
        return builder.build();
    }

    /**
     * Returns the URI of the specified download, or null if the download did not complete
     * successfully.
     *
     * @param downloadId the download.
     * @return the {@link Uri} if the download completed successfully, null otherwise.
     */
    public Uri getUri(long downloadId) {
        if (downloadId == -1) {
            return null;
        }
        return mDownloadManager.getUriForDownloadedFile(downloadId);
    }

    /** A wrapper around the status and reason Ids returned by the {@link DownloadManager}. */
    @AutoValue
    public abstract static class DownloadStatus {

        abstract long downloadId();

        abstract int status();

        abstract int reason();

        boolean isSuccessful() {
            return status() == DownloadManager.STATUS_SUCCESSFUL;
        }

        boolean isStorageError() {
            int status = status();
            int reason = reason();
            return status == DownloadManager.STATUS_FAILED
                    && (reason == DownloadManager.ERROR_DEVICE_NOT_FOUND
                            || reason == DownloadManager.ERROR_FILE_ERROR
                            || reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS
                            || reason == DownloadManager.ERROR_INSUFFICIENT_SPACE);
        }

        boolean isHttpError() {
            int status = status();
            int reason = reason();
            return status == DownloadManager.STATUS_FAILED
                    && (reason == DownloadManager.ERROR_HTTP_DATA_ERROR
                            || reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS
                            || reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE
                            // If an HTTP error occurred, reason will hold the HTTP status code.
                            || (400 <= reason && reason < 600));
        }

        @AutoValue.Builder
        abstract static class Builder {
            abstract Builder setDownloadId(long downloadId);

            abstract Builder setStatus(int status);

            abstract Builder setReason(int reason);

            abstract DownloadStatus build();
        }

        static Builder builder() {
            return new AutoValue_DownloadHelper_DownloadStatus.Builder()
                    .setDownloadId(-1)
                    .setStatus(-1)
                    .setReason(-1);
        }
    }
}
