package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.host.ShizukuController
import com.example.model.ShizukuStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GreyOffline
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateElevated
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun TacticalCard(
    modifier: Modifier = Modifier,
    borderColor: Color = SlateBorder,
    containerColor: Color = SlateSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun PresenceDot(isOnline: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (isOnline) EmeraldOnline else GreyOffline)
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Compact one-line Shizuku status banner with live colored dot (Decision 8).
 */
@Composable
fun ShizukuOneLineBanner(
    status: ShizukuStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dotColor = when (status) {
        is ShizukuStatus.Authorized -> EmeraldOnline
        is ShizukuStatus.RecoveredToPreviewAfterBinderLoss,
        is ShizukuStatus.PermissionRequired,
        is ShizukuStatus.NeedsUsbAdbOnApi29,
        is ShizukuStatus.NeedsStartOnApi30Plus -> AmberWarning
        else -> GreyOffline
    }
    val summary = when (status) {
        is ShizukuStatus.Authorized -> "Shizuku active · Full multi-display"
        is ShizukuStatus.PermissionRequired -> "Shizuku running · Tap to authorize"
        is ShizukuStatus.NeedsUsbAdbOnApi29 -> "Layout Preview · Android 10 ADB setup in menu"
        is ShizukuStatus.NeedsStartOnApi30Plus -> "Layout Preview · Start Shizuku in menu"
        is ShizukuStatus.NotInstalled -> "Layout Preview · Shizuku optional"
        is ShizukuStatus.LayoutPreviewFallback -> "Layout Preview active"
        is ShizukuStatus.RecoveredToPreviewAfterBinderLoss -> "Recovered to Layout Preview"
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = SlateSurface,
        border = BorderStroke(1.dp, SlateBorder),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("shizuku_one_line_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(color = dotColor)
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TelemetryChip(
    label: String,
    accentColor: Color = CyanPrimary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = accentColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun ShizukuStatusCard(
    status: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onContinuePreview: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isReady = status is ShizukuStatus.Authorized
    val borderColor = when (status) {
        is ShizukuStatus.Authorized -> EmeraldOnline.copy(alpha = 0.6f)
        is ShizukuStatus.NeedsUsbAdbOnApi29,
        is ShizukuStatus.RecoveredToPreviewAfterBinderLoss,
        is ShizukuStatus.PermissionRequired,
        is ShizukuStatus.NeedsStartOnApi30Plus -> AmberWarning.copy(alpha = 0.65f)
        else -> SlateBorder
    }

    TacticalCard(modifier = modifier, borderColor = borderColor) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isReady) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                contentDescription = "Shizuku service status",
                tint = if (isReady) EmeraldOnline else AmberWarning
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (status) {
                        is ShizukuStatus.Authorized -> "Shizuku Authorized • Full Multi-Display"
                        is ShizukuStatus.NeedsUsbAdbOnApi29 -> "Android 10 Host: Start Shizuku via PC / USB ADB"
                        is ShizukuStatus.NeedsStartOnApi30Plus -> "Start Shizuku via Wireless Debugging or USB"
                        is ShizukuStatus.PermissionRequired -> "Shizuku Running • Grant App Permission"
                        is ShizukuStatus.NotInstalled -> "Shizuku Not Installed • Layout Preview Available"
                        is ShizukuStatus.LayoutPreviewFallback -> "VirtualDisplay Layout Preview Active"
                        is ShizukuStatus.RecoveredToPreviewAfterBinderLoss -> "Shizuku Stopped — Recovered to Layout Preview"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (status) {
                        is ShizukuStatus.Authorized ->
                            "Ready to spawn displayId 1, migrate apps exclusively, and inject input."
                        is ShizukuStatus.NeedsUsbAdbOnApi29 ->
                            "Android 10 lacks Wireless Debugging. Connect USB to a PC and run the command below (verify path inside Shizuku app on your Host; re-run after reboot)."
                        is ShizukuStatus.NeedsStartOnApi30Plus ->
                            "Open Shizuku and tap Start via Wireless Debugging, or start via USB ADB."
                        is ShizukuStatus.PermissionRequired ->
                            "Tap Authorize Shizuku so Extended Desktop can control displayId 1."
                        is ShizukuStatus.NotInstalled ->
                            "Install Shizuku from Google Play for third-party multi-display app launching, or use VirtualDisplay Layout Preview."
                        is ShizukuStatus.LayoutPreviewFallback ->
                            "Preview tests desktop layout, aspect/DPI synthesis, touchpad, IME, and H.264 link. Third-party app launching requires Shizuku."
                        is ShizukuStatus.RecoveredToPreviewAfterBinderLoss ->
                            "Video & touch stayed connected on the Display. Restart Shizuku and tap Reconnect anytime."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        if (status is ShizukuStatus.NeedsUsbAdbOnApi29) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = ObsidianBg,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ShizukuController.ANDROID_10_ADB_CMD,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(
                                ClipData.newPlainText("Shizuku ADB Command", ShizukuController.ANDROID_10_ADB_CMD)
                            )
                            Toast.makeText(context, "ADB command copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("copy_adb_command_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy ADB command",
                            tint = CyanPrimary
                        )
                    }
                }
            }
        }

        if (!isReady) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (status) {
                    is ShizukuStatus.PermissionRequired -> {
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("authorize_shizuku_button")
                        ) {
                            Text("Authorize Shizuku")
                        }
                    }
                    is ShizukuStatus.NotInstalled -> {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                                )
                                runCatching { context.startActivity(intent) }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("get_shizuku_button")
                        ) {
                            Text("Get Shizuku")
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = onRefresh,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("recheck_shizuku_button")
                        ) {
                            Text("Reconnect Shizuku")
                        }
                    }
                }

                if (onContinuePreview != null && status !is ShizukuStatus.LayoutPreviewFallback) {
                    Button(
                        onClick = onContinuePreview,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateElevated,
                            contentColor = CyanPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("use_layout_preview_button")
                    ) {
                        Text("Layout Preview", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
