package com.exponea.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkManager
import com.exponea.sdk.exceptions.InvalidConfigurationException
import com.exponea.sdk.manager.SessionManagerImpl
import com.exponea.sdk.models.*
import com.exponea.sdk.models.FlushMode.*
import com.exponea.sdk.repository.ExponeaConfigRepository
import com.exponea.sdk.util.Logger
import com.exponea.sdk.util.addAppStateCallbacks
import com.exponea.sdk.util.currentTimeSeconds
import com.exponea.sdk.util.toDate
import com.google.firebase.FirebaseApp
import io.paperdb.Paper
import java.util.*
import java.util.concurrent.TimeUnit

@SuppressLint("StaticFieldLeak")
object Exponea {
    private lateinit var context: Context
    private lateinit var configuration: ExponeaConfiguration
    lateinit var component: ExponeaComponent

    /**
     * Defines which mode the library should flush out events
     */

    var flushMode: FlushMode = IMMEDIATE
        set(value) {
            field = value
            onFlushModeChanged()
        }

    /**
     * Defines the period at which the library should flush events
     */

    var flushPeriod: FlushPeriod = FlushPeriod(60, TimeUnit.MINUTES)
        set(value) {
            field = value
            onFlushPeriodChanged()
        }

    /**
     * Defines session timeout considered for app usage
     */

    var sessionTimeout: Double
        get() = configuration.sessionTimeout
        set(value) {
            configuration.sessionTimeout = value
        }

    /**
     * Defines if automatic session tracking is enabled
     */
    var isAutomaticSessionTracking: Boolean
        get() = configuration.automaticSessionTracking
        set(value) {
            configuration.automaticSessionTracking = value
            startSessionTracking(value)
        }

    /**
     * Check if our library has been properly initialized
     */

    var isInitialized: Boolean = false
        get() {
            return this::configuration.isInitialized
        }

    /**
     * Check if the push notification listener is set to automatically
     */

    var isAutoPushNotification: Boolean
        get() = configuration.automaticPushNotification
        set(value) {
            configuration.automaticPushNotification = value
        }

    /**
     * Whenever a notification with extra values is received, this callback is called
     * with the values as map
     *
     * If a previous data was received and no listener was attached to the callback,
     * that data i'll be dispatched as soon as a listener is attached
     */
    var notificationDataCallback: ((data: Map<String, String>) -> Unit)? = null
        set(value) {
            if (!isInitialized) {
                Logger.w(this, "SDK not initialized")
                return
            }
            field = value
            val storeData = component.pushNotificationRepository.getExtraData()
            if (storeData != null) {
                field?.invoke(storeData)
                component.pushNotificationRepository.clearExtraData()
            }
        }

    /**
     * Set which level the debugger should output log messages
     */
    var loggerLevel: Logger.Level
        get () = Logger.level
        set(value) {
            Logger.level = value
        }

    @Deprecated(
            "Use initFromFile(context: Context)",
            replaceWith = ReplaceWith("Exponea.initFromFile(context)")
    )
    @Throws(InvalidConfigurationException::class)
    fun init(context: Context, configFile: String) {
        // Try to parse our file
        val configuration = Exponea.component.fileManager.getConfigurationFromFile(configFile)

        // If our file isn't null then try initiating normally
        if (configuration != null) {
            init(context, configuration)
        } else {
            throw InvalidConfigurationException()
        }
    }

    /**
     * Use this method using a file as configuration. The SDK searches for a file called
     * "exponea_configuration.json" that must be inside the "assets" folder of your application
     */
    @Throws(InvalidConfigurationException::class)
    fun initFromFile(context: Context) {

        Paper.init(context)
        component = ExponeaComponent(ExponeaConfiguration(), context)

        // Try to parse our file
        val configuration = Exponea.component.fileManager.getConfigurationFromDefaultFile(context)

        // If our file isn't null then try initiating normally
        if (configuration != null) {
            init(context, configuration)
        } else {
            throw InvalidConfigurationException()
        }
    }

    fun init(context: Context, configuration: ExponeaConfiguration) {
        Logger.i(this, "Init")

        if (Looper.myLooper() == null)
            Looper.prepare()

        Paper.init(context)

        this.context = context
        this.configuration = configuration

        if (!isInitialized) {
            Logger.e(this, "Exponea SDK was not initialized properly!")
            return
        }
        ExponeaConfigRepository.set(context, configuration)
        FirebaseApp.initializeApp(context)
        initializeSdk()
    }

    /**
     * Update the informed properties to a specific customer.
     * All properties will be stored into database until it will be
     * flushed (send it to api).
     */

    fun identifyCustomer(customerIds: CustomerIds, properties: PropertiesList) {
        component.customerIdsRepository.set(customerIds)
        track(
            properties = properties.properties,
            type = EventType.TRACK_CUSTOMER
        )
    }

    /**
     * Track customer event add new events to a specific customer.
     * All events will be stored into database until it will be
     * flushed (send it to api).
     */

    fun trackEvent(
        properties: PropertiesList,
        timestamp: Double? = currentTimeSeconds(),
        eventType: String?
    ) {

        track(
            properties = properties.properties,
            timestamp = timestamp,
            eventType = eventType,
            type = EventType.TRACK_EVENT
        )
    }

    /**
     * Manually push all events to Exponea
     */

    fun flushData() {
        if (component.flushManager.isRunning) {
            Logger.w(this, "Cannot flush, Job service is already in progress")
            return
        }

        component.flushManager.flushData()
    }

    /**
     * Fetches customer attributes
     */
    @Deprecated("Basic authorization was deprecated and fetching data will not be available in the future.")
    fun fetchCustomerAttributes(
        customerAttributes: CustomerAttributes,
        onSuccess: (Result<List<CustomerAttributeModel>>) -> Unit,
        onFailure: (Result<FetchError>) -> Unit
    ) {
        val customer = Exponea.component.customerIdsRepository.get()
        component.fetchManager.fetchCustomerAttributes(
            projectToken = configuration.projectToken,
            attributes = customerAttributes.apply { customerIds = customer },
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    /**
     * Fetches banners web representation
     * @param onSuccess - success callback, when data is ready
     * @param onFailure - failure callback, in case of errors
     */
    fun getPersonalizationWebLayer(
        onSuccess: (Result<ArrayList<BannerResult>>) -> Unit,
        onFailure: (Result<FetchError>) -> Unit
    ) {
        // TODO map banners id's
        val customerIds = Exponea.component.customerIdsRepository.get()
        Exponea.component.personalizationManager.getWebLayer(
            customerIds = customerIds,
            projectToken = Exponea.configuration.projectToken,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    /**
     * Manually tracks session start
     * @param timestamp - determines session start time ( in seconds )
     */
    fun trackSessionStart(timestamp: Double = currentTimeSeconds()) {
        if (isAutomaticSessionTracking) {
            Logger.w(
                Exponea.component.sessionManager,
                "Can't manually track session, since automatic tracking is on "
            )
            return
        }
        component.sessionManager.trackSessionStart(timestamp)
    }

    /**
     * Manually tracks session end
     * @param timestamp - determines session end time ( in seconds )
     */
    fun trackSessionEnd(timestamp: Double = currentTimeSeconds()) {

        if (isAutomaticSessionTracking) {
            Logger.w(
                Exponea.component.sessionManager,
                "Can't manually track session, since automatic tracking is on "
            )
            return
        }

        component.sessionManager.trackSessionEnd(timestamp)
    }

    /**
     * Fetch events for a specific customer.
     * @param customerEvents - Event from a specific customer to be tracked.
     * @param onFailure - Method will be called if there was an error.
     * @param onSuccess - this method will be called when data is ready.
     */
    @Deprecated("Basic authorization was deprecated and fetching data will not be available in the future.")
    fun fetchCustomerEvents(
        customerEvents: FetchEventsRequest,
        onFailure: (Result<FetchError>) -> Unit,
        onSuccess: (Result<ArrayList<CustomerEvent>>) -> Unit
    ) {
        val customer = Exponea.component.customerIdsRepository.get()
        customerEvents.customerIds = customer

        component.fetchManager.fetchCustomerEvents(
            projectToken = configuration.projectToken,
            customerEvents = customerEvents,
            onFailure = onFailure,
            onSuccess = onSuccess
        )
    }

    /**
     * Fetch recommendations for a specific customer.
     * @param customerRecommendation - Recommendation for the customer.
     * @param onFailure - Method will be called if there was an error.
     * @param onSuccess - this method will be called when data is ready.
     */
    fun fetchRecommendation(
        customerRecommendation: CustomerRecommendation,
        onSuccess: (Result<List<CustomerAttributeModel>>) -> Unit,
        onFailure: (Result<FetchError>) -> Unit
    ) {
        val customer = Exponea.component.customerIdsRepository.get()
        component.fetchManager.fetchCustomerAttributes(
            projectToken = configuration.projectToken,
            attributes = CustomerAttributes(
                customer,
                mutableListOf(customerRecommendation.toHashMap())
            ),
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    /**
     * Manually track FCM Token to Exponea API.
     */

    fun trackPushToken(fcmToken: String) {
        component.firebaseTokenRepository.set(fcmToken)
        val properties = PropertiesList(hashMapOf("google_push_notification_id" to fcmToken))
        track(
            eventType = Constants.EventTypes.push,
            properties = properties.properties,
            type = EventType.PUSH_TOKEN
        )
    }

    /**
     * Manually track delivered push notification to Exponea API.
     */

    fun trackDeliveredPush(
        data: NotificationData? = null,
        timestamp: Double? = currentTimeSeconds()
    ) {
        val properties = PropertiesList(
            hashMapOf(
                Pair("action_type", "notification"),
                Pair("status", "delivered")
            )
        )
        Logger.d(this, "Push dev: ${timestamp.toString()}")
        Logger.d(this, "Push dev time ${Date()}")
        Logger.d(this, "Push dev time ${timestamp?.toDate()}")
        data?.let {
            properties["campaign_id"] = it.campaignId
            properties["campaign_name"] = it.campaignName
            properties["action_id"] = it.actionId
        }
        track(
            eventType = Constants.EventTypes.push,
            properties = properties.properties,
            type = EventType.PUSH_DELIVERED,
            timestamp = timestamp
        )
    }

    /**
     * Manually track clicked push notification to Exponea API.
     */

    fun trackClickedPush(
        data: NotificationData? = null,
        timestamp: Double? = currentTimeSeconds()
    ) {
        val properties = PropertiesList(
            hashMapOf(
                "action_type" to "notification",
                "status" to "clicked"
            )
        )

        data?.let {
            properties["campaign_id"] = data.campaignId
            properties["campaign_name"] = data.campaignName
            properties["action_id"] = data.actionId
        }
        track(
            eventType = Constants.EventTypes.push,
            properties = properties.properties,
            type = EventType.PUSH_OPENED,
            timestamp = timestamp
        )
    }

    /**
     * Opens a WebView showing the personalized page with the
     * banners for a specific customer.
     */

    fun showBanners(customerIds: CustomerIds) {
        Exponea.component.personalizationManager.showBanner(
            projectToken = Exponea.configuration.projectToken,
            customerIds = customerIds
        )
    }

    /**
     * Tracks payment manually
     * @param purchasedItem - represents payment details.
     * @param timestamp - Time in timestamp format where the event was created. ( in seconds )
     */

    fun trackPaymentEvent(
        timestamp: Double = currentTimeSeconds(),
        purchasedItem: PurchasedItem
    ) {

        track(
            eventType = Constants.EventTypes.payment,
            timestamp = timestamp,
            properties = purchasedItem.toHashMap(),
            type = EventType.PAYMENT
        )
    }

    // Private Helpers

    /**
     * Initialize and start all services and automatic configurations.
     */

    private fun initializeSdk() {

        // Start Network Manager
        this.component = ExponeaComponent(this.configuration, context)

        // WorkManager
        WorkManager.initialize(context, Configuration.Builder().build())
        // Alarm Manager Starter
        startService()

        // Track Install Event
        trackInstallEvent()

        // Track In-App purchase
        trackInAppPurchase()

        // Track Firebase Token
        trackFirebaseToken()

        // Initialize session observer
        component.preferences
                .setBoolean(
                        SessionManagerImpl.PREF_SESSION_AUTO_TRACK,
                        configuration.automaticSessionTracking
                )
        startSessionTracking(configuration.automaticSessionTracking)

        context.addAppStateCallbacks(
            onOpen = {
                Logger.i(this, "App is opened")
                if (flushMode == APP_CLOSE) {
                    flushMode = PERIOD
                }
            },
            onClosed = {
                Logger.i(this, "App is closed")
                if (flushMode == PERIOD) {
                    flushMode = APP_CLOSE
                    // Flush data when app is closing for flush mode periodic.
                    Exponea.component.flushManager.flushData()
                }
            }
        )
    }

    /**
     * Start the service when the flush period was changed.
     */

    private fun onFlushPeriodChanged() {
        Logger.d(this, "onFlushPeriodChanged: $flushPeriod")
        startService()
    }

    /**
     * Start or stop the service when the flush mode was changed.
     */

    private fun onFlushModeChanged() {
        Logger.d(this, "onFlushModeChanged: $flushMode")
        when (flushMode) {
            PERIOD -> startService()
            APP_CLOSE -> stopService()
            MANUAL -> stopService()
            IMMEDIATE -> stopService()
        }
    }

    /**
     * Starts the service.
     */

    private fun startService() {
        Logger.d(this, "startService")

        if (flushMode == MANUAL || flushMode == IMMEDIATE) {
            Logger.w(this, "Flush mode manual set -> Skipping job service")
            return
        }
        component.serviceManager.start()
    }

    /**
     * Stops the service.
     */

    private fun stopService() {
        Logger.d(this, "stopService")
        component.serviceManager.stop()
    }

    /**
     * Initializes session listener
     * @param enableSessionTracking - determines sdk tracking session's state
     */

    private fun startSessionTracking(enableSessionTracking: Boolean) {
        if (enableSessionTracking) {
            component.sessionManager.startSessionListener()
        } else {
            component.sessionManager.stopSessionListener()
        }
    }

    /**
     * Initializes payments listener
     */

    private fun trackInAppPurchase() {
        if (this.configuration.automaticPaymentTracking) {
            // Add the observers when the automatic session tracking is true.
            this.component.iapManager.configure()
            this.component.iapManager.startObservingPayments()
        } else {
            // Remove the observers when the automatic session tracking is false.
            this.component.iapManager.stopObservingPayments()
        }
    }

    /**
     * Send the firebase token
     */
    private fun trackFirebaseToken() {
        if (Exponea.isAutoPushNotification) {
            this.component.pushManager.trackFcmToken()
        }
    }

    /**
     * Send a tracking event to Exponea
     */

    internal fun track(
        eventType: String? = null,
        timestamp: Double? = currentTimeSeconds(),
        properties: HashMap<String, Any> = hashMapOf(),
        type: EventType
    ) {

        if (!isInitialized) {
            Logger.e(this, "Exponea SDK was not initialized properly!")
            return
        }


        val customerIds = component.customerIdsRepository.get()

        val event = ExportedEventType(
            type = eventType,
            timestamp = timestamp,
            customerIds = customerIds.toHashMap(),
            properties = properties
        )

        component.eventManager.addEventToQueue(event, type)
    }

    /**
     * Installation event is fired only once for the whole lifetime of the app on one
     * device when the app is launched for the first time.
     */

    internal fun trackInstallEvent(
        campaign: String? = null,
        campaignId: String? = null,
        link: String? = null
    ) {

        if (component.deviceInitiatedRepository.get()) {
            return
        }

        val device = DeviceProperties(
            campaign = campaign,
            campaignId = campaignId,
            link = link,
            deviceType = component.deviceManager.getDeviceType()
        )

        track(
            eventType = Constants.EventTypes.installation,
            properties = device.toHashMap(),
            type = EventType.INSTALL
        )

        component.deviceInitiatedRepository.set(true)
    }

    fun anonymize() {
        if (!isInitialized) {
            Logger.e(this, "Exponea SDK was not initialized properly!")
            return
        }

        val firebaseToken = component.firebaseTokenRepository.get()

        component.pushManager.trackFcmToken(" ")
        component.anonymizeManager.anonymize()
        component.sessionManager.trackSessionStart(currentTimeSeconds())
        component.pushManager.trackFcmToken(firebaseToken)

    }
}