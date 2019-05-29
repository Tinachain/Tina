package com.ethvm.kafka.streams.processors

import com.ethvm.common.config.NetConfig
import com.ethvm.kafka.streams.config.AppConfig
import mu.KLogger
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.Topology
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.koin.core.qualifier.named
import java.util.Properties

interface KafkaProcessor {
  fun buildTopology(): Topology
  fun start(cleanUp: Boolean = false)
}

abstract class AbstractKafkaProcessor : KafkaProcessor, KoinComponent {

  protected val appConfig: AppConfig by inject()
  protected val baseKafkaProps: Properties by inject(named("baseKafkaStreamsConfig"))

  protected val netConfig: NetConfig by inject()

  protected abstract val id: String
  protected abstract val logger: KLogger
  protected abstract val kafkaProps: Properties

  override fun start(cleanUp: Boolean) {

    logger.info { "Starting ${this.javaClass.simpleName}..." }

    val topology = buildTopology()

    val streams = KafkaStreams(topology, kafkaProps)

    streams.apply {

      if (cleanUp) {
        // remove local streams state
        logger.info { "Cleaning local state" }
        cleanUp()
      }

      start()
    }

    // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
    Runtime.getRuntime().addShutdownHook(Thread(streams::close))
  }
}
