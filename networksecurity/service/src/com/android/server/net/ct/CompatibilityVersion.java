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
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.server.net.ct.CertificateTransparencyLogger.CTLogListUpdateState;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** Represents a compatibility version directory. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CompatibilityVersion {

    private static File sRootDirectory = new File(Config.CT_ROOT_DIRECTORY_PATH);

    static final String LOGS_DIR_PREFIX = "logs-";
    static final String CURRENT_LOGS_DIR_SYMLINK_NAME = "current";

    private final String mCompatVersion;

    private final String mMetadataUrl;
    private final String mContentUrl;
    private final File mVersionDirectory;
    private final File mCurrentLogsDirSymlink;
    private final LogListFile.Provider mLogListProvider;

    CompatibilityVersion(String compatVersion, String metadataUrl, String contentUrl) {
        mCompatVersion = compatVersion;
        mMetadataUrl = metadataUrl;
        mContentUrl = contentUrl;
        mVersionDirectory = new File(sRootDirectory, compatVersion);
        mCurrentLogsDirSymlink = new File(mVersionDirectory, CURRENT_LOGS_DIR_SYMLINK_NAME);

        if (Config.COMPATIBILITY_VERSION_V3.equals(compatVersion)) {
            mLogListProvider = new LogListFileProviderFlatbuffers(mVersionDirectory);
        } else {
            mLogListProvider = new LogListFileProviderJson(mVersionDirectory);
        }
    }

    @VisibleForTesting
    static void setRootDirectoryForTesting(File rootDirectory) {
        sRootDirectory = rootDirectory;
    }

    /**
     * Installs a log list within this compatibility version directory.
     *
     * @param contentBytes the log list
     * @param statusBuilder status obj builder containing details of the log list update process
     * @return true if the log list was installed successfully, false otherwise.
     * @throws IOException if the list cannot be saved in the CT directory.
     */
    LogListUpdateStatus install(byte[] contentBytes, LogListUpdateStatus.Builder statusBuilder)
            throws IOException {
        final LogListFile newLogList;
        try {
            newLogList = mLogListProvider.fromBytes(contentBytes);
        } catch (IOException e) {
            Log.e(TAG, "invalid log list format", e);
            return statusBuilder.setState(CTLogListUpdateState.LOG_LIST_INVALID).build();
        }

        if (!shouldInstall(newLogList)) {
            Log.i(TAG, newLogList.directory() + " already exists, skipping install.");
            deleteOldLogDirectories();
            return statusBuilder
                    .setLogListTimestamp(newLogList.timestamp())
                    .setState(CTLogListUpdateState.VERSION_ALREADY_EXISTS)
                    .build();
        }

        return install(
                new ByteArrayInputStream(contentBytes),
                newLogList,
                statusBuilder.setLogListTimestamp(newLogList.timestamp()));
    }

    boolean shouldInstall(LogListFile logList) throws IOException {
        File logsDir = logList.directory();

        // This new version was not seen before, proceed with installation.
        if (!logsDir.exists()) {
            return true;
        }

        // If the symlink has not been updated then the previous installation failed and
        // this is a re-attempt. Clean-up leftover files and try again.
        if (!logsDir.getCanonicalPath().equals(mCurrentLogsDirSymlink.getCanonicalPath())) {
            Log.i(TAG, logsDir + " installation failed, reattempt.");
            DirectoryUtils.removeDir(logsDir);
            return true;
        }

        LogListFile existingLogList = null;
        try {
            existingLogList = mLogListProvider.fromFile(logList.file());
        } catch (IOException e) {
            Log.w(TAG, "The existing log list is not valid", e);
            DirectoryUtils.removeDir(logsDir);
            return true;
        }

        // If the previous installation was successful but the new log list has a later timestamp it
        // means it's a bug fix for a previously broken version.
        if (existingLogList.timestamp() < logList.timestamp()) {
            Log.i(TAG, "The new log list has a later timestamp.");
            DirectoryUtils.removeDir(logsDir);
            return true;
        }

        // In all other cases, the update died between steps 5 and 6 and so we cannot delete the
        // directory since it is in use.
        return false;
    }

    LogListUpdateStatus install(
            InputStream newContent,
            LogListFile newLogList,
            LogListUpdateStatus.Builder statusBuilder)
            throws IOException {
        // To support atomically replacing the old configuration directory with the new
        // there's a bunch of steps. We create a new directory with the logs and then do
        // an atomic update of the current symlink to point to the new directory.
        // 1. Ensure the path to the root and version directories exist and are readable.
        DirectoryUtils.makeDir(sRootDirectory);
        DirectoryUtils.makeDir(mVersionDirectory);

        File newLogsDir = newLogList.directory();
        try {
            // 2. Create a new logs-<new_version>/ directory to store the new list.
            DirectoryUtils.makeDir(newLogsDir);

            // 3. Move the log list json file in logs-<new_version>/ .
            File logListFile = newLogList.file();
            if (Files.copy(newContent, logListFile.toPath()) == 0) {
                throw new IOException("The log list appears empty");
            }
            DirectoryUtils.setWorldReadable(logListFile);

            // 4. Create temp symlink. We rename to the target symlink for an atomic update.
            File tempSymlink = new File(mVersionDirectory, "new_symlink");
            try {
                Os.symlink(newLogsDir.getCanonicalPath(), tempSymlink.getCanonicalPath());
            } catch (ErrnoException e) {
                throw new IOException("Failed to create symlink", e);
            }

            // 5. Update the symlink target, this is the actual update step.
            tempSymlink.renameTo(mCurrentLogsDirSymlink.getAbsoluteFile());
        } catch (IOException | RuntimeException e) {
            DirectoryUtils.removeDir(newLogsDir);
            throw e;
        }
        // 6. Cleanup
        Log.i(TAG, "New logs installed at " + newLogsDir);
        deleteOldLogDirectories();
        return statusBuilder.setState(CTLogListUpdateState.SUCCESS).build();
    }

    String getCompatVersion() {
        return mCompatVersion;
    }

    String getMetadataUrl() {
        return mMetadataUrl;
    }

    String getMetadataPropertyName() {
        return mCompatVersion + "_" + Config.METADATA_DOWNLOAD_ID;
    }

    String getContentUrl() {
        return mContentUrl;
    }

    String getContentPropertyName() {
        return mCompatVersion + "_" + Config.CONTENT_DOWNLOAD_ID;
    }

    File getVersionDir() {
        return mVersionDirectory;
    }

    File getLogsDirSymlink() {
        return mCurrentLogsDirSymlink;
    }

    File getLogsFile() {
        return new File(mCurrentLogsDirSymlink, mLogListProvider.getFileName());
    }

    void delete() {
        if (!DirectoryUtils.removeDir(mVersionDirectory)) {
            Log.w(TAG, "Could not delete compatibility version directory " + mVersionDirectory);
        }
    }

    private void deleteOldLogDirectories() throws IOException {
        if (!mVersionDirectory.exists()) {
            return;
        }
        File currentTarget = mCurrentLogsDirSymlink.getCanonicalFile();
        for (File file : mVersionDirectory.listFiles()) {
            if (!currentTarget.equals(file.getCanonicalFile())
                    && file.getName().startsWith(LOGS_DIR_PREFIX)) {
                DirectoryUtils.removeDir(file);
            }
        }
    }
}
