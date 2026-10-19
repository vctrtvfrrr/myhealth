package br.etc.victor.myhealthbridge.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The capability catalog as the maintainer reads it, in `docs/capability-catalog.md`.
 *
 * The page is rendered from [HealthCapabilities] and compared here, so it can never describe another
 * coverage than the one this build has. When the catalog changes, this test writes the page it expects
 * next to the build's test results, to be copied over the committed one.
 */
class CapabilityCatalogDocumentTest {

    @Test
    fun `documents exactly the coverage the catalog declares`() {
        val root = File(System.getProperty("repositoryRoot") ?: error("Missing repositoryRoot"))
        val committed = File(root, DOCUMENT)
        val expected = render()

        if (!committed.exists() || committed.readText() != expected) {
            File("build/capability-catalog.md").writeText(expected)
        }
        assertEquals(expected, committed.takeIf { it.exists() }?.readText(), "$DOCUMENT is out of date")
    }

    private fun render(): String = buildString {
        appendLine("# Catálogo de capacidades")
        appendLine()
        appendLine(
            "Gerado de `HealthCapabilities` (`health-sync`) e verificado por `CapabilityCatalogDocumentTest`: " +
                "não edite à mão. Quando o catálogo muda, o teste falha e grava a versão nova em " +
                "`health-sync/build/capability-catalog.md`, que substitui esta.",
        )
        appendLine()
        appendLine(
            "Uma entrada por Health Category do Samsung Health Data SDK adotado. Uma categoria que o SDK " +
                "expõe e o catálogo não declara não é sincronizada e vira Maintenance Incident " +
                "(`uncataloged_category`). Tamanhos de página e de leitura são escolhas deste repositório, " +
                "não limites do SDK.",
        )
        appendLine()
        val implemented = HealthCapabilities.entries.count { it.implemented }
        appendLine("Implementadas: $implemented de ${HealthCapabilities.entries.size}.")
        appendLine()
        appendLine(
            "| Categoria | Classe | Situação | Permissão | Leituras | Paginação | Mudanças | Dados associados | " +
                "Identidade | Mapper | Projeção |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        HealthCapabilities.entries.forEach { entry ->
            val cells = listOf(
                "`${entry.category.id}`",
                kindOf(entry.kind),
                if (entry.implemented) "implementada" else "não implementada",
                "`${entry.permission.id}`",
                entry.readOperations.joinToString(", ") { "`${it.name}`" }.ifEmpty { "—" },
                paginationOf(entry.pagination),
                if (entry.supportsChanges) "sim" else "não",
                associatedOf(entry.associatedData),
                identityOf(entry.identity),
                entry.mapperVersion?.let { "`$it`" } ?: "—",
                entry.projection?.let { "`$it`" } ?: "—",
            )
            appendLine(cells.joinToString(" | ", prefix = "| ", postfix = " |"))
        }
    }

    private fun kindOf(kind: CoverageKind): String = when (kind) {
        CoverageKind.HEALTH_RECORD -> "Health Record"
        CoverageKind.OBSERVED_SNAPSHOT -> "Observed Snapshot"
        CoverageKind.PERMISSION_ONLY -> "só permissão"
    }

    private fun paginationOf(pagination: Pagination): String = when (pagination) {
        is Pagination.Records -> "${pagination.pageSize} registros por página"
        is Pagination.Windows -> "${pagination.windowsPerRead} janelas por leitura"
        Pagination.Unpaged -> "—"
    }

    private fun associatedOf(data: AssociatedData): String = when (data) {
        AssociatedData.None -> "—"
        is AssociatedData.Nested -> data.paths.joinToString(", ") { "`$it`" }
    }

    private fun identityOf(identity: CapabilityIdentity): String = when (identity) {
        is CapabilityIdentity.SamsungUid -> "`${identity.recordType}` + Samsung UID"
        is CapabilityIdentity.Snapshot -> "`${identity.snapshotType}`" + when (identity.window) {
            SnapshotWindowUnit.LOCAL_HOUR -> " + hora local"
            SnapshotWindowUnit.LOCAL_DAY -> " + dia local"
            null -> ", sem janela"
        }
        is CapabilityIdentity.CarriedBy -> "dentro de `${identity.carrier.id}`"
    }

    private companion object {
        const val DOCUMENT = "docs/capability-catalog.md"
    }
}
