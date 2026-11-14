package app.iaagentauto.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import app.iaagentauto.Settings

class ClaudeCarAppService : CarAppService() {
    // Für die private Nutzung (Sideload) alle Hosts erlauben. Für eine Veröffentlichung im
    // Play Store sollte hier eine Allowlist der Android-Auto-Hosts verwendet werden.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            Settings.setLastCarSession(carContext, System.currentTimeMillis())
            return ChatScreen(carContext)
        }
    }
}
