package net.portswigger.mcp.config

import java.net.BindException
import java.nio.channels.UnresolvedAddressException
import java.util.Collections
import java.util.IdentityHashMap

internal fun serverFailureMessage(error: Throwable, host: String, port: Int): String {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = error
    while (cause != null && seen.add(cause)) {
        when (cause) {
            is BindException -> return "Cannot bind the MCP server to $host:$port. The address may already be in use " +
                "by another extension or Burp instance. Unload the previous MCP extension before enabling this one. " +
                "If you need both servers, choose a different port and reinstall the client configuration."
            is UnresolvedAddressException -> return "Unable to resolve server address: $host"
        }
        cause = cause.cause
    }
    return error.message ?: error.javaClass.simpleName
}
