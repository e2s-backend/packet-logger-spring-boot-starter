package com.etoos.packetlogger.filter

import com.etoos.packetlogger.config.BaseProperties
import com.etoos.packetlogger.filter.LoggerFilter.Companion.MASK
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.type.TypeFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import org.springframework.web.util.WebUtils
import java.time.LocalDateTime
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component
@Order(99)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "packet-logger", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(BaseProperties.PacketLogger::class)
class LoggerFilter(private val objectMapper: ObjectMapper, private val options: BaseProperties.PacketLogger) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) = when (isAsyncDispatch(request)) {
        true -> filterChain.doFilter(request, response)
        else -> doFilterWrapped(wrapRequest(request), wrapResponse(response), filterChain)
    }

    private fun doFilterWrapped(request: ContentCachingRequestWrapper, response: ContentCachingResponseWrapper, filterChain: FilterChain) {
        val start = LocalDateTime.now()
        filterChain.doFilter(request, response).also {
            try {
                doLogger(request, response, start)
            } catch (ignore: Exception) {
            }
            response.copyBodyToResponse()
        }
    }

    private fun wrapRequest(request: HttpServletRequest): ContentCachingRequestWrapper =
        request as? ContentCachingRequestWrapper ?: ContentCachingRequestWrapper(request)

    private fun wrapResponse(response: HttpServletResponse): ContentCachingResponseWrapper =
        response as? ContentCachingResponseWrapper ?: ContentCachingResponseWrapper(response)

    private fun doLogger(request: ContentCachingRequestWrapper, response: ContentCachingResponseWrapper, start: LocalDateTime) {

        if (request.servletPath in options.dropEvent) return

        val requestNativeContent = nativeRequest(request).orEmpty()
        val filteredRequest = filterContent(mapContent(objectMapper, requestNativeContent), options.hideKeywords)?.let { objectMapper.writeValueAsString(it) }

        val responseNativeContent = nativeResponse(response).orEmpty()
        val responseMaskContent = filterContent(mapContent(objectMapper, responseNativeContent), options.hideKeywords)
        val filteredResponse = responseMaskContent?.let { objectMapper.writeValueAsString(responseMaskContent) }

        val code = responseMaskContent?.get(CODE)?.toString()
        val message = responseMaskContent?.get(MESSAGE)?.toString()

        PacketDto(
            serviceName = options.serviceName,
            type = PACKET_TYPE,
            apiType = apiType(request),
            tag = options.tag,
            query = query(request),
            method = request.method.lowercase(),
            event = Event(start = start, end = LocalDateTime.now()).build(),
            network = Network(forwardedIp = request.getHeader(FORWARDED_FOR)?.split(",")?.firstOrNull()?.trim()),
            url = Url().build(request),
            client = Client().build(request),
            host = Host(name = request.serverName),
            userAgent = UserAgent(original = request.getHeader(USER_AGENT)),
            http = Http().build(
                request = request,
                response = response,
                reqBody = filteredRequest ?: requestNativeContent,
                resBody = filteredResponse ?: responseNativeContent,
                code = code,
                message = message,
                options = options
            ),
            statusCode = response.status,
            status = if (response.status == 200) OK else NOT_OK
        ).also {
            println(objectMapper.writeValueAsString(it))
        }
    }

    companion object {
        const val VERSION = "1.1"
        const val PACKET_TYPE = "http"
        const val USER_AGENT = "user-agent"
        const val DIRECTION = "ingress"
        const val MASK = "xxxx"
        const val CODE = "code"
        const val MESSAGE = "message"
        const val OK = "OK"
        const val NOT_OK = "NOT OK"
        const val FORWARDED_FOR = "x-forwarded-for"
        const val B3_TRACE_ID = "x-b3-traceid"
        const val REFERRER = "referrer"
    }

}

private fun query(request: ContentCachingRequestWrapper) = "${request.method} ${request.requestURL}"

private fun apiType(request: ContentCachingRequestWrapper) = request.servletPath.split("/").getOrNull(2)

internal fun filterHeaders(request: HttpServletRequest, headers: List<String>): Map<String, String> = headers.mapNotNull { key ->
    request.getHeader(key)?.let { key to it }
}.toMap()

private fun nativeRequest(request: HttpServletRequest): String? =
    WebUtils.getNativeRequest(request, ContentCachingRequestWrapper::class.java)?.contentAsByteArray?.let { String(it) }

private fun nativeResponse(response: HttpServletResponse): String? =
    WebUtils.getNativeResponse(response, ContentCachingResponseWrapper::class.java)?.contentAsByteArray?.let { String(it) }

private fun mapContent(objectMapper: ObjectMapper, content: String): MutableMap<String, Any>? {
    val typeFactory = TypeFactory.defaultInstance()
    return try {
        objectMapper.readValue(content, typeFactory.constructMapType(HashMap::class.java, String::class.java, Any::class.java))
    } catch (e: Exception) {
        return null
    }
}

@Suppress("UNCHECKED_CAST")
private fun filterContent(map: MutableMap<String, Any>?, hideKeywords: List<String>): Map<String, Any>? {
    if (map.isNullOrEmpty() || hideKeywords.isEmpty()) return map

    for ((key, value) in map) {
        if (value is MutableMap<*, *>) {
            map[key] = filterContent(value as MutableMap<String, Any>, hideKeywords)!!
        } else if (key in hideKeywords && value is String) {
            map[key] = MASK
        }
    }
    return map
}
