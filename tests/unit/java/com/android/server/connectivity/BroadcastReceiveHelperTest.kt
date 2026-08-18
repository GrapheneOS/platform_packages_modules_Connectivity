/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.connectivity

import android.Manifest.permission.NETWORK_STACK
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
import android.content.Intent.ACTION_PACKAGE_ADDED
import android.content.Intent.ACTION_PACKAGE_REMOVED
import android.content.Intent.ACTION_PACKAGE_REPLACED
import android.content.Intent.ACTION_USER_ADDED
import android.content.Intent.ACTION_USER_REMOVED
import android.content.Intent.EXTRA_CHANGED_PACKAGE_LIST
import android.content.Intent.EXTRA_UID
import android.content.Intent.EXTRA_USER
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.UserHandle
import android.os.UserManager
import com.android.net.module.util.TestableCallback
import com.android.server.connectivity.BroadcastReceiveHelper.Delegate
import com.android.server.connectivity.BroadcastReceiveHelperTest.TestDelegate.CallbackEvent.OnExternalApplicationsAvailable
import com.android.server.connectivity.BroadcastReceiveHelperTest.TestDelegate.CallbackEvent.OnPackageAdded
import com.android.server.connectivity.BroadcastReceiveHelperTest.TestDelegate.CallbackEvent.OnPackageRemoved
import com.android.server.connectivity.BroadcastReceiveHelperTest.TestDelegate.CallbackEvent.OnPackageReplaced
import com.android.server.connectivity.BroadcastReceiveHelperTest.TestDelegate.CallbackEvent.OnUserAdded
import com.android.server.connectivity.BroadcastReceiveHelperTest.TestDelegate.CallbackEvent.OnUserRemoved
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val TEST_PACKAGE_NAME = "com.example.app"
private const val TEST_UID = 1000
private const val TIMEOUT_MS = 2000

private inline fun <reified T> any() = org.mockito.Mockito.any(T::class.java)

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
/**
 * atest -c ConnectivityCoverageTests:android.net.connectivity.com.android.server.connectivity.BroadcastReceiveHelperTest
 */
class BroadcastReceiveHelperTest {
    private val mockContext = mock(Context::class.java)
    private val testDelegate = TestDelegate()
    private val mockUserManager = mock(UserManager::class.java)

    // lateinit is used here because thread and handler need to be initialized in the
    // @Before setUp method.
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var broadcastReceiveHelper: BroadcastReceiveHelper
    private lateinit var packageReceiver: BroadcastReceiver
    private lateinit var externalAppReceiver: BroadcastReceiver
    private lateinit var userReceiver: BroadcastReceiver

    @Before
    fun setUp() {
        handlerThread = HandlerThread("TestThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        broadcastReceiveHelper = BroadcastReceiveHelper(mockContext, handler, testDelegate)

        // Capture intent receivers.
        doReturn(mockUserManager).`when`(mockContext).getSystemService(UserManager::class.java)
        doReturn(mockContext).`when`(mockContext).createContextAsUser(UserHandle.ALL, 0)
        broadcastReceiveHelper.registerReceivers()
        userReceiver = captureIntentReceiver(ACTION_USER_ADDED)
        packageReceiver = captureIntentReceiver(ACTION_PACKAGE_ADDED)
        externalAppReceiver = captureIntentReceiver(ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
    }

    private fun captureIntentReceiver(action: String): BroadcastReceiver {
        val receiverCaptor = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
        verify(mockContext, times(1)).registerReceiver(
                receiverCaptor.capture(),
                argThat { it.hasAction(action) },
                eq(NETWORK_STACK),
                eq(handler)
        )
        // Return the first match.
        return receiverCaptor.value
    }

    @After
    fun tearDown() {
        broadcastReceiveHelper.unregisterReceivers()
        handlerThread.quitSafely()
        handlerThread.join()
    }

    @Test
    fun testPackageAdded() {
        val intent = Intent(ACTION_PACKAGE_ADDED).apply {
            data = Uri.fromParts("package", TEST_PACKAGE_NAME, null)
            putExtra(EXTRA_UID, TEST_UID)
        }
        handler.post { packageReceiver.onReceive(mockContext, intent) }
        testDelegate.expect<OnPackageAdded> {
            it.packageName == TEST_PACKAGE_NAME && it.uid == TEST_UID
        }
        testDelegate.assertNoCallback()
    }

    @Test
    fun testPackageRemoved() {
        val intent = Intent(ACTION_PACKAGE_REMOVED).apply {
            data = Uri.fromParts("package", TEST_PACKAGE_NAME, null)
            putExtra(EXTRA_UID, TEST_UID)
        }
        handler.post { packageReceiver.onReceive(mockContext, intent) }
        testDelegate.expect<OnPackageRemoved> {
            it.packageName == TEST_PACKAGE_NAME && it.uid == TEST_UID
        }
        testDelegate.assertNoCallback()
    }

    @Test
    fun testPackageReplaced() {
        val intent = Intent(ACTION_PACKAGE_REPLACED).apply {
            data = Uri.fromParts("package", TEST_PACKAGE_NAME, null)
            putExtra(EXTRA_UID, TEST_UID)
        }
        handler.post { packageReceiver.onReceive(mockContext, intent) }
        testDelegate.expect<OnPackageReplaced> {
            it.packageName == TEST_PACKAGE_NAME && it.uid == TEST_UID
        }
        testDelegate.assertNoCallback()
    }

    @Test
    fun testExternalAppsAvailable() {
        val packageList = arrayOf("com.example.app1", "com.example.app2")
        val intent = Intent(ACTION_EXTERNAL_APPLICATIONS_AVAILABLE).apply {
            putExtra(EXTRA_CHANGED_PACKAGE_LIST, packageList)
        }
        handler.post { externalAppReceiver.onReceive(mockContext, intent) }
        testDelegate.expect<OnExternalApplicationsAvailable> {
            it.pkgList.contentEquals(packageList)
        }
        testDelegate.assertNoCallback()
    }

    @Test
    fun testUserAdded() {
        val userHandle = mock(UserHandle::class.java)
        val intent = Intent(ACTION_USER_ADDED).apply {
            putExtra(EXTRA_USER, userHandle)
        }
        handler.post { userReceiver.onReceive(mockContext, intent) }
        testDelegate.expect<OnUserAdded> {
            it.userHandle == userHandle
        }
        testDelegate.assertNoCallback()
    }

    @Test
    fun testUserRemoved() {
        val userHandle = mock(UserHandle::class.java)
        val intent = Intent(ACTION_USER_REMOVED).apply {
            putExtra(EXTRA_USER, userHandle)
        }
        handler.post { userReceiver.onReceive(mockContext, intent) }
        testDelegate.expect<OnUserRemoved> {
            it.userHandle == userHandle
        }
        testDelegate.assertNoCallback()
    }

    @Test
    fun testUnregisterReceivers() {
        broadcastReceiveHelper.unregisterReceivers()
        verify(mockContext).unregisterReceiver(userReceiver)
        verify(mockContext).unregisterReceiver(packageReceiver)
        verify(mockContext).unregisterReceiver(externalAppReceiver)
    }

    @Test
    fun testBroadcastOrder() {
        val numEvents = 50
        val random = java.util.Random()
        val eventList = mutableListOf<Intent>()

        // Function to append a test package name extra.
        fun Intent.withPackageNameForIndex(index: Int): Intent {
            data = Uri.fromParts("package", "com.example.app$index", null)
            putExtra(EXTRA_UID, index)
            return this
        }

        // Function to append a mock UserHandle extra.
        fun Intent.withUserHandleForIndex(index: Int): Intent {
            putExtra(EXTRA_USER, UserHandle.of(index))
            return this
        }

        // Generate a list of 50 random actions and their corresponding intents.
        repeat(numEvents) { index ->
            val intent = when (random.nextInt(6)) {
                0 -> Intent(ACTION_PACKAGE_ADDED).withPackageNameForIndex(index)
                1 -> Intent(ACTION_PACKAGE_REMOVED).withPackageNameForIndex(index)
                2 -> Intent(ACTION_PACKAGE_REPLACED).withPackageNameForIndex(index)
                3 -> Intent(ACTION_EXTERNAL_APPLICATIONS_AVAILABLE).apply {
                    val packageList = arrayOf("com.example.app$index")
                    putExtra(EXTRA_CHANGED_PACKAGE_LIST, packageList)
                }

                4 -> Intent(ACTION_USER_ADDED).withUserHandleForIndex(index)
                5 -> Intent(ACTION_USER_REMOVED).withUserHandleForIndex(index)

                else -> throw IllegalStateException("Unexpected random number")
            }
            eventList.add(intent)
        }

        // Post all intents to the handler thread at once.
        eventList.forEach {
            handler.post {
                when (it.action) {
                    ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REMOVED, ACTION_PACKAGE_REPLACED ->
                        packageReceiver.onReceive(mockContext, it)

                    ACTION_EXTERNAL_APPLICATIONS_AVAILABLE ->
                        externalAppReceiver.onReceive(mockContext, it)

                    ACTION_USER_ADDED, ACTION_USER_REMOVED ->
                        userReceiver.onReceive(mockContext, it)
                }
            }
        }

        // Verify the order of delegate calls.
        eventList.forEach {
            when (it.action) {
                ACTION_PACKAGE_ADDED -> {
                    val pkgName = it.data?.schemeSpecificPart!!
                    val uid = it.getIntExtra(EXTRA_UID, -1)
                    testDelegate.expect<OnPackageAdded> { event ->
                        event.packageName == pkgName && event.uid == uid
                    }
                }
                ACTION_PACKAGE_REMOVED -> {
                    val pkgName = it.data?.schemeSpecificPart!!
                    val uid = it.getIntExtra(EXTRA_UID, -1)
                    testDelegate.expect<OnPackageRemoved> { event ->
                        event.packageName == pkgName && event.uid == uid
                    }
                }
                ACTION_PACKAGE_REPLACED -> {
                    val pkgName = it.data?.schemeSpecificPart!!
                    val uid = it.getIntExtra(EXTRA_UID, -1)
                    testDelegate.expect<OnPackageReplaced> { event ->
                        event.packageName == pkgName && event.uid == uid
                    }
                }
                ACTION_EXTERNAL_APPLICATIONS_AVAILABLE -> {
                    val pkgList = it.getStringArrayExtra(EXTRA_CHANGED_PACKAGE_LIST)!!
                    testDelegate.expect<OnExternalApplicationsAvailable> { event ->
                        event.pkgList.contentEquals(pkgList)
                    }
                }
                ACTION_USER_ADDED -> {
                    val user = it.getParcelableExtra<UserHandle>(EXTRA_USER)!!
                    testDelegate.expect<OnUserAdded> { event ->
                        event.userHandle == user
                    }
                }
                ACTION_USER_REMOVED -> {
                    val user = it.getParcelableExtra<UserHandle>(EXTRA_USER)!!
                    testDelegate.expect<OnUserRemoved> { event ->
                        event.userHandle == user
                    }
                }
            }
        }

        // Ensure no other calls were made to the delegate.
        testDelegate.assertNoCallback()
    }

    @Test
    fun testCallOnUserAddedForInitialUsers() {
        // Mock existing users.
        val existingUser1 = mock(UserHandle::class.java)
        val existingUser2 = mock(UserHandle::class.java)
        val existingUsers = listOf(existingUser1, existingUser2)
        doReturn(existingUsers).`when`(mockUserManager).getUserHandles(any())
        // Make sure there is no interactions.
        testDelegate.assertNoCallback()

        // Verify that onUserAdded is called for each existing user.
        handler.post { broadcastReceiveHelper.callOnUserAddedForExistingUsers() }
        testDelegate.expect<OnUserAdded> { it.userHandle == existingUser1 }
        testDelegate.expect<OnUserAdded> { it.userHandle == existingUser2 }
        testDelegate.assertNoCallback()
    }

    private class TestDelegate : TestableCallback<TestDelegate.CallbackEvent>(), Delegate {
        sealed class CallbackEvent {
            data class OnPackageAdded(val packageName: String, val uid: Int) : CallbackEvent()
            data class OnPackageRemoved(val packageName: String, val uid: Int) : CallbackEvent()
            data class OnPackageReplaced(val packageName: String, val uid: Int) : CallbackEvent()
            data class OnExternalApplicationsAvailable(val pkgList: Array<String>) : CallbackEvent()
            data class OnUserAdded(val userHandle: UserHandle) : CallbackEvent()
            data class OnUserRemoved(val userHandle: UserHandle) : CallbackEvent()
            data class OnUserStarted(val userHandle: UserHandle) : CallbackEvent()
            data class OnUserStopped(val userHandle: UserHandle) : CallbackEvent()
            data class OnUidRemoved(val uid: Int) : CallbackEvent()
        }

        override fun onPackageAdded(packageName: String, uid: Int) {
            history.add(CallbackEvent.OnPackageAdded(packageName, uid))
        }

        override fun onPackageRemoved(packageName: String, uid: Int) {
            history.add(CallbackEvent.OnPackageRemoved(packageName, uid))
        }

        override fun onPackageReplaced(packageName: String, uid: Int) {
            history.add(CallbackEvent.OnPackageReplaced(packageName, uid))
        }

        override fun onExternalApplicationsAvailable(pkgList: Array<String>) {
            history.add(CallbackEvent.OnExternalApplicationsAvailable(pkgList))
        }

        override fun onUserAdded(userHandle: UserHandle) {
            history.add(CallbackEvent.OnUserAdded(userHandle))
        }

        override fun onUserRemoved(userHandle: UserHandle) {
            history.add(CallbackEvent.OnUserRemoved(userHandle))
        }

        override fun onUserStarted(userHandle: UserHandle) {
            history.add(CallbackEvent.OnUserStarted(userHandle))
        }

        override fun onUserStopped(userHandle: UserHandle) {
            history.add(CallbackEvent.OnUserStopped(userHandle))
        }

        override fun onUidRemoved(uid: Int) {
            history.add(CallbackEvent.OnUidRemoved(uid))
        }
    }
}
