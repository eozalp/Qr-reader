package com.example.model

import com.google.mlkit.vision.barcode.common.Barcode
import org.json.JSONArray

enum class BarcodeCategory(val title: String, val subtitle: String) {
    TWO_D_AND_STACKED("2D & Yığılı Matris Barkodlar", "Yüksek kapasiteli 2D matris ve yığılı barkodlar"),
    ONE_D_RETAIL("1D Perakende & Tüketici", "Standart süpermarket ve ürün tanımlama kodları"),
    ONE_D_LOGISTICS("1D Endüstriyel & Lojistik", "Depolama, kargo, sevkiyat ve takip kodları")
}

enum class BarcodeScanPreset(val label: String, val description: String) {
    ALL_FORMATS("Tüm Barkodlar (Otomatik Algıla)", "Tüm 2D, 3D/yığılı ve 1D barkod formatlarını tarar"),
    TWO_D_ONLY("Yalnızca 2D ve 3D Matris", "QR Kod, Data Matrix (DPM), PDF417 ve Aztec"),
    ONE_D_ONLY("Yalnızca 1D Çizgikodlar", "EAN, UPC, Code 128, Code 39, Codabar ve ITF"),
    CUSTOM("Özel Formatlar", "Barkod formatlarını manuel olarak tek tek seçin")
}

data class BarcodeFormatItem(
    val format: Int,
    val codeKey: String,
    val displayName: String,
    val category: BarcodeCategory,
    val badge: String, // "2D", "3D Stacked", "1D", "2D / DPM"
    val summary: String,
    val exampleUsage: String
)

object BarcodeCatalog {
    val ALL_ITEMS: List<BarcodeFormatItem> = listOf(
        // 2D & 3D Stacked
        BarcodeFormatItem(
            format = Barcode.FORMAT_QR_CODE,
            codeKey = "QR_CODE",
            displayName = "QR Code",
            category = BarcodeCategory.TWO_D_AND_STACKED,
            badge = "2D",
            summary = "Universal 2D square matrix code",
            exampleUsage = "URLs, text, contact cards, payments, math payloads"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_DATA_MATRIX,
            codeKey = "DATA_MATRIX",
            displayName = "Data Matrix",
            category = BarcodeCategory.TWO_D_AND_STACKED,
            badge = "2D / DPM",
            summary = "Compact matrix for micro marking and DPM",
            exampleUsage = "Electronics, PCB components, medical devices, aerospace parts"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_PDF417,
            codeKey = "PDF417",
            displayName = "PDF417",
            category = BarcodeCategory.TWO_D_AND_STACKED,
            badge = "3D Stacked",
            summary = "Multi-row stacked high-density 2D barcode",
            exampleUsage = "Driver's licenses, IDs, airline boarding passes, shipping parcels"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_AZTEC,
            codeKey = "AZTEC",
            displayName = "Aztec Code",
            category = BarcodeCategory.TWO_D_AND_STACKED,
            badge = "2D",
            summary = "Square matrix with central bullseye target",
            exampleUsage = "Railway tickets, airline e-boarding passes, transit ticketing"
        ),

        // 1D Retail & Consumer
        BarcodeFormatItem(
            format = Barcode.FORMAT_EAN_13,
            codeKey = "EAN_13",
            displayName = "EAN-13 (GTIN-13)",
            category = BarcodeCategory.ONE_D_RETAIL,
            badge = "1D",
            summary = "13-digit global standard retail product barcode",
            exampleUsage = "Retail supermarket products, books (ISBN), consumer goods worldwide"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_EAN_8,
            codeKey = "EAN_8",
            displayName = "EAN-8",
            category = BarcodeCategory.ONE_D_RETAIL,
            badge = "1D",
            summary = "Compact 8-digit barcode for small retail items",
            exampleUsage = "Small confectionery, cosmetics, small retail packaging"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_UPC_A,
            codeKey = "UPC_A",
            displayName = "UPC-A",
            category = BarcodeCategory.ONE_D_RETAIL,
            badge = "1D",
            summary = "12-digit standard Universal Product Code",
            exampleUsage = "United States & Canada supermarket & retail products"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_UPC_E,
            codeKey = "UPC_E",
            displayName = "UPC-E",
            category = BarcodeCategory.ONE_D_RETAIL,
            badge = "1D",
            summary = "Zero-suppressed compact version of UPC-A",
            exampleUsage = "Small retail items in North America"
        ),

        // 1D Industrial & Logistics
        BarcodeFormatItem(
            format = Barcode.FORMAT_CODE_128,
            codeKey = "CODE_128",
            displayName = "Code 128 (GS1-128)",
            category = BarcodeCategory.ONE_D_LOGISTICS,
            badge = "1D",
            summary = "High-density alphanumeric barcode with 128 ASCII chars",
            exampleUsage = "Shipping containers, FedEx/UPS labels, warehouse inventory"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_CODE_39,
            codeKey = "CODE_39",
            displayName = "Code 39",
            category = BarcodeCategory.ONE_D_LOGISTICS,
            badge = "1D",
            summary = "Variable-length alphanumeric industrial barcode",
            exampleUsage = "Automotive parts (AIAG), defense (DoD), asset badges"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_CODE_93,
            codeKey = "CODE_93",
            displayName = "Code 93",
            category = BarcodeCategory.ONE_D_LOGISTICS,
            badge = "1D",
            summary = "High-security compact alphanumeric barcode",
            exampleUsage = "Postal services, electronic part serial numbers"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_CODABAR,
            codeKey = "CODABAR",
            displayName = "Codabar (NW-7)",
            category = BarcodeCategory.ONE_D_LOGISTICS,
            badge = "1D",
            summary = "Self-checking numeric barcode with A/B/C/D markers",
            exampleUsage = "Blood banks, library books, delivery manifests, photo labs"
        ),
        BarcodeFormatItem(
            format = Barcode.FORMAT_ITF,
            codeKey = "ITF",
            displayName = "ITF (Interleaved 2 of 5)",
            category = BarcodeCategory.ONE_D_LOGISTICS,
            badge = "1D",
            summary = "Continuous numeric-only barcode for pairs of digits",
            exampleUsage = "Corrugated shipping cartons, distribution outer boxes"
        )
    )

    fun getFormatName(format: Int): String {
        return when (format) {
            Barcode.FORMAT_QR_CODE -> "QR CODE"
            Barcode.FORMAT_DATA_MATRIX -> "DATA MATRIX"
            Barcode.FORMAT_AZTEC -> "AZTEC"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_EAN_13 -> "EAN-13"
            Barcode.FORMAT_EAN_8 -> "EAN-8"
            Barcode.FORMAT_UPC_A -> "UPC-A"
            Barcode.FORMAT_UPC_E -> "UPC-E"
            Barcode.FORMAT_CODE_128 -> "CODE 128"
            Barcode.FORMAT_CODE_39 -> "CODE 39"
            Barcode.FORMAT_CODE_93 -> "CODE 93"
            Barcode.FORMAT_CODABAR -> "CODABAR"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_ALL_FORMATS -> "ALL FORMATS"
            else -> "BARCODE"
        }
    }

    fun getFormatBadge(format: Int): String {
        return ALL_ITEMS.firstOrNull { it.format == format }?.badge ?: "BARCODE"
    }

    fun resolveEffectiveFormats(preset: BarcodeScanPreset, customFormats: List<Int>): List<Int> {
        return when (preset) {
            BarcodeScanPreset.ALL_FORMATS -> listOf(Barcode.FORMAT_ALL_FORMATS)
            BarcodeScanPreset.TWO_D_ONLY -> listOf(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_PDF417
            )
            BarcodeScanPreset.ONE_D_ONLY -> listOf(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_ITF
            )
            BarcodeScanPreset.CUSTOM -> {
                if (customFormats.isEmpty()) {
                    listOf(Barcode.FORMAT_ALL_FORMATS)
                } else {
                    customFormats
                }
            }
        }
    }
}
