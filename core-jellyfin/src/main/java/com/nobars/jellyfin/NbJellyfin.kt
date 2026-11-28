package com.nobars.jellyfin

import android.content.Context
import com.github.damontecres.wholphin.nobars.data.ServerAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import java.util.UUID

/**
 * NoBars: the connection to Jellyfin for the phone app. One per app ([get]). After [restore] or a sign-in, [api] carries
 * the server address and the login, and the shared data layer (core-data's ServerAccess) is pointed at the same server.
 */
class NbJellyfin private constructor(
    context: Context,
    clientName: String,
    clientVersion: String,
) {
    data class Session(
        val serverUrl: String,
        val serverName: String,
        val userId: UUID,
        val userName: String,
        val token: String,
    )

    data class Server(
        val address: String,
        val name: String,
        val version: String? = null,
    )

    private val app = context.applicationContext
    val jellyfin: Jellyfin =
        createJellyfin {
            this.context = app
            clientInfo = ClientInfo(name = clientName, version = clientVersion)
            deviceInfo = androidDevice(app)
            // the SDK's HTTP and websocket client (OkHttp, as in the TV app)
            val http = CoroutineContextApiClientFactory(OkHttpFactory(OkHttpClient()))
            apiClientFactory = http
            socketConnectionFactory = http
        }
    val api: ApiClient = jellyfin.createApi()
    private val store = SecureStore(app)
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    val library by lazy { Library(this) }
    val playback by lazy { Playback(this) }

    /** Signs in with the remembered login, if there is one. */
    fun restore(): Boolean {
        val s = store.load() ?: return false
        use(s)
        return true
    }

    private fun use(s: Session) {
        api.update(baseUrl = s.serverUrl, accessToken = s.token)
        ServerAccess.baseUrl = s.serverUrl
        ServerAccess.token = s.token
        ServerAccess.userId = s.userId
        _session.value = s
    }

    /** Why the last [checkServer] found nothing (shown on the server screen). */
    @Volatile
    var lastError: String? = null
        private set

    fun lastServer(): String? = store.lastServer()

    /** Jellyfin servers answering on this Wi-Fi (a few seconds). */
    fun localServers(): Flow<Server> = jellyfin.discovery.discoverLocalServers().map { Server(it.address, it.name) }

    /** Checks an address typed in (or found): the best working form of it, with the server's name. */
    suspend fun checkServer(input: String): Server? {
        lastError = null
        val found =
            try {
                jellyfin.discovery.getRecommendedServers(input).sortedBy { it.score }
            } catch (e: Exception) {
                Timber.w(e, "NoBars: server check failed for %s", input)
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                emptyList()
            }
        found.forEach { Timber.i("NoBars: %s -> %s %s", it.address, it.score, it.systemInfo.exceptionOrNull()?.toString().orEmpty()) }
        val best = found.firstOrNull { it.score != RecommendedServerInfoScore.BAD }
        if (best == null) {
            if (lastError == null) {
                lastError = found.firstNotNullOfOrNull { it.systemInfo.exceptionOrNull() }?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: if (found.isEmpty()) "no address to try" else "the server's version isn't supported"
            }
            return null
        }
        val info = best.systemInfo.getOrNull()
        return Server(best.address, info?.serverName ?: "Jellyfin", info?.version)
    }

    /** Users the server shows on its sign-in screen. */
    suspend fun publicUsers(server: Server): List<UserDto> {
        api.update(baseUrl = server.address, accessToken = null)
        return api.userApi.getPublicUsers().content
    }

    suspend fun signIn(
        server: Server,
        userName: String,
        password: String,
    ): Session {
        api.update(baseUrl = server.address, accessToken = null)
        val r = api.userApi.authenticateUserByName(AuthenticateUserByName(username = userName, pw = password)).content
        return finish(server, r)
    }

    /** Quick Connect: shows a code to approve in Jellyfin (dashboard or another signed-in app). */
    suspend fun quickConnectStart(server: Server): QuickConnectResult {
        api.update(baseUrl = server.address, accessToken = null)
        return api.quickConnectApi.initiateQuickConnect().content
    }

    suspend fun quickConnectApproved(secret: String): Boolean = api.quickConnectApi.getQuickConnectState(secret).content.authenticated

    suspend fun quickConnectFinish(
        server: Server,
        secret: String,
    ): Session = finish(server, api.userApi.authenticateWithQuickConnect(QuickConnectDto(secret = secret)).content)

    private fun finish(
        server: Server,
        r: AuthenticationResult,
    ): Session {
        val user = requireNotNull(r.user) { "The server did not return the user" }
        val s =
            Session(
                serverUrl = server.address,
                serverName = server.name,
                userId = user.id,
                userName = user.name.orEmpty(),
                token = requireNotNull(r.accessToken) { "The server did not return a login" },
            )
        store.save(s)
        store.rememberServer(server.address)
        use(s)
        return s
    }

    fun signOut() {
        store.clear()
        _session.value?.serverUrl?.let { store.rememberServer(it) }
        api.update(accessToken = null)
        ServerAccess.token = null
        ServerAccess.userId = null
        _session.value = null
    }

    /** Sends a text (a playback report or the app's log) to the server's log folder, where the Mac reads it. */
    suspend fun uploadLog(text: String): Boolean =
        try {
            api.clientLogApi.logFile(text)
            true
        } catch (e: Exception) {
            Timber.w(e, "NoBars: log upload failed")
            false
        }

    companion object {
        @Volatile
        private var instance: NbJellyfin? = null

        fun get(
            context: Context,
            clientName: String,
            clientVersion: String,
        ): NbJellyfin = instance ?: synchronized(this) { instance ?: NbJellyfin(context, clientName, clientVersion).also { instance = it } }

        /** The instance created at app start. */
        val current: NbJellyfin get() = checkNotNull(instance) { "NbJellyfin.get() not called yet" }
    }
}
