package com.etoos.packetlogger.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

class BaseProperties {

    @ConstructorBinding
    @ConfigurationProperties(prefix = "packet-logger")
    class PacketLogger(
        val enabled: Boolean = false,
        val serviceName: String = "",
        val hideKeywords: List<String> = emptyList(),
        val sendHeaders: List<String> = emptyList(),
        val receiveHeaders: List<String> = emptyList(),
        val registerPattern: List<String> = emptyList(),
        val dropEvent: List<String> = emptyList(),
        val tag: String = "packet-logger",
    )

}