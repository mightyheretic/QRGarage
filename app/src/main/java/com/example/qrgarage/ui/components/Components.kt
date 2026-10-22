package com.example.qrgarage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.example.qrgarage.data.db.ItemRow
import java.io.File

/** NFR-4 / HOME-4: generous targets for gloved or dirty hands. */
val LargeTouch = 64.dp
val ContentMaxWidth = 720.dp

/** NFR-6: keeps content readable on tablets and wall-mounted phones. */
fun Modifier.contentWidth(max: Dp = ContentMaxWidth) =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = actions,
    )
}

@Composable
fun BigButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = LargeTouch,
    enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors(),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = height),
        shape = RoundedCornerShape(20.dp),
        colors = colors,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
fun Thumbnail(file: File?, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            PhotoImage(file, contentDescription = null, modifier = Modifier.size(size), compact = true)
        } else {
            Icon(
                Icons.Outlined.Inventory2, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A stored item photo. A file that is missing or unreadable (e.g. not restored from a backup,
 * BK-9) shows an explicit "missing" marker instead of a blank box.
 */
@Composable
fun PhotoImage(
    file: File,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    compact: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    var failed by remember(file) { mutableStateOf(false) }
    Box(modifier.background(containerColor), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = file,
            contentDescription = contentDescription,
            contentScale = contentScale,
            onState = { failed = it is AsyncImagePainter.State.Error },
            modifier = Modifier.matchParentSize(),
        )
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.BrokenImage, contentDescription = if (compact) "Photo missing" else null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!compact) {
                    Text("Photo missing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * List row for an item. With [pathAsHeadline] the location path is the headline (SEARCH-3),
 * because the app's job is answering "where is it?".
 */
@Composable
fun ItemRowView(
    row: ItemRow,
    path: String?,
    photoFile: (String) -> File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pathAsHeadline: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val badges = buildList {
        row.quantity?.let { add("${formatQty(it)} ${row.unit.orEmpty()}".trim()) }
        if (row.isLowStock) add("Low stock")
        if (row.isLent) add("Lent out")
    }.joinToString(" · ")
    ListItem(
        modifier = modifier.clickable(onClick = onClick).heightIn(min = 72.dp),
        leadingContent = leading ?: { Thumbnail(row.thumbnailPath?.let(photoFile)) },
        headlineContent = {
            if (pathAsHeadline && path != null) {
                Text(path, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            Column {
                if (pathAsHeadline) {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else if (path != null) {
                    Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (badges.isNotEmpty()) {
                    Text(
                        badges,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (row.isLowStock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

fun formatQty(q: Double): String =
    if (q == q.toLong().toDouble()) q.toLong().toString() else "%.2f".format(q).trimEnd('0').trimEnd('.')

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
