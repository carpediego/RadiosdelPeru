package pe.radiosdelperu.app.catalog

/**
 * Una emisora del catálogo.
 *
 * [candidates] es una cadena de URLs de entrada, en orden de preferencia. Nunca se guardan
 * URLs finales: los proveedores (mdstrm, zeno, etc.) redirigen a una CDN con token que caduca.
 */
data class Station(
    val id: String,
    val name: String,
    val tagline: String,
    val category: String,
    val color: String,
    val priority: Int,
    val active: Boolean,
    val candidates: List<String>,
    val providerKind: String? = null,
    val providerId: String? = null
) {
    val initials: String
        get() = name
            .split(" ", "-", ".", "(", ")")
            .filter { it.isNotBlank() && it.first().isLetter() }
            .take(2)
            .joinToString("") { it.first().uppercase() }

    val streamUrl: String get() = candidates.first()
}
