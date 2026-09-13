package dev.aster.probe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aster.probe.Models

private enum class Target { PROVIDER, MODEL, EFFORT }

/**
 * Provider, then model. The provider list is the shared catalog and the model
 * list is what that provider actually serves, so the two cannot disagree.
 */
@Composable
fun ModelPicker(
    provider: Models.Provider?,
    providers: List<Models.Provider>,
    keys: Set<String>,
    model: String,
    models: List<Models.Model>,
    loadingModels: Boolean,
    onProvider: (Models.Provider) -> Unit,
    onModel: (String) -> Unit,
    effort: String,
    onEffort: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<Target?>(null) }
    Column(modifier) {
        GroupCard {
            SettingRow(
                label = "Provider",
                value = provider?.name ?: "Choose a provider",
                onClick = { open = Target.PROVIDER },
            )
            RowDivider()
            SettingRow(
                label = "Model",
                value = models.firstOrNull { it.id == model }?.name ?: Models.pretty(model),
                onClick = { open = Target.MODEL },
            )
            RowDivider()
            SettingRow(
                label = "Effort",
                value = effort.ifEmpty { "Default" },
                onClick = { open = Target.EFFORT },
            )
        }
        val hint = when {
            provider != null && !Models.usable(provider, keys) ->
                "No key for ${provider.name} on this phone. Add ${provider.keyVars.joinToString(" or ")} under the settings icon, then choose the provider again."
            loadingModels -> "Loading models from ${provider?.name ?: "the provider"}…"
            models.isEmpty() && provider != null -> "The model list did not load. The agent uses ${Models.pretty(model)}."
            else -> ""
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.dim,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    when (open) {
        Target.PROVIDER -> PickerSheet(
            label = "PROVIDER",
            options = providers.map { p ->
                Option(
                    id = p.id,
                    title = p.name,
                    detail = if (Models.usable(p, keys)) null else "needs ${p.keyVars.joinToString(" or ")}",
                    usable = Models.usable(p, keys),
                )
            },
            chosenId = provider?.id,
            onPick = { picked ->
                open = null
                providers.firstOrNull { it.id == picked.id }?.let(onProvider)
            },
            onDismiss = { open = null },
        )
        Target.MODEL -> PickerSheet(
            label = "MODEL",
            options = models.map { Option(id = it.id, title = it.name) },
            chosenId = model,
            onPick = { open = null; onModel(it.id) },
            onDismiss = { open = null },
        )
        Target.EFFORT -> PickerSheet(
            label = "EFFORT",
            options = listOf(Option(id = "", title = "Default", detail = "whatever the provider does on its own")) +
                Models.EFFORTS.map { Option(id = it, title = it) },
            chosenId = effort,
            onPick = { open = null; onEffort(it.id) },
            onDismiss = { open = null },
        )
        null -> Unit
    }
}
