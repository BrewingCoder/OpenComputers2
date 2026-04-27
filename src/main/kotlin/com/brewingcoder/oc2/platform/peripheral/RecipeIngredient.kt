package com.brewingcoder.oc2.platform.peripheral

/**
 * One merged ingredient line on a recipe card. Exposes a per-cycle [count] so
 * orchestrators (e.g. `recipes.craft(itemId, n)`) can compute total demand
 * without having to peek at the underlying card pattern.
 */
data class RecipeIngredient(val id: String, val count: Int)
