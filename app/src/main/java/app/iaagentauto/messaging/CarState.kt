package app.iaagentauto.messaging

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Fragt bei Android Auto nach, ob das Handy gerade mit einem Auto verbunden ist. */
object CarState {
    suspend fun isInCar(ctx: Context): Boolean =
        connectionType(ctx) == CarConnection.CONNECTION_TYPE_PROJECTION

    private suspend fun connectionType(ctx: Context): Int = withContext(Dispatchers.Main) {
        val live = CarConnection(ctx).type
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val observer = object : Observer<Int> {
                    override fun onChanged(value: Int) {
                        live.removeObserver(this)
                        if (cont.isActive) cont.resume(value)
                    }
                }
                live.observeForever(observer)
                cont.invokeOnCancellation { live.removeObserver(observer) }
            }
        } ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED
    }
}
