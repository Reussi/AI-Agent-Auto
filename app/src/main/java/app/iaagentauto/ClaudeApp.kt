package app.iaagentauto

import android.app.Application
import app.iaagentauto.messaging.ClaudeNotifier

class ClaudeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ClaudeNotifier.createChannels(this)
        Conversation.init(this)
        Billing.init(this)
    }
}
