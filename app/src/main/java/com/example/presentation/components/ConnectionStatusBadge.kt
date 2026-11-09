package com.example.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.network.ConnectionState
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusInfo
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning

@Composable
fun ConnectionStatusBadge(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (label, color, isLoading) = when (state) {
        is ConnectionState.Connected -> Triple("Connected", StatusSuccess, false)
        is ConnectionState.Connecting -> Triple("Connecting…", StatusInfo, true)
        is ConnectionState.AwaitingApproval -> Triple("Awaiting Approval", StatusWarning, true)
        is ConnectionState.Authenticating -> Triple("Authenticating…", CyanAccent, true)
        is ConnectionState.Discovering -> Triple("Discovering…", CyanAccent, true)
        is ConnectionState.ConnectionFailed -> Triple("Failed", StatusError, false)
        is ConnectionState.Disconnected -> Triple("Offline", MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), false)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(8.dp),
                    strokeWidth = 1.5.dp,
                    color = color
                )
                Spacer(modifier = Modifier.width(5.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }

            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
