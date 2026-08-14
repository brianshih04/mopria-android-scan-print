package com.brianshih.mopria.android.scanprint.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.DirectIppPrintPrompt
import com.brianshih.mopria.android.scanprint.domain.PrintOptions

/**
 * Direct IPP print-options sheet. Every control is shown only when the printer advertises that option
 * (capability-driven), and standard IPP/PWG keywords are mapped to localized labels; vendor-specific
 * keywords the app does not know fall back to the raw keyword. On confirm the chosen [PrintOptions] are
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
    val maximumSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maximumSheetHeight)
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
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
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.print_options_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConfirm(options) }) { Text(stringResource(R.string.documents_print)) }
            }
            // ModalBottomSheet consumes navigation insets before its content is measured on some
            // Android/Compose combinations. Keep a physical touch-safe gutter for gesture and
            // three-button navigation even when navigationBarsPadding() observes zero remaining inset.
            Spacer(Modifier.height(56.dp))
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
                selected?.let { localizedIppKeyword(it) } ?: "—",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(localizedIppKeyword(value)) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun localizedIppKeyword(keyword: String): String =
    ippKeywordLabelRes(keyword)?.let { stringResource(it) } ?: keyword

/** Standard IPP/PWG keywords with localized labels; unknown (vendor-specific) keywords return null. */
@StringRes
private fun ippKeywordLabelRes(keyword: String): Int? = when (keyword) {
    // sides (PWG 5100.7)
    "one-sided" -> R.string.ipp_sides_one
    "two-sided-long-edge" -> R.string.ipp_sides_two_long
    "two-sided-short-edge" -> R.string.ipp_sides_two_short
    // print-color-mode
    "auto" -> R.string.scan_size_auto
    "color" -> R.string.color_color
    "monochrome" -> R.string.color_grayscale
    "bi-level" -> R.string.color_black_white
    // print-quality
    "draft" -> R.string.ipp_quality_draft
    "normal" -> R.string.ipp_quality_normal
    "high" -> R.string.ipp_quality_high
    // orientation-requested
    "portrait" -> R.string.ipp_orientation_portrait
    "landscape" -> R.string.ipp_orientation_landscape
    "reverse-portrait" -> R.string.ipp_orientation_reverse_portrait
    "reverse-landscape" -> R.string.ipp_orientation_reverse_landscape
    // media (PWG 5101.1 self-describing size names); reuses the scan-side size labels
    "iso_a3_297x420mm" -> R.string.ipp_media_a3
    "iso_a4_210x297mm" -> R.string.scan_size_a4
    "iso_a5_148x210mm" -> R.string.scan_size_a5
    "na_letter_8.5x11in" -> R.string.scan_size_letter
    "na_legal_8.5x14in" -> R.string.ipp_media_legal
    "na_4x6_4x6in", "na_index-4x6_4x6in" -> R.string.scan_size_photo_4x6
    "na_5x7_5x7in", "na_index-5x7_5x7in" -> R.string.scan_size_photo_5x7
    "na_8x10_8x10in" -> R.string.scan_size_photo_8x10
    else -> null
}
