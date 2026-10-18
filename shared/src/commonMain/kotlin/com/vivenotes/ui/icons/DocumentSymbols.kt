package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Exact Material Symbols Rounded path data copied from the Android ribbon drawables. */
object DocumentSymbols {
    val ContentPaste: ImageVector by lazy {
        ImageVector.Builder(
            name = "ContentPaste",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h167q11-35 43-57.5t70-22.5q40 0 71.5 22.5T594-840h166q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Zm0-80h560v-560h-80v80q0 17-11.5 28.5T640-640H320q-17 0-28.5-11.5T280-680v-80h-80v560Zm280-560q17 0 28.5-11.5T520-800q0-17-11.5-28.5T480-840q-17 0-28.5 11.5T440-800q0 17 11.5 28.5T480-760Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val ContentCut: ImageVector by lazy {
        ImageVector.Builder(
            name = "ContentCut",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("m480-400-94 94q8 15 11 32t3 34q0 66-47 113T240-80q-66 0-113-47T80-240q0-66 47-113t113-47q17 0 34 3t32 11l94-94-94-94q-15 8-32 11t-34 3q-66 0-113-47T80-720q0-66 47-113t113-47q66 0 113 47t47 113q0 17-3 34t-11 32l438 438q27 27 12 61.5T783-120q-11 0-21.5-4.5T743-137L480-400Zm120-120-80-80 223-223q8-8 18.5-12.5T783-840q38 0 52.5 35T823-743L600-520ZM240-640q33 0 56.5-23.5T320-720q0-33-23.5-56.5T240-800q-33 0-56.5 23.5T160-720q0 33 23.5 56.5T240-640Zm240 180q8 0 14-6t6-14q0-8-6-14t-14-6q-8 0-14 6t-6 14q0 8 6 14t14 6ZM240-160q33 0 56.5-23.5T320-240q0-33-23.5-56.5T240-320q-33 0-56.5 23.5T160-240q0 33 23.5 56.5T240-160Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val ContentCopy: ImageVector by lazy {
        ImageVector.Builder(
            name = "ContentCopy",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M360-240q-33 0-56.5-23.5T280-320v-480q0-33 23.5-56.5T360-880h360q33 0 56.5 23.5T800-800v480q0 33-23.5 56.5T720-240H360Zm0-80h360v-480H360v480ZM200-80q-33 0-56.5-23.5T120-160v-520q0-17 11.5-28.5T160-720q17 0 28.5 11.5T200-680v520h400q17 0 28.5 11.5T640-120q0 17-11.5 28.5T600-80H200Zm160-240v-480 480Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatBold: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatBold",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M352-200q-33 0-56.5-23.5T272-280v-400q0-33 23.5-56.5T352-760h141q65 0 120 40t55 111q0 51-23 78.5T602-491q25 11 55.5 41t30.5 90q0 89-65 124.5T501-200H352Zm41-112h104q48 0 58.5-24.5T566-372q0-11-10.5-35.5T494-432H393v120Zm0-228h93q33 0 48-17t15-38q0-24-17-39t-44-15h-95v109Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatItalic: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatItalic",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M250-200q-21 0-35.5-14.5T200-250q0-21 14.5-35.5T250-300h110l120-360H370q-21 0-35.5-14.5T320-710q0-21 14.5-35.5T370-760h300q21 0 35.5 14.5T720-710q0 21-14.5 35.5T670-660h-90L460-300h90q21 0 35.5 14.5T600-250q0 21-14.5 35.5T550-200H250Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatUnderlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatUnderlined",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M240-120q-17 0-28.5-11.5T200-160q0-17 11.5-28.5T240-200h480q17 0 28.5 11.5T760-160q0 17-11.5 28.5T720-120H240Zm240-160q-101 0-157-63t-56-167v-279q0-21 15.5-36t36.5-15q21 0 36 15t15 36v285q0 56 28 91t82 35q54 0 82-35t28-91v-285q0-21 15.5-36t36.5-15q21 0 36 15t15 36v279q0 104-56 167t-157 63Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatStrikethrough: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatStrikethrough",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M120-400q-17 0-28.5-11.5T80-440q0-17 11.5-28.5T120-480h720q17 0 28.5 11.5T880-440q0 17-11.5 28.5T840-400H120Zm300-160v-120H260q-25 0-42.5-17.5T200-740q0-25 17.5-42.5T260-800h440q25 0 42.5 17.5T760-740q0 25-17.5 42.5T700-680H540v120H420Zm0 240h120v100q0 25-17.5 42.5T480-160q-25 0-42.5-17.5T420-220v-100Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatClear: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatClear",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("m528-546-93-93-26.5-26.5L382-692q-19-19-8.5-43.5T411-760h329q25 0 42.5 17.5T800-700q0 25-17.5 42.5T740-640H568l-40 94ZM764-84 460-388l-64 151q-7 17-22.5 27T340-200q-32 0-50-27t-5-57l83-196L84-764q-11-11-11-28t11-28q11-11 28-11t28 11l680 680q11 11 11 28t-11 28q-11 11-28 11t-28-11Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatIndentDecrease: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatIndentDecrease",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M160-120q-17 0-28.5-11.5T120-160q0-17 11.5-28.5T160-200h640q17 0 28.5 11.5T840-160q0 17-11.5 28.5T800-120H160Zm320-160q-17 0-28.5-11.5T440-320q0-17 11.5-28.5T480-360h320q17 0 28.5 11.5T840-320q0 17-11.5 28.5T800-280H480Zm0-160q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520h320q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H480Zm0-160q-17 0-28.5-11.5T440-640q0-17 11.5-28.5T480-680h320q17 0 28.5 11.5T840-640q0 17-11.5 28.5T800-600H480ZM160-760q-17 0-28.5-11.5T120-800q0-17 11.5-28.5T160-840h640q17 0 28.5 11.5T840-800q0 17-11.5 28.5T800-760H160Zm86 406L134-466q-6-6-6-14t6-14l112-112q10-10 22-5t12 19v224q0 14-12 19t-22-5Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatIndentIncrease: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatIndentIncrease",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M160-120q-17 0-28.5-11.5T120-160q0-17 11.5-28.5T160-200h640q17 0 28.5 11.5T840-160q0 17-11.5 28.5T800-120H160Zm320-160q-17 0-28.5-11.5T440-320q0-17 11.5-28.5T480-360h320q17 0 28.5 11.5T840-320q0 17-11.5 28.5T800-280H480Zm0-160q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520h320q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H480Zm0-160q-17 0-28.5-11.5T440-640q0-17 11.5-28.5T480-680h320q17 0 28.5 11.5T840-640q0 17-11.5 28.5T800-600H480ZM160-760q-17 0-28.5-11.5T120-800q0-17 11.5-28.5T160-840h640q17 0 28.5 11.5T840-800q0 17-11.5 28.5T800-760H160Zm-6 406q-10 10-22 5t-12-19v-224q0-14 12-19t22 5l112 112q6 6 6 14t-6 14L154-354Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatAlignLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatAlignLeft",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M160-120q-17 0-28.5-11.5T120-160q0-17 11.5-28.5T160-200h640q17 0 28.5 11.5T840-160q0 17-11.5 28.5T800-120H160Zm0-160q-17 0-28.5-11.5T120-320q0-17 11.5-28.5T160-360h400q17 0 28.5 11.5T600-320q0 17-11.5 28.5T560-280H160Zm0-160q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h640q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H160Zm0-160q-17 0-28.5-11.5T120-640q0-17 11.5-28.5T160-680h400q17 0 28.5 11.5T600-640q0 17-11.5 28.5T560-600H160Zm0-160q-17 0-28.5-11.5T120-800q0-17 11.5-28.5T160-840h640q17 0 28.5 11.5T840-800q0 17-11.5 28.5T800-760H160Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatAlignCenter: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatAlignCenter",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M160-120q-17 0-28.5-11.5T120-160q0-17 11.5-28.5T160-200h640q17 0 28.5 11.5T840-160q0 17-11.5 28.5T800-120H160Zm160-160q-17 0-28.5-11.5T280-320q0-17 11.5-28.5T320-360h320q17 0 28.5 11.5T680-320q0 17-11.5 28.5T640-280H320ZM160-440q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h640q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H160Zm160-160q-17 0-28.5-11.5T280-640q0-17 11.5-28.5T320-680h320q17 0 28.5 11.5T680-640q0 17-11.5 28.5T640-600H320ZM160-760q-17 0-28.5-11.5T120-800q0-17 11.5-28.5T160-840h640q17 0 28.5 11.5T840-800q0 17-11.5 28.5T800-760H160Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val FormatAlignRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "FormatAlignRight",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M160-760q-17 0-28.5-11.5T120-800q0-17 11.5-28.5T160-840h640q17 0 28.5 11.5T840-800q0 17-11.5 28.5T800-760H160Zm240 160q-17 0-28.5-11.5T360-640q0-17 11.5-28.5T400-680h400q17 0 28.5 11.5T840-640q0 17-11.5 28.5T800-600H400ZM160-440q-17 0-28.5-11.5T120-480q0-17 11.5-28.5T160-520h640q17 0 28.5 11.5T840-480q0 17-11.5 28.5T800-440H160Zm240 160q-17 0-28.5-11.5T360-320q0-17 11.5-28.5T400-360h400q17 0 28.5 11.5T840-320q0 17-11.5 28.5T800-280H400ZM160-120q-17 0-28.5-11.5T120-160q0-17 11.5-28.5T160-200h640q17 0 28.5 11.5T840-160q0 17-11.5 28.5T800-120H160Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val StylusHighlighter: ImageVector by lazy {
        ImageVector.Builder(
            name = "StylusHighlighter",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M320-320q-17 0-28.5-11.5T280-360v-400q0-33 23.5-56.5T360-840q9 0 18 2t17 6l240 119q20 10 32.5 29.5T680-641v281q0 17-11.5 28.5T640-320H320Zm40-80h240v-241L360-760v360Zm0 0h240-240ZM215-120q-20 0-32.5-16.5T177-173l5-12q8-25 29-40t47-15h444q26 0 47 15t29 40l5 12q7 20-5.5 36.5T745-120H215Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val Function: ImageVector by lazy {
        ImageVector.Builder(
            name = "Function",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M221-120q-45 0-73-24t-28-64q0-32 17-51.5t43-19.5q25 0 42.5 17t17.5 41q0 5-.5 9t-1.5 9q5-1 8.5-5.5T252-221l62-339h-74q-17 0-28.5-11.5T200-600q0-17 11.5-28.5T240-640h89l21-114q7-38 37.5-62t72.5-24q44 0 72 26t28 65q0 30-17 49.5T500-680q-25 0-42.5-17T440-739q0-5 .5-9t1.5-9q-6 2-9 6t-5 12l-17 99h149q17 0 28.5 11.5T600-600q0 15-9.5 26T567-561l53 60 53-60q-14-2-23.5-13t-9.5-26q0-17 11.5-28.5T680-640h120q17 0 28.5 11.5T840-600q0 17-11.5 28.5T800-560h-22L673-440l105 120h22q17 0 28.5 11.5T840-280q0 17-11.5 28.5T800-240H680q-17 0-28.5-11.5T640-280q0-15 9.5-26t23.5-13l-53-61-53 61q14 2 23.5 13t9.5 26q0 17-11.5 28.5T560-240H440q-17 0-28.5-11.5T400-280q0-17 11.5-28.5T440-320h22l105-120-105-120h-66l-64 344q-8 45-37 70.5T221-120Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val Link: ImageVector by lazy {
        ImageVector.Builder(
            name = "Link",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            // Android's ms_rounded_link.xml is the older 24-unit symbol, with no offset group.
            viewportWidth = 24f, viewportHeight = 24f,
        ).group {
            addPath(addPathNodes("M3.9,12c0,-1.16 0.94,-2.1 2.1,-2.1h4v-2H6c-2.21,0 -4,1.79 -4,4s1.79,4 4,4h4v-2H6c-1.16,0 -2.1,-0.94 -2.1,-2.1zM8,13h8v-2H8v2zM18,8h-4v2h4c1.16,0 2.1,0.94 2.1,2.1s-0.94,2.1 -2.1,2.1h-4v2h4c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val Image: ImageVector by lazy {
        ImageVector.Builder(
            name = "Image",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
            // Android's ms_rounded_image.xml is drawn in 0..960 already, with no offset group.
        ).group {
            addPath(addPathNodes("M200,840Q167,840 143.5,816.5Q120,793 120,760L120,200Q120,167 143.5,143.5Q167,120 200,120L760,120Q793,120 816.5,143.5Q840,167 840,200L840,760Q840,793 816.5,816.5Q793,840 760,840L200,840ZM200,760L760,760Q760,760 760,760Q760,760 760,760L760,200Q760,200 760,200Q760,200 760,200L200,200Q200,200 200,200Q200,200 200,200L200,760Q200,760 200,760Q200,760 200,760ZM280,680L680,680Q692,680 698,669Q704,658 696,648L586,501Q580,493 570,493Q560,493 554,501L450,640L376,541Q370,533 360,533Q350,533 344,541L264,648Q256,658 262,669Q268,680 280,680Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val ArrowDropDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowDropDown",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group(translationY = 960f) {
            addPath(addPathNodes("M459-381 314-526q-3-3-4.5-6.5T308-540q0-8 5.5-14t14.5-6h304q9 0 14.5 6t5.5 14q0 2-6 14L501-381q-5 5-10 7t-11 2q-6 0-11-2t-10-7Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    // The Picture menu's three sources: Android's ms_rounded_photo_library, ms_rounded_image_search and
    // ms_rounded_photo_camera, drawn in 0..960 like ms_rounded_image.

    val PhotoLibrary: ImageVector by lazy {
        ImageVector.Builder(
            name = "PhotoLibrary",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group {
            addPath(addPathNodes("M530,500L484,440Q478,432 468,432Q458,432 452,440L385,528Q377,538 382.5,549Q388,560 401,560L719,560Q732,560 737.5,549Q743,538 735,528L638,401Q632,393 622,393Q612,393 606,401L530,500ZM320,720Q287,720 263.5,696.5Q240,673 240,640L240,160Q240,127 263.5,103.5Q287,80 320,80L800,80Q833,80 856.5,103.5Q880,127 880,160L880,640Q880,673 856.5,696.5Q833,720 800,720L320,720ZM320,640L800,640Q800,640 800,640Q800,640 800,640L800,160Q800,160 800,160Q800,160 800,160L320,160Q320,160 320,160Q320,160 320,160L320,640Q320,640 320,640Q320,640 320,640ZM160,880Q127,880 103.5,856.5Q80,833 80,800L80,280Q80,263 91.5,251.5Q103,240 120,240Q137,240 148.5,251.5Q160,263 160,280L160,800Q160,800 160,800Q160,800 160,800L680,800Q697,800 708.5,811.5Q720,823 720,840Q720,857 708.5,868.5Q697,880 680,880L160,880ZM320,160L320,160Q320,160 320,160Q320,160 320,160L320,640Q320,640 320,640Q320,640 320,640L320,640Q320,640 320,640Q320,640 320,640L320,160Q320,160 320,160Q320,160 320,160Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val ImageSearch: ImageVector by lazy {
        ImageVector.Builder(
            name = "ImageSearch",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group {
            addPath(addPathNodes("M200,840Q167,840 143.5,816.5Q120,793 120,760L120,200Q120,167 143.5,143.5Q167,120 200,120L360,120Q377,120 388.5,131.5Q400,143 400,160Q400,177 388.5,188.5Q377,200 360,200L200,200Q200,200 200,200Q200,200 200,200L200,760Q200,760 200,760Q200,760 200,760L760,760Q760,760 760,760Q760,760 760,760L760,626Q760,609 771.5,597.5Q783,586 800,586Q817,586 828.5,597.5Q840,609 840,626L840,760Q840,793 816.5,816.5Q793,840 760,840L200,840ZM450,640L554,501Q560,493 570,493Q580,493 586,501L696,648Q704,658 698,669Q692,680 680,680L280,680Q268,680 262,669Q256,658 264,648L344,541Q350,533 360,533Q370,533 376,541L450,640ZM642,440Q568,440 516,387.5Q464,335 464,260Q464,185 516.5,132.5Q569,80 644,80Q719,80 771.5,132.5Q824,185 824,260Q824,287 816,312Q808,337 796,358L890,452Q901,463 901,480Q901,497 890,508Q879,519 862,519Q845,519 834,508L738,412Q717,426 693,433Q669,440 642,440ZM644,360Q686,360 715,331Q744,302 744,260Q744,218 715,189Q686,160 644,160Q602,160 573,189Q544,218 544,260Q544,302 573,331Q602,360 644,360Z"), fill = SolidColor(Color.Black))
        }.build()
    }

    val PhotoCamera: ImageVector by lazy {
        ImageVector.Builder(
            name = "PhotoCamera",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 960f, viewportHeight = 960f,
        ).group {
            addPath(addPathNodes("M480,700Q555,700 607.5,647.5Q660,595 660,520Q660,445 607.5,392.5Q555,340 480,340Q405,340 352.5,392.5Q300,445 300,520Q300,595 352.5,647.5Q405,700 480,700ZM480,620Q438,620 409,591Q380,562 380,520Q380,478 409,449Q438,420 480,420Q522,420 551,449Q580,478 580,520Q580,562 551,591Q522,620 480,620ZM160,840Q127,840 103.5,816.5Q80,793 80,760L80,280Q80,247 103.5,223.5Q127,200 160,200L286,200L336,146Q347,134 362.5,127Q378,120 395,120L565,120Q582,120 597.5,127Q613,134 624,146L674,200L800,200Q833,200 856.5,223.5Q880,247 880,280L880,760Q880,793 856.5,816.5Q833,840 800,840L160,840ZM160,760L800,760Q800,760 800,760Q800,760 800,760L800,280Q800,280 800,280Q800,280 800,280L638,280L565,200L395,200L322,280L160,280Q160,280 160,280Q160,280 160,280L160,760Q160,760 160,760Q160,760 160,760ZM480,520Q480,520 480,520Q480,520 480,520L480,520Q480,520 480,520Q480,520 480,520L480,520L480,520L480,520L480,520L480,520Q480,520 480,520Q480,520 480,520L480,520Q480,520 480,520Q480,520 480,520L480,520Z"), fill = SolidColor(Color.Black))
        }.build()
    }

}
