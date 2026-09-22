package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DashThemePickerDialog(selected: DashThemeMode, onSelect: (DashThemeMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text("Dashboard theme", color = DashColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DashThemeMode.entries.forEach { mode ->
                    ThemeOption(mode, mode == selected) { onSelect(mode) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = DashColors.Accent) } }
    )
}

@Composable
private fun ThemeOption(mode: DashThemeMode, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val preview = when (mode) {
        DashThemeMode.AUTO -> Brush.linearGradient(listOf(Color(0xFF11151D), Color(0xFFF5F7FA)))
        DashThemeMode.NEON_DARK -> Brush.linearGradient(listOf(Color(0xFF071126), Color(0xFF6D3CFF)))
        DashThemeMode.CLEAN_LIGHT -> Brush.linearGradient(listOf(Color.White, Color(0xFFDCE8F7)))
        DashThemeMode.DARK_GLASS -> Brush.linearGradient(listOf(Color(0xFF05070B), Color(0xFF233A5F)))
        DashThemeMode.SPORTY -> Brush.linearGradient(listOf(Color(0xFF08090B), Color(0xFFFF334A)))
    }
    Row(
        modifier = Modifier.fillMaxWidth().border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.CardHi, shape)
            .background(DashColors.CardHi.copy(alpha = if (selected) 0.65f else 0.35f), shape).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 54.dp, height = 38.dp).background(preview, RoundedCornerShape(10.dp)))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(mode.title, color = DashColors.TextPrimary)
            Text(mode.description, color = DashColors.TextSecondary)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = DashColors.Accent)
    }
}
