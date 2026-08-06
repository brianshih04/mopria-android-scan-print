package com.brianshih.mopria.android.scanprint.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.DirectIppPrintPrompt
import com.brianshih.mopria.android.scanprint.domain.PrintOptions

/**
 * Direct IPP print-options sheet. Every control is shown only when the printer advertises that option
 * (capability-driven), and the raw IPP keyword is displayed for each value — a future pass can map
 * keywords (e.g. `two-sided-long-edge`) to localized labels. On confirm the chosen [PrintOptions] are
 * submitted; [PrintOptions.coerceTo] in the provider guarantees nothing unsupported is sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintOptionsSheet(
    prompt: DirectIppPrintPrompt,
    onConfirm: (PrintOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val capabilities = prompt.capabilities
    var options by remember { mutableStateOf(prompt.options) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.print_options_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(16.dp))

            if (capabilities.copies.last > capabilities.copies.first) {
                val copies = options.copies ?: capabilities.copies.first
                val range = capabilities.copies.first.toFloat()..capabilities.copies.last.toFloat()
                Text("${stringResource(R.string.print_options_copies)}: $copies")
                Slider(
                    value = copies.toFloat(),
                    onValueChange = { options = options.copy(copies = it.toInt()) },
                    valueRange = range,
                    steps = (capabilities.copies.last - capabilities.copies.first - 1).coerceAtLeast(0),
                )
                Spacer(Modifier.width(12.dp))
            }

            if (capabilities.colorModes.isNotEmpty()) {
                OptionRow(R.string.print_options_color, capabilities.colorModes, options.colorMode) {
                    options = options.copy(colorMode = it)
                }
            }
            if (capabilities.sides.isNotEmpty()) {
                OptionRow(R.string.print_options_sides, capabilities.sides, options.sides) {
                    options = options.copy(sides = it)
                }
            }
            if (capabilities.qualities.isNotEmpty()) {
                OptionRow(R.string.print_options_quality, capabilities.qualities, options.quality) {
                    options = options.copy(quality = it)
                }
            }
            if (capabilities.media.isNotEmpty()) {
                OptionRow(R.string.print_options_media, capabilities.media, options.media) {
                    options = options.copy(media = it)
                }
            }
            if (capabilities.orientations.isNotEmpty()) {
                OptionRow(R.string.print_options_orientation, capabilities.orientations, options.orientation) {
                    options = options.copy(orientation = it)
                }
            }

            Spacer(Modifier.width(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.print_options_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConfirm(options) }) { Text(stringResource(R.string.documents_print)) }
            }
        }
    }
}

@Composable
private fun OptionRow(
    @StringRes labelRes: Int,
    values: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(labelRes))
            Text(
                selected ?: "—",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
