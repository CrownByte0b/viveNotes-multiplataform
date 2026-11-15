package com.vivenotes.ui.ribbon.settings

import com.vivenotes.localization.Strings
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.vivenotes.ui.components.WavyLoadingIndicator
import com.vivenotes.ui.update.AppUpdates
import com.vivenotes.ui.update.UpdateCheck
import com.vivenotes.ui.update.UpdateState
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
    const val Updates = "settings-about-updates"
    const val UpdateStatus = "settings-about-update-status"
    const val UpdateButton = "settings-about-update-button"
}

internal object AboutLinks {
    const val GitHub = "https://github.com/CrownByte0b/viveNotes-multiplataform"
    const val Website = "https://vivenotes.net"
    const val Support = "https://buymeacoffee.com/acidburn"
}

@Composable
internal fun AboutDialog(version: String, onDismiss: () -> Unit, updates: AppUpdates? = null) {
    val uriHandler = LocalUriHandler.current
    if (updates != null) {
        // The update dialog takes over once it has something to show, so keys reach it rather than About.
        val offering = updates.state.collectAsState().value != UpdateState.Idle
        LaunchedEffect(offering) { if (offering) onDismiss() }
    }
    DesktopDialogFrame(
        title = Strings.about_vive_notes_kmp,
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
                    contentDescription = Strings.vive_notes_kmp_app_icon,
                    modifier = Modifier.size(104.dp).testTag(AboutTags.Icon),
                )
                Text(Strings.vive_notes_kmp, style = MaterialTheme.typography.headlineSmall)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Text(Strings.version_value(version), modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(Strings.notes_sketches_and_ideas_in_one_workspace,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)

                if (updates != null) UpdatesSection(version, updates)

                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text(Strings.project_links, modifier = Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.medium) {
                        Column {
                            AboutLinkRow(Strings.github, AboutLinks.GitHub, AboutTags.GitHub,
                                SettingsSymbols.GitHub, MaterialTheme.colorScheme.onSurface,
                                AboutTags.GitHubIcon) {
                                runCatching { uriHandler.openUri(AboutLinks.GitHub) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AboutLinkRow(Strings.website, AboutLinks.Website, AboutTags.Website,
                                DocumentSymbols.Link, MaterialTheme.colorScheme.primary) {
                                runCatching { uriHandler.openUri(AboutLinks.Website) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AboutLinkRow(Strings.support_the_project, AboutLinks.Support, AboutTags.Support,
                                SettingsSymbols.Favorite, Color(0xFFE53935), AboutTags.SupportIcon) {
                                runCatching { uriHandler.openUri(AboutLinks.Support) }
                            }
                        }
                    }
                }
                Text(Strings.crownbyte_llc_source_first_license_1_1,
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        },
        actions = {
            Button(onClick = onDismiss, shape = MaterialTheme.shapes.small,
                modifier = Modifier.testTag(AboutTags.Close)) { Text(Strings.close) }
        },
    )
}

/** Whether a newer release exists, as the launch check or the last request found, and a button to ask again. */
@Composable
private fun UpdatesSection(version: String, updates: AppUpdates) {
    val check by updates.check.collectAsState()
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag(AboutTags.Updates)) {
        Text(Strings.updates, modifier = Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val leading = Modifier.size(20.dp)
                when (check) {
                    UpdateCheck.Checking -> WavyLoadingIndicator(leading, size = 20.dp)
                    UpdateCheck.UpToDate -> Icon(SettingsSymbols.CheckCircle, null, leading, MaterialTheme.colorScheme.primary)
                    is UpdateCheck.Found -> Icon(SettingsSymbols.CloudDownload, null, leading, MaterialTheme.colorScheme.primary)
                    is UpdateCheck.Failed -> Icon(SettingsSymbols.CloudOff, null, leading, MaterialTheme.colorScheme.error)
                    UpdateCheck.NotChecked -> Icon(SettingsSymbols.CloudDownload, null, leading,
                        MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val (title, detail) = when (val current = check) {
                    UpdateCheck.NotChecked -> Strings.update_not_checked to null
                    UpdateCheck.Checking -> Strings.update_checking to null
                    UpdateCheck.UpToDate -> Strings.update_up_to_date to Strings.update_up_to_date_message(version)
                    is UpdateCheck.Found -> Strings.update_found(current.version) to Strings.update_found_message(version)
                    is UpdateCheck.Failed -> Strings.update_check_failed to current.reason
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp).testTag(AboutTags.UpdateStatus)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    if (!detail.isNullOrBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall,
                            color = if (check is UpdateCheck.Failed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val label = when (check) {
                    UpdateCheck.NotChecked, UpdateCheck.Checking -> Strings.update_check_now
                    UpdateCheck.UpToDate, is UpdateCheck.Failed -> Strings.update_check_again
                    is UpdateCheck.Found -> Strings.update_view
                }
                val button = Modifier.testTag(AboutTags.UpdateButton)
                if (check is UpdateCheck.Found) {
                    // Something to act on stands out; a check to repeat does not.
                    Button(onClick = updates::checkNow, shape = MaterialTheme.shapes.small, modifier = button) { Text(label) }
                } else {
                    OutlinedButton(onClick = updates::checkNow, enabled = check != UpdateCheck.Checking,
                        shape = MaterialTheme.shapes.small, modifier = button) { Text(label) }
                }
            }
        }
    }
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
