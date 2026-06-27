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

import static com.android.net.ct.flags.Flags.flatbuffersLogList;
import static com.android.server.net.ct.Config.TAG;

import android.annotation.RequiresApi;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;

/** Implementation of the Certificate Transparency service. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class CertificateTransparencyService {

    private final CertificateTransparencyJob mCertificateTransparencyJob;

    /** Creates a new {@link CertificateTransparencyService} object. */
    public CertificateTransparencyService(Context context) {
        SignatureVerifier signatureVerifier = new SignatureVerifier(context);
        Collection<CompatibilityVersion> compatVersions = new ArrayList<>();
        compatVersions.add(
                new CompatibilityVersion(
                        Config.COMPATIBILITY_VERSION_V2,
                        Config.signatureV2Url(context),
                        Config.logListV2Url(context)));
        if (flatbuffersLogList()) {
            compatVersions.add(
                    new CompatibilityVersion(
                            Config.COMPATIBILITY_VERSION_V3,
                            Config.signatureV3Url(context),
                            Config.logListV3Url(context)));
        }

        mCertificateTransparencyJob =
                new CertificateTransparencyJob(
                        context,
                        new CertificateTransparencyDownloader(
                                context,
                                new DownloadHelper(context),
                                signatureVerifier,
                                new CertificateTransparencyLoggerImpl(),
                                compatVersions),
                        signatureVerifier,
                        compatVersions);
    }

    /**
     * Called by {@link com.android.server.ConnectivityServiceInitializer}.
     */
    public void onSystemUserUnlocked() {
        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyService start");
        }
        mCertificateTransparencyJob.schedule();
    }
}
