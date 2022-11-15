package com.etoos.packetlogger.filter

import com.etoos.packetlogger.config.BaseProperties
import com.etoos.packetlogger.filter.LoggerFilter.Companion.B3_TRACE_ID
import com.etoos.packetlogger.filter.LoggerFilter.Companion.DIRECTION
import com.etoos.packetlogger.filter.LoggerFilter.Companion.REFERRER
import com.etoos.packetlogger.filter.LoggerFilter.Companion.VERSION
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PacketDto(
    var event: Event? = null,
    var url: Url? = null,
    var client: Client? = null,
    var http: Http? = null,
    var query: String = "",
    val serviceName: String? = null,
    var type: String? = null,
    var network: Network? = null,
    var apiType: String? = null,
    var userAgent: UserAgent? = null,
    var method: String? = null,
    var status: String? = null,
    var host: Host? = null,
    var statusCode: Int? = null,
    var tag: String = "",
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Event(
    var duration: Long = 0,
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    var start: LocalDateTime = LocalDateTime.now(),
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    var end: LocalDateTime = LocalDateTime.now(),
)

fun Event.build(): Event = apply {
    duration = ChronoUnit.NANOS.between(start, end)
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Client(var ip: String = "", var port: Int = 0)

fun Client.build(request: HttpServletRequest): Client = apply {
    ip = request.remoteAddr
    port = request.remotePort
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Network(var forwardedIp: String? = null, var direction: String = DIRECTION)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Url(var full: String = "", var path: String = "", var query: String? = null)

fun Url.build(request: HttpServletRequest): Url = apply {
    query = request.queryString
    full = "${request.requestURL}${request.queryString?.ifEmpty { null }?.let { "?${it}" } ?: ""}"
    path = request.servletPath ?: ""
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class Host(var name: String = "")

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class UserAgent(var original: String = "")

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Http(
    var version: String = VERSION,
    var request: Request? = null,
    var response: Response? = null,
)

fun Http.build(
    request: HttpServletRequest,
    response: HttpServletResponse,
    reqBody: String?,
    resBody: String?,
    code: String?,
    message: String?,
    options: BaseProperties.PacketLogger,
): Http = apply {
    this.request = Request().build(request = request, content = reqBody, options = options)
    this.response = Response().build(response = response, content = resBody, code = code, message = message, headers = options.receiveHeaders)
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class Request(
    var method: String? = null,
    var referrer: String? = null,
    var headers: Map<String, Any> = emptyMap(),
    var body: Body? = null,
    var transactionId: String? = null,
    var pathPattern: String? = null,
    var pathParam: String? = null,
)

fun Request.build(request: HttpServletRequest, content: String?, options: BaseProperties.PacketLogger): Request = apply {
    val (pattern, param) = registerPattern(request.servletPath, options.registerPattern)
    referrer = request.getHeader(REFERRER)
    pathPattern = pattern
    pathParam = param
    headers = filterHeaders(request = request, headers = options.sendHeaders)
    body = Body(content = content, bytes = content?.length)
    transactionId = request.getHeader(B3_TRACE_ID)
}

fun registerPattern(reqUrl: String, patternUrl: List<String>): Pair<String, String?> {
    for (pattern in patternUrl) {
        val (result, map) = matchUrl(reqUrl, pattern)
        if (result) {
            return Pair(pattern, (map?.toList()?.fold("") { acc, (key, value) -> "$acc&$key=$value" } ?: "&").drop(1))
        }
    }
    return Pair(reqUrl, null)
}

fun matchUrl(reqUrl: String, patternUrl: String): Pair<Boolean, Map<String, String>?> {

    if (reqUrl == patternUrl)
        return true to null

    val patterns = patternUrl.split("/")
    val paths = reqUrl.split("/")

    if (patterns.size != paths.size)
        return false to null

    val params = mutableMapOf<String, String>()

    patterns.zip(paths).forEach { (pattern, path) ->
        when {
            pattern.isNotEmpty() && pattern.startsWith(":") -> params[pattern.substringAfter(":")] = path
            pattern != path -> return@matchUrl false to null
        }
    }
    return true to params
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class Response(
    var code: String? = null,
    var message: String? = null,
    var body: Body? = null,
    var headers: Map<String, Any> = emptyMap(),
    var bytes: Int? = null,
)

fun Response.build(response: HttpServletResponse, content: String?, code: String?, message: String?, headers: List<String>): Response = apply {
    this.code = code
    this.message = message
    this.body = Body(bytes = content?.length, content = content)
    this.bytes = content?.toByteArray()?.size
    this.headers = headers.mapNotNull { key ->
        response.getHeader(key)?.let { key to it }
    }.toMap()
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class Body(var bytes: Int? = null, var content: String? = null)
