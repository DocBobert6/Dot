package app.dot

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Dot — manifest permissions test.
//
// The three permissions are declared deliberately from build one
// (§1D): VIBRATE for haptics, INTERNET and ACCESS_NETWORK_STATE
// for the cover-art index and fetch. Removing one must fail this
// test instead of failing silently on device — Atomic lost a month
// to a missing VIBRATE line, and Poing lost a login to a missing
// INTERNET line. CI's host tests never enforce the manifest, which
// is how those slipped through.
// SPDX-License-Identifier: GPL-3.0-or-later
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestPermissionsTest {

    private fun requestedPermissions(): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions?.toList().orEmpty()
    }

    @Test
    fun vibratePermissionIsDeclared() {
        assertTrue(
            "VIBRATE must be declared — without it every haptic " +
                "call throws SecurityException, silently caught, " +
                "and nothing is ever felt",
            requestedPermissions().contains("android.permission.VIBRATE"),
        )
    }

    @Test
    fun internetPermissionIsDeclared() {
        assertTrue(
            "INTERNET must be declared — without it no network " +
                "feature can open a socket on device",
            requestedPermissions().contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun networkStatePermissionIsDeclared() {
        assertTrue(
            "ACCESS_NETWORK_STATE must be declared — without it the " +
                "cover-art network gate cannot tell Wi-Fi from mobile data",
            requestedPermissions().contains("android.permission.ACCESS_NETWORK_STATE"),
        )
    }
}
