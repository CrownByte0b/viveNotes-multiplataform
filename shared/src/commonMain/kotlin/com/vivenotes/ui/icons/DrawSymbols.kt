package com.vivenotes.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Rounded used by the Android drawing tools. */
object DrawSymbols {
    val Select: ImageVector by lazy {
        ImageVector.Builder("Select", 24.dp, 24.dp, 960f, 960f).apply {
            addPath(addPathNodes(
                "M320,550L399,440L569,440L320,244L320,550ZM606,855Q583,866 560,857.5Q537,849 526,826L406,568L313,698Q296,7" +
                "22 268,713Q240,704 240,675L240,162Q240,137 262.5,126Q285,115 305,131L709,449Q732,466 722.5,493Q713,520 68" +
                "4,520L516,520L635,775Q646,798 637.5,821Q629,844 606,855Z"
            ), fill = SolidColor(Color.Black))
        }.build()
    }

    val Lasso: ImageVector by lazy {
        ImageVector.Builder("Lasso", 24.dp, 24.dp, 960f, 960f).apply {
            group(translationY = 960f) {
                addPath(addPathNodes(
                    "M479-82q-35 0-69.5-5.5T343-106q-12-4-19.5-14t-7.5-24q0-17 11.5-28.5T356-184l14 2q27 9 53.5 14.5T479-162q1" +
                    "7 0 28.5 11.5T519-122q0 17-11.5 28.5T479-82Zm160-40q-17 0-28.5-11.5T599-162v-160q0-17 11.5-28.5T639-362h1" +
                    "60q17 0 28.5 11.5T839-322q0 17-11.5 28.5T799-282h-64l84 84q11 11 11.5 27.5T819-142q-11 11-28 11t-28-11l-8" +
                    "4-84v64q0 17-11.5 28.5T639-122Zm-414-66q-8 0-15.5-3.5T197-200q-33-32-57-70t-39-81q-2-4-2-14 0-17 11.5-28." +
                    "5T139-405q14 0 24 8t14 20q12 35 31 65t45 56q5 5 8.5 12.5T265-228q0 17-11.5 28.5T225-188Zm614-254q-17 0-28" +
                    ".5-11.5T799-482q0-29-5.5-55.5T779-591q-2-4-2-14 0-17 11.5-28.5T817-645q14 0 24 7.5t14 19.5q13 32 18.5 66." +
                    "5T879-482q0 17-11.5 28.5T839-442Zm-718-38q-17 0-28.5-11.5T81-520v-7q6-45 21-88t39-81q5-8 14-13.5t20-5.5q1" +
                    "7 0 28.5 11.5T215-675q0 6-2 12t-5 11q-20 31-31 65.5T161-516q-2 15-13 25.5T121-480Zm612-216q-8 0-15.5-3.5T" +
                    "705-708q-26-26-56-45t-65-31q-12-4-20-14t-8-24q0-17 11.5-28.5T596-862l14 2q43 15 81 39t70 57q5 5 8.5 12.5T" +
                    "773-736q0 17-11.5 28.5T733-696Zm-447-50q-17 0-28.5-11.5T246-786q0-11 5.5-20t13.5-14q38-24 81-39t88-21h7q1" +
                    "7 0 28.5 11.5T481-840q0 16-10.5 27T445-800q-36 5-70.5 16T309-753q-5 3-11 5t-12 2Z"
                ), fill = SolidColor(Color.Black))
            }
        }.build()
    }

    val Pen: ImageVector by lazy {
        ImageVector.Builder("Pen", 24.dp, 24.dp, 960f, 960f).apply {
            group(translationY = 960f) {
                addPath(addPathNodes(
                    "M160-120l22-65q8-25 29-40t47-15h444q26,0 47,15t29,40l22,65H160Zm80-200 200-520h80l200,520H240Zm116-80h248" +
                    "L480-721 356-400Zm0,0h248-248Z"
                ), fill = SolidColor(Color.Black))
            }
        }.build()
    }

    val Highlighter: ImageVector by lazy {
        ImageVector.Builder("Highlighter", 24.dp, 24.dp, 960f, 960f).apply {
            group(translationY = 960f) {
                addPath(addPathNodes(
                    "M320-320q-17 0-28.5-11.5T280-360v-400q0-33 23.5-56.5T360-840q9 0 18 2t17 6l240 119q20 10 32.5 29.5T680-64" +
                    "1v281q0 17-11.5 28.5T640-320H320Zm40-80h240v-241L360-760v360Zm0 0h240-240ZM215-120q-20 0-32.5-16.5T177-17" +
                    "3l5-12q8-25 29-40t47-15h444q26 0 47 15t29 40l5 12q7 20-5.5 36.5T745-120H215Z"
                ), fill = SolidColor(Color.Black))
            }
        }.build()
    }

    val Eraser: ImageVector by lazy {
        ImageVector.Builder("Eraser", 24.dp, 24.dp, 960f, 960f).apply {
            group(translationY = 960f) {
                addPath(addPathNodes(
                    "m528-546-93-93-26.5-26.5L382-692q-19-19-8.5-43.5T411-760h329q25 0 42.5 17.5T800-700q0 25-17.5 42.5T740-64" +
                    "0H568l-40 94ZM764-84 460-388l-64 151q-7 17-22.5 27T340-200q-32 0-50-27t-5-57l83-196L84-764q-11-11-11-28t1" +
                    "1-28q11-11 28-11t28 11l680 680q11 11 11 28t-11 28q-11 11-28 11t-28-11Z"
                ), fill = SolidColor(Color.Black))
            }
        }.build()
    }

    val Shape: ImageVector by lazy {
        ImageVector.Builder("Shape", 24.dp, 24.dp, 960f, 960f).apply {
            group(translationY = 960f) {
                addPath(addPathNodes(
                    "m297-581 149-243q6-10 15-14.5t19-4.5q10 0 19 4.5t15 14.5l149 243q6 10 6 21t-5 20q-5 9-14 14.5t-21 5.5H331" +
                    "q-12 0-21-5.5T296-540q-5-9-5-20t6-21ZM700-80q-75 0-127.5-52.5T520-260q0-75 52.5-127.5T700-440q75 0 127.5 " +
                    "52.5T880-260q0 75-52.5 127.5T700-80Zm-580-60v-240q0-17 11.5-28.5T160-420h240q17 0 28.5 11.5T440-380v240q0" +
                    " 17-11.5 28.5T400-100H160q-17 0-28.5-11.5T120-140Zm580-20q42 0 71-29t29-71q0-42-29-71t-71-29q-42 0-71 29t" +
                    "-29 71q0 42 29 71t71 29Zm-500-20h160v-160H200v160Zm202-420h156l-78-126-78 126Zm78 0ZM360-340Zm340 80Z"
                ), fill = SolidColor(Color.Black))
            }
        }.build()
    }

    val Ruler: ImageVector by lazy {
        ImageVector.Builder("Ruler", 24.dp, 24.dp, 960f, 960f).apply {
            addPath(addPathNodes(
                "M160,720Q127,720 103.5,696.5Q80,673 80,640L80,320Q80,287 103.5,263.5Q127,240 160,240L800,240Q833,240 856." +
                "5,263.5Q880,287 880,320L880,640Q880,673 856.5,696.5Q833,720 800,720L160,720ZM160,640L800,640Q800,640 800," +
                "640Q800,640 800,640L800,320Q800,320 800,320Q800,320 800,320L680,320L680,440Q680,457 668.5,468.5Q657,480 6" +
                "40,480Q623,480 611.5,468.5Q600,457 600,440L600,320L520,320L520,440Q520,457 508.5,468.5Q497,480 480,480Q46" +
                "3,480 451.5,468.5Q440,457 440,440L440,320L360,320L360,440Q360,457 348.5,468.5Q337,480 320,480Q303,480 291" +
                ".5,468.5Q280,457 280,440L280,320L160,320Q160,320 160,320Q160,320 160,320L160,640Q160,640 160,640Q160,640 " +
                "160,640ZM320,480L320,480L320,480L320,480ZM480,480L480,480L480,480L480,480ZM640,480L640,480L640,480L640,48" +
                "0ZM480,480Q480,480 480,480Q480,480 480,480L480,480Q480,480 480,480Q480,480 480,480L480,480L480,480L480,48" +
                "0L480,480L480,480L480,480L480,480L480,480L480,480L480,480L480,480L480,480L480,480Q480,480 480,480Q480,480" +
                " 480,480L480,480Q480,480 480,480Q480,480 480,480Z"
            ), fill = SolidColor(Color.Black))
        }.build()
    }

}
