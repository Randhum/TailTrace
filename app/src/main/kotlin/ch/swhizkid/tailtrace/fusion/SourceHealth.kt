package ch.swhizkid.tailtrace.fusion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-source upstream-health registry.
 *
 * Network sources (OSM, WAZE) record OK/FAILED so the UI can distinguish
 * "scanned, found nothing" from "couldn't reach the data source."
 */
object SourceHealth {

    enum class Status { UNKNOWN, OK, FAILED }

    data class Health(
        val status: Status = Status.UNKNOWN,
        val lastFetchMs: Long = 0L,
        val message: String? = null
    )

    private val flows = DetectionSource.entries.associateWith { MutableStateFlow(Health()) }

    fun flowFor(source: DetectionSource): StateFlow<Health> = flows.getValue(source).asStateFlow()

    fun record(source: DetectionSource, ok: Boolean, message: String? = null) {
        flows.getValue(source).value = Health(
            status = if (ok) Status.OK else Status.FAILED,
            lastFetchMs = System.currentTimeMillis(),
            message = message
        )
    }

    fun reset() {
        for (flow in flows.values) flow.value = Health()
    }
}
