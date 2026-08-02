package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PrintScreen(
    onChooseFiles: () -> Unit,
    onOpenDocuments: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("選擇列印內容", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(58.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Print,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = "PDF")
                        Icon(Icons.Outlined.Image, contentDescription = "JPEG 或 PNG")
                        Text(
                            "PDF · JPEG · PNG",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Button(onClick = onChooseFiles, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("從手機選擇")
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onOpenDocuments, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("已掃描文件")
            }
        }
    }
}
