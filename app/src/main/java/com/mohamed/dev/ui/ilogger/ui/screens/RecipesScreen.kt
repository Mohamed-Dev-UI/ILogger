package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.ui.Recipe
import com.mohamed.dev.ui.ilogger.ui.Recipes
import com.mohamed.dev.ui.ilogger.ui.components.CodeBlock
import com.mohamed.dev.ui.ilogger.ui.components.Pill
import com.mohamed.dev.ui.ilogger.ui.components.ScreenIntro
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard

@Composable
fun RecipesScreen() {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenIntro(
                "Copy-ready recipes",
                "The implementation shortcuts used across this app and the repo's runnable examples. " +
                    "Expand a card, hit Copy, and paste into your module.",
            )
        }
        item {
            SectionCard(
                title = "How to read these",
                subtitle = "Each snippet is a verified shape, not pseudo-code.",
            ) {
                Text(
                    "The recipes assume artifacts from the README (group io.github.mohamed-dev-ui, version 0.1.0). " +
                        "Android snippets use the same imports the demo does; server snippets are from examples/server, " +
                        "which this project's CI builds and runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Android", MaterialTheme.colorScheme.primary)
                    Pill("JVM", MaterialTheme.colorScheme.secondary)
                    Pill("Ktor", MaterialTheme.colorScheme.tertiary)
                    Pill("Tests", MaterialTheme.colorScheme.outline)
                }
            }
        }
        Recipes.all.forEach { recipe ->
            item(key = recipe.id) {
                RecipeCard(recipe)
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: Recipe) {
    var expanded by rememberSaveable(recipe.id) { mutableStateOf(false) }
    SectionCard(title = recipe.title, subtitle = recipe.subtitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            recipe.tags.forEach { tag -> Pill(tag, MaterialTheme.colorScheme.secondary) }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (expanded) "Hide code" else "Show code", modifier = Modifier)
            }
        }
        AnimatedVisibility(visible = expanded) {
            CodeBlock(title = "${recipe.id}.kt", code = recipe.code)
        }
    }
}
