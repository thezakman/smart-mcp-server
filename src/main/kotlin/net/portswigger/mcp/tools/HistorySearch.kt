package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Limits regex work on captured data without creating workers that can outlive a request. */
internal class HistoryRegex(expression: String, private val maxReads: Long = 10_000_000) {
    private val pattern: Pattern
    private var reads = 0L
    private var startedAt: Long? = null

    init {
        require(expression.length <= 1024) { "History regex must not exceed 1024 characters" }
        pattern = try { Pattern.compile(expression) } catch (e: PatternSyntaxException) {
            throw IllegalArgumentException("Invalid history regex: ${e.description}")
        }
    }

    fun contains(text: String): Boolean {
        require(text.length <= 1_000_000) {
            "A history field exceeds the regex search limit of 1000000 characters; narrow the history filters"
        }
        if (startedAt == null) startedAt = System.nanoTime()
        checkBudget()
        return try { pattern.matcher(CountedText(text, 0, text.length)).find() } catch (_: StackOverflowError) {
            throw IllegalArgumentException("History regex is too complex; simplify the expression")
        }
    }

    private fun checkBudget() {
        check(reads <= maxReads && System.nanoTime() - startedAt!! < 2_000_000_000L &&
            !Thread.currentThread().isInterrupted) {
            "History regex search budget exceeded; narrow the filters or simplify the expression"
        }
    }

    private inner class CountedText(private val text: String, private val start: Int, private val end: Int) : CharSequence {
        override val length: Int get() = end - start
        override fun get(index: Int): Char {
            require(index in 0 until length)
            reads++
            if (reads > maxReads || reads % 1024 == 0L) checkBudget()
            return text[start + index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex in 0..endIndex && endIndex <= length)
            return CountedText(text, start + startIndex, start + endIndex)
        }
        override fun toString(): String = text.substring(start, end)
    }
}

internal fun filterHttpHistory(
    api: MontoyaApi, items: List<ProxyHttpRequestResponse>, inScopeOnly: Boolean, regex: HistoryRegex?
): List<ProxyHttpRequestResponse> = items.asSequence()
    .filter { !inScopeOnly || api.scope().isInScope(it.finalRequest().url()) }
    .filter { item ->
        regex == null || regex.contains(item.finalRequest().url()) ||
            regex.contains(item.finalRequest().toString()) ||
            (item.response()?.let { regex.contains(it.toString()) } == true) ||
            regex.contains(item.annotations().notes().orEmpty())
    }.toList()
