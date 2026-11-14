package app.iaagentauto

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kauf der Zusatzfunktion „Home Assistant“ über Google Play (Einmalkauf).
 *
 * In der Google Play Console muss ein In-App-Produkt (Einmalkauf) mit der ID
 * [PRODUCT_HOME_ASSISTANT] angelegt werden – dort wird auch der Preis (3,00 €) festgelegt.
 *
 * Testversionen (Debug-Build, nicht aus Play installiert) sind automatisch freigeschaltet.
 */
object Billing {
    const val PRODUCT_HOME_ASSISTANT = "home_assistant_addon"

    sealed interface State {
        data object Loading : State
        data object Unlocked : State
        /** Kaufbar; [price] ist der von Google gelieferte Preis, z. B. „3,00 €“. */
        data class Available(val price: String) : State
        data object Pending : State
        data class Unavailable(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private lateinit var appContext: Context

    /** Ist die Zusatzfunktion nutzbar? */
    fun isUnlocked(c: Context): Boolean = BuildConfig.DEBUG || Settings.haUnlocked(c)

    fun init(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.DEBUG) { _state.value = State.Unlocked; return }
        if (Settings.haUnlocked(appContext)) _state.value = State.Unlocked
        connect()
    }

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handlePurchase(it) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> updateAvailable()
            else -> updateAvailable()
        }
    }

    private fun connect() {
        val c = client ?: BillingClient.newBuilder(appContext)
            .setListener(purchasesListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
            .also { client = it }
        if (c.isReady) { refresh(); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refresh()
                } else if (!Settings.haUnlocked(appContext)) {
                    _state.value = State.Unavailable("Google Play ist nicht erreichbar (${result.debugMessage}).")
                }
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    /** Käufe und Preis neu laden – auch zum „Kauf wiederherstellen“. */
    fun refresh() {
        if (BuildConfig.DEBUG) return
        val c = client ?: return connect()
        if (!c.isReady) return connect()
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val own = purchases.filter { PRODUCT_HOME_ASSISTANT in it.products }
            own.forEach { handlePurchase(it) }
            if (own.none { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
                Settings.setHaUnlocked(appContext, false)
                if (own.any { it.purchaseState == Purchase.PurchaseState.PENDING }) _state.value = State.Pending
                else loadDetails()
            }
        }
    }

    private fun loadDetails() {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_HOME_ASSISTANT)
                    .setProductType(ProductType.INAPP)
                    .build()
            ))
            .build()
        c.queryProductDetailsAsync(params) { result, productResult ->
            details = productResult.productDetailsList.firstOrNull()
            if (result.responseCode == BillingClient.BillingResponseCode.OK && details != null) {
                updateAvailable()
            } else {
                _state.value = State.Unavailable("Die Zusatzfunktion ist im Play Store gerade nicht verfügbar.")
            }
        }
    }

    private fun updateAvailable() {
        if (Settings.haUnlocked(appContext)) { _state.value = State.Unlocked; return }
        val price = details?.oneTimePurchaseOfferDetails?.formattedPrice
        _state.value = if (price != null) State.Available(price) else State.Loading
        if (price == null) loadDetails()
    }

    /** Startet den Kaufdialog von Google Play. */
    fun buy(activity: Activity): Boolean {
        val c = client ?: return false
        val d = details ?: return false
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
            ))
            .build()
        return c.launchBillingFlow(activity, flow).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun handlePurchase(p: Purchase) {
        if (PRODUCT_HOME_ASSISTANT !in p.products) return
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                Settings.setHaUnlocked(appContext, true)
                _state.value = State.Unlocked
                // Käufe müssen innerhalb von 3 Tagen bestätigt werden, sonst erstattet Google sie.
                if (!p.isAcknowledged) {
                    client?.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
                    ) {}
                }
            }
            Purchase.PurchaseState.PENDING -> _state.value = State.Pending
            else -> Unit
        }
    }
}
