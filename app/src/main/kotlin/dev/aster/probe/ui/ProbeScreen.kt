package dev.aster.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aster.probe.ActivityLog

private val FeedHeight = 520.dp
private val EmptyFeedHeight = 220.dp

/** What is granted, what the agent is doing, and the one control that matters. */
@Composable
fun ProbeScreen(
    running: Boolean,
    grants: List<Grant>,
    entries: List<ActivityLog.Entry>,
    chosen: dev.aster.probe.Models.Provider?,
    providers: List<dev.aster.probe.Models.Provider>,
    providerKeys: Set<String>,
    model: String,
    models: List<dev.aster.probe.Models.Model>,
    effort: String,
    loadingModels: Boolean,
    onGrant: (Grant) -> Unit,
    onProvider: (dev.aster.probe.Models.Provider) -> Unit,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onToggle: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.ground)
            .safeDrawingPadding()
            .padding(horizontal = Gutter),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lockup()
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentStatus(running = running)
                Box(
                    modifier = Modifier.size(36.dp).pressable(onSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Glyph.settings,
                        contentDescription = "Settings",
                        tint = Ink.dim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        SectionLabel("PERMISSIONS", Modifier.padding(top = 24.dp))
        GroupCard(Modifier.padding(top = 8.dp)) {
            grants.forEachIndexed { i, grant ->
                GrantRow(grant, onGrant)
                if (i < grants.lastIndex) RowDivider()
            }
        }
        grants.filterNot { it.on }.forEach { grant ->
            Text(
                text = "${grant.label} is off. The agent ${grant.need}. Tap ${grant.label} to turn it on.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.dim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        SectionLabel("AGENT", Modifier.padding(top = 24.dp))
        ModelPicker(
            provider = chosen,
            providers = providers,
            keys = providerKeys,
            model = model,
            models = models,
            loadingModels = loadingModels,
            onProvider = onProvider,
            onModel = onModel,
            effort = effort,
            onEffort = onEffort,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("ACTIVITY")
            Row(
                modifier = Modifier.pressable(onHistory),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("HISTORY")
                Chevron()
            }
        }
        ActivityFeed(
            entries = entries,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (entries.isEmpty()) EmptyFeedHeight else FeedHeight)
                .padding(top = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        }
        AgentButton(running = running, onToggle = onToggle, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(Gutter))
    }
}
