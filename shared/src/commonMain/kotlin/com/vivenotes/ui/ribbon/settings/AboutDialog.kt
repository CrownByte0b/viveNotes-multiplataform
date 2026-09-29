package com.vivenotes.ui.ribbon.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vivenotes.ui.components.DesktopDialogFrame
import com.vivenotes.ui.icons.DocumentSymbols
import com.vivenotes.ui.icons.SettingsSymbols
import multiplataform_vive.shared.generated.resources.Res
import multiplataform_vive.shared.generated.resources.vivenotes_icon
import org.jetbrains.compose.resources.painterResource

object AboutTags {
    const val Open = "settings-about-open"
    const val Dialog = "settings-about-dialog"
    const val Icon = "settings-about-icon"
    const val GitHub = "settings-about-github"
    const val GitHubIcon = "settings-about-github-icon"
    const val Website = "settings-about-website"
    const val Support = "settings-about-support"
    const val SupportIcon = "settings-about-support-icon"
    const val Close = "settings-about-close"
}

internal object AboutLinks {
    const val GitHub = "https://github.com/CrownByte0b/viveNotes-multiplataform"
    const val Website = "https://vivenotes.net"
    const val Support = "https://buymeacoffee.com/acidburn"
}

@Composable
internal fun AboutDialog(version: String, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    DesktopDialogFrame(
        title = "About Vive Notes",
        onDismiss = onDismiss,
        modifier = Modifier.testTag(AboutTags.Dialog),
        maxWidth = 480.dp,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(Res.drawable.vivenotes_icon),
                    contentDescription = "Vive Notes app icon",
                    modifier = Modifier.size(104.dp).testTag(AboutTags.Icon),
                )
                Text("Vive Notes", style = MaterialTheme.typography.headlineSmall)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Text("Version $version", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text("Notes, sketches and ideas in one workspace.",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)

                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text("Project links", modifier = Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.medium) {
                        Column {
                            AboutLinkRow("GitHub", AboutLinks.GitHub, AboutTags.GitHub,
                                SettingsSymbols.GitHub, MaterialTheme.colorScheme.onSurface,
                                AboutTags.GitHubIcon) {
                                runCatching { uriHandler.openUri(AboutLinks.GitHub) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AboutLinkRow("Website", AboutLinks.Website, AboutTags.Website,
                                DocumentSymbols.Link, MaterialTheme.colorScheme.primary) {
                                runCatching { uriHandler.openUri(AboutLinks.Website) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AboutLinkRow("Support the project", AboutLinks.Support, AboutTags.Support,
                                SettingsSymbols.Favorite, Color(0xFFE53935), AboutTags.SupportIcon) {
                                runCatching { uriHandler.openUri(AboutLinks.Support) }
                            }
                        }
                    }
                }
                Text("CROWNBYTE LLC · Source First License 1.1",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        },
        actions = {
            Button(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(AboutTags.Close)) { Text("Close") }
        },
    )
}

@Composable
private fun AboutLinkRow(
    label: String,
    url: String,
    tag: String,
    icon: ImageVector,
    iconTint: Color,
    iconTag: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
            .testTag(tag).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null,
            modifier = Modifier.size(20.dp).then(if (iconTag == null) Modifier else Modifier.testTag(iconTag)),
            tint = iconTint)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(SettingsSymbols.ArrowOutward, contentDescription = null,
            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
