package com.studiopocket.app

import android.content.Context
import com.studiopocket.app.engine.AppEngine
import com.studiopocket.app.engine.StreamProfileStore

/** Objek proses tunggal; service dan activity memakai mesin yang sama. */
object AppContainer {
    @Volatile
    private var appEngine: AppEngine? = null

    @Volatile
    private var profileStore: StreamProfileStore? = null

    @Volatile
    private var chatController: ChatController? = null

    fun engine(context: Context): AppEngine =
        appEngine ?: synchronized(this) {
            appEngine ?: AppEngine(context.applicationContext).also { appEngine = it }
        }

    fun profiles(context: Context): StreamProfileStore =
        profileStore ?: synchronized(this) {
            profileStore ?: StreamProfileStore(context.applicationContext).also { profileStore = it }
        }

    fun chat(): ChatController =
        chatController ?: synchronized(this) {
            chatController ?: ChatController().also { chatController = it }
        }
}
