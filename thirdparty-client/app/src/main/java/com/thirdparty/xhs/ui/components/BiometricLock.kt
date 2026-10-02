package com.thirdparty.xhs.ui.components

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * Full-screen cover shown while the app lock is engaged.
 *
 * It sits ON TOP of the normal content rather than replacing it, so the nav state
 * and every screen's state survive the unlock (replacing them would bounce the
 * user back to 推荐 every time they came back to the app).
 */
@Composable
fun BiometricLockCover(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(Spacing.m))
        Text("已锁定", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "解锁后可继续浏览",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.l))
        Button(onClick = onUnlock) { Text("解锁") }
    }
}

/**
 * Whether this device can actually authenticate a human.
 *
 * Checked with BIOMETRIC_WEAK so a device with only face unlock still counts;
 * {@link BiometricManager#canAuthenticate} also reports devices with no enrolled
 * credential at all, which must not be offered the toggle.
 */
fun biometricAvailable(activity: FragmentActivity): Boolean {
    val manager = BiometricManager.from(activity)
    val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return manager.canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS
}

/**
 * Prompt for the device credential. [onSuccess] runs only on a real authentication;
 * cancel and error both leave the app locked, which is the safe default.
 */
fun promptBiometric(
    activity: FragmentActivity,
    title: String = "解锁小黄书",
    onSuccess: () -> Unit,
    onFail: (String) -> Unit = {}
) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val prompt = BiometricPrompt(
        activity,
        androidx.core.content.ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // A user-initiated cancel is not an error worth reporting
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) {
                    onFail(errString.toString())
                }
            }
        }
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("验证指纹或设备密码")
            .setAllowedAuthenticators(allowed)
            .build()
    )
}
