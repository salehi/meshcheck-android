package io.meshcheck.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import meshcheck.agent.v1.Capabilities
import meshcheck.agent.v1.ClientHello
import meshcheck.agent.v1.ConnectionClass
import meshcheck.agent.v1.Envelope
import meshcheck.agent.v1.Heartbeat
import meshcheck.agent.v1.ResultSubmit
import meshcheck.agent.v1.Shutdown
import meshcheck.agent.v1.ShutdownReason
import meshcheck.agent.v1.TaskAck
import meshcheck.agent.v1.TaskAckStatus
import meshcheck.agent.v1.TaskAssignment
import meshcheck.agent.v1.UpdateAvailable
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/**
 * Holds the agent's WebSocket connection to the MeshCheck gateway and speaks
 * the protocol in `doc/agent-protocol.md`: TLS 1.3 (via bundled Conscrypt),
 * the `ServerHello` / `ClientHello` handshake, heartbeats, the task lifecycle,
 * flow control, shutdown, and reconnect-with-backoff.
 *
 * One instance is owned by the foreground service. Task execution is delegated
 * to a [TaskGateway] so this class never depends on the check or credential
 * layers.
 */
class AgentClient(
    private val config: AgentConfig,
    private val gateway: TaskGateway,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    /** The live connection state. */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    /** Rolling job counters since the current [start]. */
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _updateAvailable = MutableStateFlow<AvailableUpdate?>(null)
    /**
     * Set when the platform sends an `UpdateAvailable`; null until then. The
     * UI surfaces it as a nudge to update — the app cannot self-update.
     */
    val updateAvailable: StateFlow<AvailableUpdate?> = _updateAvailable.asStateFlow()

    private var managerJob: Job? = null

    /**
     * Starts connecting (and reconnecting) with the given credentials. The
     * [apiKey] authenticates the WebSocket upgrade; [ed25519PublicKey] is the
     * Node's Result-signing public key, registered in `ClientHello`.
     */
    fun start(apiKey: String, ed25519PublicKey: ByteArray) {
        if (managerJob?.isActive == true) return
        _stats.value = SessionStats()
        _updateAvailable.value = null
        managerJob = scope.launch { connectLoop(apiKey, ed25519PublicKey) }
    }

    /** Stops connecting and closes any open connection. Reversible via [start]. */
    fun stop() {
        managerJob?.cancel()
        managerJob = null
        _state.value = ConnectionState.Stopped(StopReason.REQUESTED)
    }

    // --- Connect / reconnect loop -------------------------------------------

    private suspend fun connectLoop(apiKey: String, publicKey: ByteArray) {
        var attempt = 0
        while (coroutineContext.isActive) {
            _state.value = ConnectionState.Connecting
            val outcome = try {
                runConnection(apiKey, publicKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionOutcome.Retry(hadConnected = false)
            }
            when (outcome) {
                is ConnectionOutcome.Stop -> {
                    _state.value = ConnectionState.Stopped(outcome.reason)
                    return
                }
                is ConnectionOutcome.Retry -> {
                    attempt = if (outcome.hadConnected) 1 else attempt + 1
                    val backoff = backoffMillis(attempt)
                    _state.value = ConnectionState.Reconnecting(attempt, backoff)
                    delay(backoff)
                }
            }
        }
    }

    /**
     * Runs one connection from dial to close. Suspends until the connection
     * ends, then returns how the caller should proceed.
     */
    private suspend fun runConnection(apiKey: String, publicKey: ByteArray): ConnectionOutcome {
        val outcome = CompletableDeferred<ConnectionOutcome>()
        val connectionJob = Job(coroutineContext[Job])
        val connectionScope = CoroutineScope(coroutineContext + connectionJob)

        val serverHelloSeen = AtomicBoolean(false)
        val inflight = AtomicInteger(0)
        val maxInflight = AtomicInteger(DEFAULT_MAX_INFLIGHT)
        val taskJobs = ConcurrentHashMap<String, Job>()

        fun startTask(webSocket: WebSocket, task: TaskAssignment) {
            inflight.incrementAndGet()
            val job = connectionScope.launch {
                try {
                    gateway.runTask(task)?.let { result ->
                        sendEnvelope(webSocket, wrap(result = result))
                        _stats.update { it.copy(done = it.done + 1) }
                    }
                } catch (_: CancellationException) {
                    // Task cancelled (TaskCancel, or the connection ended) —
                    // submit nothing; the platform re-dispatches.
                } catch (_: Exception) {
                    // Executor failure — submit nothing.
                } finally {
                    inflight.decrementAndGet()
                    taskJobs.remove(task.task_id)
                }
            }
            taskJobs[task.task_id] = job
        }

        fun handle(webSocket: WebSocket, envelope: Envelope) {
            when {
                envelope.server_hello != null -> {
                    val hello = envelope.server_hello!!
                    serverHelloSeen.set(true)
                    maxInflight.set(
                        hello.max_concurrent_tasks.takeIf { it > 0 } ?: DEFAULT_MAX_INFLIGHT,
                    )
                    sendEnvelope(webSocket, wrap(clientHello = buildClientHello(publicKey)))
                    _state.value = ConnectionState.Connected(hello.node_id)
                    val interval = hello.heartbeat_interval_seconds
                        .takeIf { it > 0 } ?: DEFAULT_HEARTBEAT_SECONDS
                    connectionScope.launch { heartbeatLoop(webSocket, interval, inflight) }
                }

                envelope.task != null -> {
                    val task = envelope.task!!
                    if (inflight.get() >= maxInflight.get()) {
                        sendEnvelope(
                            webSocket,
                            wrap(taskAck = TaskAck(task.task_id, TaskAckStatus.TASK_ACK_REJECTED_OVERLOAD)),
                        )
                    } else {
                        sendEnvelope(
                            webSocket,
                            wrap(taskAck = TaskAck(task.task_id, TaskAckStatus.TASK_ACK_ACCEPTED)),
                        )
                        startTask(webSocket, task)
                        _stats.update { it.copy(received = it.received + 1) }
                    }
                }

                envelope.task_cancel != null -> {
                    taskJobs.remove(envelope.task_cancel!!.task_id)?.cancel()
                }

                envelope.flow_control != null -> {
                    val limit = envelope.flow_control!!.max_inflight_tasks
                    if (limit > 0) maxInflight.set(limit)
                }

                envelope.shutdown != null -> {
                    webSocket.close(WS_CLOSE_NORMAL, "shutdown acknowledged")
                    outcome.complete(shutdownOutcome(envelope.shutdown!!))
                }

                envelope.update_available != null -> {
                    // We can't self-update on Android; record it so the UI can
                    // nudge the user to update via Play / a new APK. The
                    // connection stays up. manifest_url is ignored.
                    _updateAvailable.value = toAvailableUpdate(envelope.update_available!!)
                }
                // result_ack, error, and agent-sent types: nothing to do here.
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // The platform must send ServerHello first; if it does not
                // arrive in time, drop the connection and retry.
                connectionScope.launch {
                    delay(SERVER_HELLO_TIMEOUT_MS)
                    if (!serverHelloSeen.get()) {
                        webSocket.cancel()
                        outcome.complete(ConnectionOutcome.Retry(hadConnected = false))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val envelope = runCatching { Envelope.ADAPTER.decode(bytes.toByteArray()) }
                    .getOrNull() ?: return
                handle(webSocket, envelope)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(WS_CLOSE_NORMAL, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                outcome.complete(ConnectionOutcome.Retry(hadConnected = serverHelloSeen.get()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                outcome.complete(
                    when (response?.code) {
                        HTTP_UNAUTHORIZED -> ConnectionOutcome.Stop(StopReason.UNAUTHORIZED)
                        HTTP_UPGRADE_REQUIRED -> ConnectionOutcome.Stop(StopReason.OUTDATED)
                        else -> ConnectionOutcome.Retry(hadConnected = serverHelloSeen.get())
                    },
                )
            }
        }

        val request = Request.Builder()
            .url(config.gatewayUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Sec-WebSocket-Protocol", config.subprotocol)
            .header("X-Agent-Version", config.agentVersion)
            .header("X-Agent-Platform", config.platform)
            .build()

        val webSocket = httpClient.newWebSocket(request, listener)
        try {
            return outcome.await()
        } finally {
            // Ends the heartbeat and any in-flight task coroutines.
            connectionJob.cancel()
            webSocket.cancel()
        }
    }

    // --- Outgoing messages --------------------------------------------------

    private suspend fun heartbeatLoop(
        webSocket: WebSocket,
        intervalSeconds: Int,
        inflight: AtomicInteger,
    ) {
        while (coroutineContext.isActive) {
            delay(intervalSeconds * 1000L)
            val runtime = Runtime.getRuntime()
            sendEnvelope(
                webSocket,
                wrap(
                    heartbeat = Heartbeat(
                        current_load = inflight.get(),
                        cpu_percent = 0,
                        memory_bytes = runtime.totalMemory() - runtime.freeMemory(),
                    ),
                ),
            )
        }
    }

    private fun buildClientHello(publicKey: ByteArray): ClientHello = ClientHello(
        agent_version = config.agentVersion,
        platform = config.platform,
        capabilities = Capabilities(
            supported_check_types = config.supportedCheckTypes,
            can_send_icmp = false,
            connection_class = ConnectionClass.CONNECTION_CLASS_MOBILE,
            name = config.nodeName,
        ),
        ed25519_pubkey = ByteString.of(*publicKey),
    )

    private fun wrap(
        clientHello: ClientHello? = null,
        heartbeat: Heartbeat? = null,
        taskAck: TaskAck? = null,
        result: ResultSubmit? = null,
    ): Envelope = Envelope(
        message_id = Ulid.generate(),
        sent_at = System.currentTimeMillis(),
        client_hello = clientHello,
        heartbeat = heartbeat,
        task_ack = taskAck,
        result = result,
    )

    private fun sendEnvelope(webSocket: WebSocket, envelope: Envelope) {
        webSocket.send(ByteString.of(*Envelope.ADAPTER.encode(envelope)))
    }

    // --- Helpers ------------------------------------------------------------

    private fun toAvailableUpdate(message: UpdateAvailable): AvailableUpdate =
        AvailableUpdate(targetVersion = message.target_version, mandatory = message.mandatory)

    private fun shutdownOutcome(shutdown: Shutdown): ConnectionOutcome = when (shutdown.reason) {
        ShutdownReason.SHUTDOWN_REASON_NODE_SUSPENDED,
        ShutdownReason.SHUTDOWN_REASON_KEY_REVOKED,
        ShutdownReason.SHUTDOWN_REASON_PROTOCOL_VIOLATION ->
            ConnectionOutcome.Stop(StopReason.SHUTDOWN)

        ShutdownReason.SHUTDOWN_REASON_PLATFORM_RESTART,
        ShutdownReason.SHUTDOWN_REASON_UNKNOWN ->
            ConnectionOutcome.Retry(hadConnected = true)
    }

    private fun backoffMillis(attempt: Int): Long {
        val step = attempt.coerceIn(1, MAX_BACKOFF_STEP)
        return (BASE_BACKOFF_MS shl (step - 1)).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.MILLISECONDS) // app-level Heartbeat instead
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // long-lived connection
            .retryOnConnectionFailure(true)

        // Conscrypt supplies TLS 1.3 down to API 21; the protocol mandates it.
        runCatching {
            val trustManager = systemTrustManager()
            val sslContext = SSLContext.getInstance("TLS", Conscrypt.newProvider())
            sslContext.init(null, arrayOf(trustManager), null)
            val tls13 = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                .tlsVersions(TlsVersion.TLS_1_3)
                .build()
            builder
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .connectionSpecs(listOf(tls13))
        }
        return builder.build()
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    private sealed interface ConnectionOutcome {
        /** The connection ended; reconnect. [hadConnected] resets the backoff. */
        data class Retry(val hadConnected: Boolean) : ConnectionOutcome

        /** The connection ended terminally; do not reconnect. */
        data class Stop(val reason: StopReason) : ConnectionOutcome
    }

    private companion object {
        const val SERVER_HELLO_TIMEOUT_MS = 5_000L
        const val DEFAULT_HEARTBEAT_SECONDS = 30
        const val DEFAULT_MAX_INFLIGHT = 4
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_STEP = 7
        const val WS_CLOSE_NORMAL = 1000
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_UPGRADE_REQUIRED = 426
    }
}
