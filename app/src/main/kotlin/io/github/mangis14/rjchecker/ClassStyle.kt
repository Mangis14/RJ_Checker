package io.github.mangis14.rjchecker

import androidx.compose.ui.graphics.Color

/**
 * Vizualny styl triedy - farba a nazov pre "marketingovy" tag.
 *
 * Farby su volene tak, aby davali zmysel samy o sebe: cim vyssi komfort, tym
 * teplejsia a sytejsia farba. Low cost je neutralna siva (nic navyse), Standard
 * pokojna modra, Relax zelena (oddych), Business zlata (premium), lozka a
 * lehatka tlmena nocna modrofialova.
 */
data class ClassStyle(
    val label: String,
    val background: Color,
    val foreground: Color,
)

private val Grey = ClassStyle("Low cost", Color(0xFFDDDDD8), Color(0xFF44443F))
private val Blue = ClassStyle("Standard", Color(0xFFCFE0F5), Color(0xFF17395E))
private val Green = ClassStyle("Relax", Color(0xFFC7E8CE), Color(0xFF14512A))
private val Gold = ClassStyle("Business", Color(0xFFF3DFA6), Color(0xFF5E4406))
private val Night = ClassStyle("Lôžko", Color(0xFFD3D0EA), Color(0xFF302A5E))

/**
 * Kluce tried su technicke (C0, C1, C2), takze sa prekladaju. Neznamy kluc
 * dostane neutralny styl a povodny nazov - radsej surovy kluc ako nespravny tag.
 */
fun classStyle(key: String, title: String? = null): ClassStyle = when {
    key in setOf("C0", "TRAIN_STANDARD_PL", "TRAIN_R23_STANDARD", "TRAIN_R8_STANDARD") -> Blue
    key == "TRAIN_STANDARD_PLUS" -> Blue.copy(label = "Standard PLUS", background = Color(0xFFBBD4F2))
    key in setOf("C1", "TRAIN_R23_RELAX") -> Green
    key in setOf("C2", "TRAIN_1ST_CLASS", "TRAIN_R23_BUSINESS") -> Gold
    key in setOf("TRAIN_LOW_COST", "TRAIN_2ND_CLASS", "TRAIN_R23_LOW_COST") -> Grey
    key.startsWith("TRAIN_COUCHETTE") -> Night.copy(
        label = when {
            key.contains("WOMEN") -> "Lôžko (ženy)"
            key.contains("BUSINESS") -> "Lôžko – vlastné kupé"
            key.contains("STANDARD") -> "Ležadlo"
            else -> "Lôžko"
        },
    )
    key.startsWith("BUS_") -> Grey.copy(label = title ?: "Bus")
    else -> ClassStyle(title ?: key, Color(0xFFE2E2DC), Color(0xFF4A4A45))
}
