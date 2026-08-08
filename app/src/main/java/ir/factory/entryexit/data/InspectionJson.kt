package ir.factory.entryexit.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * [InspectionEntity.partsJson] is kept as a JSON string (not a Room-relational table), same
 * reasoning as always: no migration every time the part list or the fields on a part change.
 * This is the one place that (de)serializes it, so [Repository], [ir.factory.entryexit.ui
 * .ReportActivity]'s Excel export, and [ir.factory.entryexit.ui.view.VehicleDiagramView] all
 * agree on the shape.
 *
 * The legacy "ok" boolean is always written (WARN and BAD both write false), so any code that
 * still only reads "ok" — like the existing Excel export — keeps working untouched even though
 * newer records carry a three-way "status" too.
 */
object InspectionJson {

    fun serialize(parts: List<InspectionPartResult>): String =
        JSONArray().apply {
            for (part in parts) {
                put(JSONObject().apply {
                    put("name", part.name)
                    put("ok", part.ok)
                    put("status", part.status.name)
                    put("note", part.note ?: JSONObject.NULL)
                    put("photoUri", part.photoUri ?: JSONObject.NULL)
                    put("recurringSince", part.recurringSinceTimestamp ?: JSONObject.NULL)
                    put("repairedAt", part.repairedAt ?: JSONObject.NULL)
                })
            }
        }.toString()

    fun parse(json: String): List<InspectionPartResult> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val status = runCatching { PartStatus.valueOf(obj.optString("status")) }
                    .getOrDefault(if (obj.optBoolean("ok", true)) PartStatus.OK else PartStatus.BAD)
                InspectionPartResult(
                    name = obj.getString("name"),
                    status = status,
                    note = if (obj.isNull("note")) null else obj.optString("note"),
                    photoUri = if (obj.isNull("photoUri")) null else obj.optString("photoUri"),
                    recurringSinceTimestamp = if (obj.isNull("recurringSince")) null else obj.optLong("recurringSince"),
                    repairedAt = if (obj.isNull("repairedAt")) null else obj.optLong("repairedAt")
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Convenience for [ir.factory.entryexit.ui.FleetHeatmapActivity]: name -> how many
     *  inspections in the given list had that part as WARN or BAD. */
    fun defectCountsByPart(entities: List<InspectionEntity>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (entity in entities) {
            for (part in parse(entity.partsJson)) {
                if (part.status != PartStatus.OK) {
                    counts[part.name] = (counts[part.name] ?: 0) + 1
                }
            }
        }
        return counts
    }
}
