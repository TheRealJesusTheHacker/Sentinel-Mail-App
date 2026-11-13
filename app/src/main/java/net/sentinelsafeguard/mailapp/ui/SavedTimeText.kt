package net.sentinelsafeguard.mailapp.ui

import androidx.compose.runtime.Composable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import net.sentinelsafeguard.mailapp.R

/**
 * The "saved copy from …" time phrase (1.2.0), shared by the
 * offline banners: same-day saves read "today at 4:12 PM",
 * older ones carry their date. All wording lives in strings;
 * this only formats the clock.
 */
@Composable
fun savedAtLabel(savedAtMs: Long): String {
    if (savedAtMs <= 0L) return ""
    val then = Calendar.getInstance().apply { timeInMillis = savedAtMs }
    val now = Calendar.getInstance()
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(savedAtMs))
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        stringResource(R.string.saved_time_today, time)
    } else {
        val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(savedAtMs))
        stringResource(R.string.saved_time_date, date, time)
    }
}
