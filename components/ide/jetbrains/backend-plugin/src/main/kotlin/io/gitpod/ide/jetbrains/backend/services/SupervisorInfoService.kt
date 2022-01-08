// Copyright (c) 2021 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

package io.gitpod.ide.jetbrains.backend.services

import io.gitpod.ide.jetbrains.backend.utils.Retrier.retry
import io.gitpod.supervisor.api.Info.WorkspaceInfoRequest
import io.gitpod.supervisor.api.InfoServiceGrpc
import io.gitpod.supervisor.api.Token.GetTokenRequest
import io.gitpod.supervisor.api.TokenServiceGrpc
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.guava.asDeferred

object SupervisorInfoService {
    private const val SUPERVISOR_ADDRESS = "localhost:22999"

    // there should be only one channel per an application to avoid memory leak
    private val channel = ManagedChannelBuilder.forTarget(SUPERVISOR_ADDRESS).usePlaintext().build()

    data class Info(
        val host: String,
        val workspaceUrl: String,
        val instanceId: String,
        val authToken: String
    )

    @Suppress("MagicNumber")
    suspend fun fetch(): Info =
        retry(3) {
            // TODO(ak) retry forever only on network issues, otherwise propagate error
            val infoResponse = InfoServiceGrpc
                .newFutureStub(channel)
                .workspaceInfo(WorkspaceInfoRequest.newBuilder().build())
                .asDeferred()
                .await()

            val request = GetTokenRequest.newBuilder()
                .setHost(infoResponse.gitpodHost.split("//").last())
                .addScope("function:sendHeartBeat")
                .setKind("gitpod")
                .build()

            val response = TokenServiceGrpc
                .newFutureStub(channel)
                .getToken(request)
                .asDeferred()
                .await()

            Info(
                host = infoResponse.gitpodHost,
                workspaceUrl = infoResponse.workspaceUrl,
                instanceId = infoResponse.instanceId,
                authToken = response.token
            )
        }
}
