package br.etc.victor.myhealthbridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import br.etc.victor.myhealthbridge.maintenance.IncidentIdentity
import br.etc.victor.myhealthbridge.maintenance.MaintenanceIncident
import br.etc.victor.myhealthbridge.maintenance.MaintenanceNotifier
import br.etc.victor.myhealthbridge.maintenance.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The "Manutenção necessária" channel.
 *
 * A notification is posted under the incident's own identity as its tag, so meeting the same
 * condition on the next hourly synchronization replaces what is on screen with a fresh count instead
 * of adding another notification beside it.
 *
 * Nothing here is read from a Health Record: the notification says which defect was met and how often,
 * and the diagnostics screen is where the rest of the incident is.
 */
class AndroidMaintenanceNotifier(private val context: Context) : MaintenanceNotifier {

    private val notifications = NotificationManagerCompat.from(context)

    init {
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.maintenance_channel_name))
                .setDescription(context.getString(R.string.maintenance_channel_description))
                .build(),
        )
    }

    override fun post(incident: MaintenanceIncident) {
        // The Data Owner may have turned the channel off, which is an answer: the incident stays
        // recorded and the diagnostics screen keeps showing it.
        if (!notifications.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(incident.identity.code.title))
            .setContentText(
                context.getString(
                    R.string.maintenance_notification_summary,
                    incident.occurrences,
                    lastSeenFormat.format(incident.lastSeenAt),
                ),
            )
            .setContentIntent(opensDiagnostics())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        notifications.notify(incident.identity.key, ID, notification)
    }

    override fun withdraw(identity: IncidentIdentity) = notifications.cancel(identity.key, ID)

    private fun opensDiagnostics(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainTab.DIAGNOSTICS_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val CHANNEL = "maintenance-required"

        /** The tag tells the notifications apart; the identifier only has to be the same for all. */
        const val ID = 1
    }
}

private val lastSeenFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
