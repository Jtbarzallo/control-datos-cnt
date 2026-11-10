package ec.jat.controldatos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (DataUsage.tienePermiso(context)) MonitorService.iniciar(context)
    }
}
