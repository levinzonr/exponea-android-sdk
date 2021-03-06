package com.exponea.sdk.manager

import com.exponea.sdk.Exponea
import com.exponea.sdk.models.NotificationData
import com.exponea.sdk.repository.FirebaseTokenRepository
import com.google.firebase.messaging.FirebaseMessagingService

class PushManagerImpl(
        private val firebaseTokenRepository: FirebaseTokenRepository
) : PushManager, FirebaseMessagingService() {

    override val fcmToken: String?
        get() = firebaseTokenRepository.get()

    override fun trackFcmToken(token: String?) {
        if (token != null) {
            firebaseTokenRepository.set(token)
        }
        if (fcmToken != null) {
            Exponea.trackPushToken(fcmToken!!)
        }
    }

    override fun trackDeliveredPush(data: NotificationData?) {
        Exponea.trackDeliveredPush(
                data = data
        )
    }

    override fun trackClickedPush(data: NotificationData?) {
        Exponea.trackClickedPush(
                data = data
        )
    }

    override fun onCreate() {
        super.onCreate()
        trackClickedPush()
    }

}