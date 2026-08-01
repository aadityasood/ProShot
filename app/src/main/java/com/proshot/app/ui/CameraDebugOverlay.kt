package com.proshot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proshot.app.camera.CaptureTiming
import com.proshot.app.camera.FocusLensDiagnostics
import com.proshot.app.camera.compat.CompatibilityDecision
import com.proshot.app.camera.compat.DeviceCameraCapabilities

/**
 * Temporary debug overlay displaying pipeline tier and device capability diagnostics.
 */
@Composable
internal fun DebugStatusOverlay(
    capabilities: DeviceCameraCapabilities,
    decision: CompatibilityDecision,
    lastCaptureTiming: CaptureTiming?,
    lastFocusLensDiagnostics: FocusLensDiagnostics?
) {
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = "ProShot Debug Status",
            color = Color.Green,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Pipeline Tier: ${decision.tier}",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Look Profile: ${decision.lookProfile.displayName}",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "HW Level: ${capabilities.hardwareLevel}",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "GPU: ${if (capabilities.gpuDelegateSupported) "OK" else "CPU fallback"}",
            color = if (capabilities.gpuDelegateSupported) Color.Cyan else Color.Yellow,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Masks: ${if (capabilities.semanticMasksSupported) "OK" else "Disabled"}",
            color = if (capabilities.semanticMasksSupported) Color.Cyan else Color.Yellow,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        if (lastCaptureTiming != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastCaptureTiming.formatDiagnostics(),
                color = Color.Green,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (lastFocusLensDiagnostics != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastFocusLensDiagnostics.formatDiagnostics(),
                color = Color.Green,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
