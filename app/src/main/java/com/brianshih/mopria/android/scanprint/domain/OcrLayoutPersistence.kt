package com.brianshih.mopria.android.scanprint.domain

import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON codec used by DocumentStore's compressed per-page OCR sidecars. */
internal object OcrLayoutPersistence {
    private const val schemaVersion = 1
    private const val engineName = "mlkit-text-recognition-v2"

    fun encode(result: OcrResult.Applied): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("engine", engineName)
        put("text", result.text)
        put("layout", encodeLayout(result.layout))
    }

    fun decode(json: JSONObject): OcrResult.Applied? = runCatching {
        require(json.getInt("schemaVersion") == schemaVersion)
        val layout = decodeLayout(json.getJSONObject("layout"))
        OcrResult.Applied(
            text = json.optString("text", ""),
            layout = layout,
        )
    }.getOrNull()

    private fun encodeLayout(layout: OcrTextLayout): JSONObject = JSONObject().apply {
        put("imageWidth", layout.imageWidth)
        put("imageHeight", layout.imageHeight)
        put("blocks", JSONArray().apply { layout.blocks.forEach { put(encodeBlock(it)) } })
    }

    private fun decodeLayout(json: JSONObject): OcrTextLayout = OcrTextLayout(
        blocks = json.objects("blocks").map(::decodeBlock),
        imageWidth = json.optInt("imageWidth", 0).coerceAtLeast(0),
        imageHeight = json.optInt("imageHeight", 0).coerceAtLeast(0),
    )

    private fun encodeBlock(block: OcrTextBlock): JSONObject = JSONObject().apply {
        put("text", block.text)
        putOptional("bounds", block.bounds?.let(::encodeBounds))
        put("cornerPoints", encodePoints(block.cornerPoints))
        putOptional("recognizedLanguage", block.recognizedLanguage)
        put("lines", JSONArray().apply { block.lines.forEach { put(encodeLine(it)) } })
    }

    private fun decodeBlock(json: JSONObject): OcrTextBlock = OcrTextBlock(
        text = json.optString("text", ""),
        bounds = json.optJSONObject("bounds")?.let(::decodeBounds),
        cornerPoints = decodePoints(json.optJSONArray("cornerPoints")),
        recognizedLanguage = json.optionalString("recognizedLanguage"),
        lines = json.objects("lines").map(::decodeLine),
    )

    private fun encodeLine(line: OcrTextLine): JSONObject = JSONObject().apply {
        put("text", line.text)
        putOptional("bounds", line.bounds?.let(::encodeBounds))
        put("cornerPoints", encodePoints(line.cornerPoints))
        put("angle", line.angle.toDouble())
        putOptional("confidence", line.confidence?.toDouble())
        putOptional("recognizedLanguage", line.recognizedLanguage)
        put("elements", JSONArray().apply { line.elements.forEach { put(encodeElement(it)) } })
    }

    private fun decodeLine(json: JSONObject): OcrTextLine = OcrTextLine(
        text = json.optString("text", ""),
        bounds = json.optJSONObject("bounds")?.let(::decodeBounds),
        cornerPoints = decodePoints(json.optJSONArray("cornerPoints")),
        angle = json.optDouble("angle", 0.0).toFloat(),
        confidence = json.optionalFloat("confidence"),
        recognizedLanguage = json.optionalString("recognizedLanguage"),
        elements = json.objects("elements").map(::decodeElement),
    )

    private fun encodeElement(element: OcrTextElement): JSONObject = JSONObject().apply {
        put("text", element.text)
        putOptional("bounds", element.bounds?.let(::encodeBounds))
        put("cornerPoints", encodePoints(element.cornerPoints))
        put("angle", element.angle.toDouble())
        putOptional("confidence", element.confidence?.toDouble())
        putOptional("recognizedLanguage", element.recognizedLanguage)
        put("symbols", JSONArray().apply { element.symbols.forEach { put(encodeSymbol(it)) } })
    }

    private fun decodeElement(json: JSONObject): OcrTextElement = OcrTextElement(
        text = json.optString("text", ""),
        bounds = json.optJSONObject("bounds")?.let(::decodeBounds),
        cornerPoints = decodePoints(json.optJSONArray("cornerPoints")),
        angle = json.optDouble("angle", 0.0).toFloat(),
        confidence = json.optionalFloat("confidence"),
        recognizedLanguage = json.optionalString("recognizedLanguage"),
        symbols = json.objects("symbols").map(::decodeSymbol),
    )

    private fun encodeSymbol(symbol: OcrTextSymbol): JSONObject = JSONObject().apply {
        put("text", symbol.text)
        putOptional("bounds", symbol.bounds?.let(::encodeBounds))
        put("cornerPoints", encodePoints(symbol.cornerPoints))
        put("angle", symbol.angle.toDouble())
        putOptional("confidence", symbol.confidence?.toDouble())
        putOptional("recognizedLanguage", symbol.recognizedLanguage)
    }

    private fun decodeSymbol(json: JSONObject): OcrTextSymbol = OcrTextSymbol(
        text = json.optString("text", ""),
        bounds = json.optJSONObject("bounds")?.let(::decodeBounds),
        cornerPoints = decodePoints(json.optJSONArray("cornerPoints")),
        angle = json.optDouble("angle", 0.0).toFloat(),
        confidence = json.optionalFloat("confidence"),
        recognizedLanguage = json.optionalString("recognizedLanguage"),
    )

    private fun encodeBounds(bounds: OcrBounds): JSONObject = JSONObject().apply {
        put("left", bounds.left)
        put("top", bounds.top)
        put("right", bounds.right)
        put("bottom", bounds.bottom)
    }

    private fun decodeBounds(json: JSONObject): OcrBounds = OcrBounds(
        left = json.getInt("left"),
        top = json.getInt("top"),
        right = json.getInt("right"),
        bottom = json.getInt("bottom"),
    )

    private fun encodePoints(points: List<OcrPoint>): JSONArray = JSONArray().apply {
        points.forEach { point ->
            put(JSONObject().apply {
                put("x", point.x)
                put("y", point.y)
            })
        }
    }

    private fun decodePoints(array: JSONArray?): List<OcrPoint> = if (array == null) {
        emptyList()
    } else {
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { point ->
                OcrPoint(point.optInt("x", 0), point.optInt("y", 0))
            }
        }
    }

    private fun JSONObject.objects(name: String): List<JSONObject> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }

    private fun JSONObject.optionalString(name: String): String? =
        optString(name, "").takeIf(String::isNotBlank)

    private fun JSONObject.optionalFloat(name: String): Float? =
        takeIf { has(name) && !isNull(name) }
            ?.optDouble(name)
            ?.toFloat()
            ?.takeIf(Float::isFinite)

    private fun JSONObject.putOptional(name: String, value: Any?) {
        if (value != null) put(name, value)
    }
}
