// Copyright (c) 2021 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

package io.gitpod.jetbrains.gateway

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.remote.RemoteCredentialsHolder
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.HorizontalAlign
import com.intellij.ui.dsl.gridLayout.VerticalAlign
import com.intellij.util.ExceptionUtil
import com.intellij.util.io.DigestUtil
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.jetbrains.gateway.ssh.ClientOverSshTunnelConnector
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.AtomicReference
import com.jetbrains.rd.util.ConcurrentHashMap
import com.jetbrains.rd.util.URI
import com.jetbrains.rd.util.concurrentMapOf
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.onTermination
import io.gitpod.gitpodprotocol.api.GitpodClient
import io.gitpod.gitpodprotocol.api.GitpodServer
import io.gitpod.gitpodprotocol.api.GitpodServerLauncher
import io.gitpod.gitpodprotocol.api.entities.Workspace
import io.gitpod.gitpodprotocol.api.entities.WorkspaceInstance
import io.gitpod.jetbrains.auth.GitpodAuthService
import io.gitpod.jetbrains.icons.GitpodIcons
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.future.await
import org.eclipse.jetty.websocket.api.UpgradeException
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import javax.swing.JComponent
import javax.swing.JLabel

class GitpodConnectionProvider : GatewayConnectionProvider {

    override suspend fun connect(
        parameters: Map<String, String>,
        requestor: ConnectionRequestor
    ): GatewayConnectionHandle? {
        if (parameters["gitpodHost"] == null) {
            throw IllegalArgumentException("bad gitpodHost parameter");
        }
        if (parameters["workspaceId"] == null) {
            throw IllegalArgumentException("bad workspaceId parameter");
        }
        val connectParams = ConnectParams(
            parameters["gitpodHost"]!!,
            parameters["workspaceId"]!!
        )
        val client = obtainClient(connectParams.gitpodHost)
        val connectionLifetime = Lifetime.Eternal.createNested()
        val updates = client.listenToWorkspace(connectionLifetime, connectParams.workspaceId)
        val workspace = client.syncWorkspace(connectParams.workspaceId)

        val phaseMessage = JLabel()
        val statusMessage = JLabel()
        val errorMessage = JLabel()
        val connectionPanel = panel {
            row {
                resizableRow()
                panel {
                    verticalAlign(VerticalAlign.CENTER)
                    row {
                        icon(GitpodIcons.Logo)
                            .horizontalAlign(HorizontalAlign.CENTER)
                    }
                    row {
                        cell(phaseMessage)
                            .bold()
                            .horizontalAlign(HorizontalAlign.CENTER)
                    }
                    row {
                        cell(statusMessage)
                            .horizontalAlign(HorizontalAlign.CENTER)
                    }
                    panel {
                        row {
                            label(connectParams.workspaceId)

                        }
                        row {
                            browserLink(workspace.contextURL, workspace.contextURL)
                        }
                    }.horizontalAlign(HorizontalAlign.CENTER)
                    row {
                        cell(errorMessage)
                    }
                }
            }
        }

        GlobalScope.launch {
            var thinClient: ThinClientHandle? = null;

            val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(2))
                .build()

            try {
                for (update in updates) {
                    try {
                        if (!update.status.conditions.failed.isNullOrBlank()) {
                            errorMessage.text = update.status.conditions.failed;
                        }
                        when (update.status.phase) {
                            "preparing" -> {
                                phaseMessage.text = "Preparing"
                                statusMessage.text = "Building workspace image..."
                            }
                            "pending" -> {
                                phaseMessage.text = "Preparing"
                                statusMessage.text = "Allocating resources …"
                            }
                            "creating" -> {
                                phaseMessage.text = "Creating"
                                statusMessage.text = "Pulling workspace image …"
                            }
                            "initializing" -> {
                                phaseMessage.text = "Starting"
                                statusMessage.text = "Initializing workspace content …"
                            }
                            "running" -> {
                                // TODO(ak) fetch supervisor for desktop ide ready then Starting
                                phaseMessage.text = "Ready"
                                statusMessage.text = ""
                            }
                            "interrupted" -> {
                                phaseMessage.text = "Starting"
                                statusMessage.text = "Checking workspace …"
                            }
                            "stopping" -> {
                                phaseMessage.text = "Stopping"
                                statusMessage.text = ""
                            }
                            "stopped" -> {
                                if (update.status.conditions.timeout.isNullOrBlank()) {
                                    phaseMessage.text = "Stopped"
                                } else {
                                    phaseMessage.text = "Timed Out"
                                }
                                statusMessage.text = ""
                            }
                            else -> {
                                phaseMessage.text = ""
                                statusMessage.text = ""
                            }
                        }

                        if (update.status.phase == "stopping" || update.status.phase == "stopped") {
                            thinClient?.close()
                        }

                        if (thinClient == null && update.status.phase == "running") {
                            val ownerToken = client.server.getOwnerToken(update.workspaceId).await()

                            val ideUrl = URL(update.ideUrl);
                            val httpRequest = HttpRequest.newBuilder()
                                .uri(URI.create("https://24000-${ideUrl.host}/joinLink"))
                                .header("x-gitpod-owner-token", ownerToken)
                                .GET()
                                .build()
                            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

                            if (response.statusCode() != 200) {
                                errorMessage.text =
                                    "failed to check workspace connectivity status ${response.statusCode()}"
                                continue;
                            }
                            val joinLink = response.body()

                            val credentials = RemoteCredentialsHolder()
                            credentials.setHost(ideUrl.host)
                            credentials.port = 22
                            credentials.userName = update.workspaceId
                            credentials.password = ownerToken

                            val connector = ClientOverSshTunnelConnector(
                                connectionLifetime,
                                credentials,
                                URI(joinLink)
                            )
                            val client = connector.connect()
                            client.clientClosed.advise(connectionLifetime) {
                                connectionLifetime.terminate()
                            }
                            thinClient = client
                        }
                    } catch (e: Throwable) {
                        thisLogger().error(
                            "${connectParams.gitpodHost}: ${connectParams.workspaceId}: failed to process workspace update:",
                            e
                        )
                    }
                }
                connectionLifetime.terminate()
            } catch (t: Throwable) {
                thisLogger().error(
                    "${connectParams.gitpodHost}: ${connectParams.workspaceId}: failed to process workspace updates:",
                    t
                )
                errorMessage.text = " failed to process workspace updates ${t.message}"
            }
        }

        return GitpodConnectionHandle(connectionLifetime, connectionPanel, connectParams);
    }

    override fun isApplicable(parameters: Map<String, String>): Boolean =
        parameters.containsKey("gitpodHost")

    private data class ConnectParams(
        val gitpodHost: String,
        val workspaceId: String
    )

    private class GitpodConnectionHandle(
        lifetime: Lifetime,
        private val component: JComponent,
        private val params: ConnectParams
    ) : GatewayConnectionHandle(lifetime) {

        override fun createComponent(): JComponent {
            return component
        }

        override fun getTitle(): String {
            return "${params.workspaceId} (${params.gitpodHost})"
        }

        override fun hideToTrayOnStart(): Boolean {
            return false
        }
    }

    private class GatewayGitpodClient(
        private val lifetimeDefinition: LifetimeDefinition, val gitpodHost: String
    ) : GitpodClient() {

        private val listeners = concurrentMapOf<String, Channel<WorkspaceInstance>?>()

        private val timeoutDelayInMinutes = 15
        private val timeoutJob = AtomicReference<Job?>(null);

        init {
            scheduleTimeout("waiting for workspace listeners")
        }

        private fun scheduleTimeout(reason: String) {
            timeoutJob.getAndUpdate {
                if (it?.isActive == true) {
                    it
                } else {
                    GlobalScope.launch {
                        thisLogger().info("$gitpodHost: connection times out in $timeoutDelayInMinutes minutes: $reason")
                        delay(timeoutDelayInMinutes * 60 * 1000L)
                        if (isActive) {
                            lifetimeDefinition.terminate()
                        }
                    }
                }
            }
        }

        private fun cancelTimeout(reason: String) {
            timeoutJob.getAndUpdate {
                if (it?.isActive == true) {
                    thisLogger().info("$gitpodHost: canceled connection timeout: $reason")
                    it?.cancel()
                }
                null
            }
        }

        override fun connect(server: GitpodServer) {
            super.connect(server);
            sync();
        }

        private fun sync() {
            GlobalScope.launch {
                for (id in listeners.keys) {
                    ensureActive()
                    try {
                        syncWorkspace(id);
                    } catch (t: Throwable) {
                        thisLogger().error("${gitpodHost}: ${id}: failed to sync", t)
                    }
                }
            }
        }

        // TODO(ak) ordered processing
        override fun onInstanceUpdate(instance: WorkspaceInstance?) {
            if (instance == null) {
                return;
            }
            val channel = listeners[instance.workspaceId] ?: return
            GlobalScope.launch {
                channel.send(instance)
            }
        }

        fun listenToWorkspace(
            listenerLifetime: Lifetime,
            workspaceId: String
        ): ReceiveChannel<WorkspaceInstance> {
            if (this.listeners.containsKey(workspaceId)) {
                // TODO(ak) test with multiple windows for the same workspace id
                throw IllegalStateException("already listening")
            }
            return this.listeners.getOrPut(workspaceId) {
                cancelTimeout("listening to workspace: $workspaceId")
                val updates = Channel<WorkspaceInstance>()
                listenerLifetime.onTermination {
                    updates.close()
                    this.listeners.remove(workspaceId)
                    if (this.listeners.isEmpty()) {
                        scheduleTimeout("no workspace listeners")
                    }
                }
                updates
            }!!
        }

        suspend fun syncWorkspace(id: String): Workspace {
            val info = server.getWorkspace(id).await()
            onInstanceUpdate(info.latestInstance)
            return info.workspace
        }

    }


    private val clients = ConcurrentHashMap<String, GatewayGitpodClient>();
    private fun obtainClient(gitpodHost: String): GatewayGitpodClient {
        return clients.getOrPut(gitpodHost) {
            val lifetime = Lifetime.Eternal.createNested()
            val client = GatewayGitpodClient(lifetime, gitpodHost)
            val launcher = GitpodServerLauncher.create(client)
            val job = GlobalScope.launch {
                var credentialsAttributes = CredentialAttributes(generateServiceName("Gitpod", gitpodHost))
                var accessToken = PasswordSafe.instance.getPassword(credentialsAttributes)
                val authorize = suspend {
                    ensureActive()
                    accessToken = GitpodAuthService.instance.authorize(gitpodHost).await().accessToken
                    PasswordSafe.instance.setPassword(credentialsAttributes, accessToken)
                }
                if (accessToken == null) {
                    authorize();
                }

                val connect = {
                    ensureActive()
                    val originalClassLoader = Thread.currentThread().contextClassLoader
                    try {
                        // see https://intellij-support.jetbrains.com/hc/en-us/community/posts/360003146180/comments/360000376240
                        Thread.currentThread().contextClassLoader = GitpodConnectionProvider::class.java.classLoader
                        launcher.listen(
                            "wss://${gitpodHost}/api/v1",
                            "https://${gitpodHost}/",
                            "jetbrains-gateway-gitpod-plugin",
                            // TODO(ak) use plugin properties to compute current version
                            "0.1-SNAPSHOT",
                            accessToken
                        )
                    } finally {
                        Thread.currentThread().contextClassLoader = originalClassLoader;
                    }
                }

                val minReconnectionDelay = 2 * 1000L
                val maxReconnectionDelay = 30 * 1000L
                val reconnectionDelayGrowFactor = 1.5;
                var reconnectionDelay = minReconnectionDelay;
                while (isActive) {
                    try {
                        var connection = try {
                            connect()
                        } catch (t: Throwable) {
                            val e = ExceptionUtil.findCause(t, UpgradeException::class.java)
                            if (e?.responseStatusCode != 401 && e?.responseStatusCode != 403) {
                                throw t;
                            }
                            thisLogger().warn("${gitpodHost}: invalid token, authorizing again and reconnecting: failed web socket handshake (${e.responseStatusCode})")
                            authorize()
                            connect()
                        }

                        var missingScope: String? = null
                        val tokenHash = DigestUtil.sha256Hex(accessToken!!.toByteArray(StandardCharsets.UTF_8))
                        val tokenScopes = client.server.getGitpodTokenScopes(tokenHash).await()
                        for (scope in GitpodAuthService.scopes) {
                            if (!tokenScopes.contains(scope)) {
                                missingScope = scope
                                break
                            }
                        }
                        if (missingScope != null) {
                            thisLogger().warn("${gitpodHost}: invalid token, authorizing again and reconnecting: $missingScope scope is not granted")
                            connection.cancel(false)
                            authorize()
                            connection = connect()
                        }
                        reconnectionDelay = minReconnectionDelay
                        thisLogger().info("${gitpodHost}: connected")
                        val reason = connection.await()
                        if (isActive) {
                            thisLogger().warn("${gitpodHost}: connection closed, reconnecting after $reconnectionDelay milliseconds: $reason")
                        } else {
                            thisLogger().info("${gitpodHost}: connection permanently closed: $reason")
                        }
                    } catch (t: Throwable) {
                        if (isActive) {
                            thisLogger().warn(
                                "${gitpodHost}: failed to connect, trying again after $reconnectionDelay milliseconds:",
                                t
                            )
                        } else {
                            thisLogger().error("${gitpodHost}: connection permanently closed:", t)
                        }
                    }
                    delay(reconnectionDelay)
                    reconnectionDelay = (reconnectionDelay * reconnectionDelayGrowFactor).toLong()
                    if (reconnectionDelay > maxReconnectionDelay) {
                        reconnectionDelay = maxReconnectionDelay
                    }
                }
            }
            lifetime.onTermination {
                clients.remove(gitpodHost)
                job.cancel()
            }
            return client
        }
    }
}
