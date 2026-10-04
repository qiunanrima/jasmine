package eu.kanade.presentation.browse.components

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.source.builtin.base.AlignedSourceCategory
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun SourceCategoriesDialog(
    categories: List<AlignedSourceCategory>,
    selectedCategory: String?,
    loading: Boolean,
    error: Exception?,
    onSelect: (AlignedSourceCategory) -> Unit,
    onRetry: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.categories)) },
        text = {
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(with(context) { error.formattedMessage })
                    TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
                }
                categories.isEmpty() -> Text(stringResource(MR.strings.no_results_found))
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).selectableGroup()) {
                    items(categories) { category ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(
                                    selected = category.name == selectedCategory,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(category) },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = category.name == selectedCategory, onClick = null)
                            Text(category.name, Modifier.weight(1f).padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_close)) }
        },
    )
}
