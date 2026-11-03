package com.bydmate.app.ui.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextMuted

/** Extra height the arrival row adds to the widget (row + divider + spacing). */
internal const val ARRIVAL_CHIP_EXTRA_HEIGHT_DP = 28

/** Same palette as the widget border, so the chip speaks the widget's status language. */
internal fun arrivalColor(level: ArrivalLevel): Color = when (level) {
    ArrivalLevel.OK -> AccentGreen
    ArrivalLevel.LOW -> SocYellow
    ArrivalLevel.SHORT -> SocRed
}

/** One compact line at the bottom of the widget while a route is guided: the reserve at the destination. */
@Composable
internal fun RowArrivalChip(reserve: ArrivalReserve) {
    Row(
        modifier = Modifier.fillMaxWidth().height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Flag,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(arrivalReserveTextRes(reserve), reserve.km),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = arrivalColor(reserve.level),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
