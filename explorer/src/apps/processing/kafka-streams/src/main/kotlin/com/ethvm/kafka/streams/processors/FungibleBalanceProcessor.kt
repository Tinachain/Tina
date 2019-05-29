package com.ethvm.kafka.streams.processors

import com.ethvm.avro.capture.CanonicalKeyRecord
import com.ethvm.avro.common.TraceLocationRecord
import com.ethvm.avro.processing.FungibleBalanceDeltaListRecord
import com.ethvm.avro.processing.FungibleBalanceDeltaRecord
import com.ethvm.avro.processing.FungibleBalanceDeltaType
import com.ethvm.avro.processing.FungibleBalanceKeyRecord
import com.ethvm.avro.processing.FungibleBalanceRecord
import com.ethvm.avro.processing.FungibleTokenType
import com.ethvm.common.extensions.getAmountBI
import com.ethvm.common.extensions.getNumberBI
import com.ethvm.common.extensions.getTransactionFeeBI
import com.ethvm.common.extensions.hexToBI
import com.ethvm.common.extensions.reverse
import com.ethvm.common.extensions.setAmountBI
import com.ethvm.common.extensions.setBlockNumberBI
import com.ethvm.common.extensions.toEtherBalanceDeltas
import com.ethvm.common.extensions.toFungibleBalanceDeltas
import com.ethvm.kafka.streams.Serdes
import com.ethvm.kafka.streams.config.Topics.CanonicalBlockAuthor
import com.ethvm.kafka.streams.config.Topics.CanonicalBlockHeader
import com.ethvm.kafka.streams.config.Topics.CanonicalMinerFeesEtherDeltas
import com.ethvm.kafka.streams.config.Topics.CanonicalReceiptErc20Deltas
import com.ethvm.kafka.streams.config.Topics.CanonicalReceipts
import com.ethvm.kafka.streams.config.Topics.CanonicalTraces
import com.ethvm.kafka.streams.config.Topics.CanonicalTracesEtherDeltas
import com.ethvm.kafka.streams.config.Topics.CanonicalTransactionFees
import com.ethvm.kafka.streams.config.Topics.CanonicalTransactionFeesEtherDeltas
import com.ethvm.kafka.streams.config.Topics.FungibleBalance
import com.ethvm.kafka.streams.config.Topics.FungibleBalanceDelta
import com.ethvm.kafka.streams.processors.transformers.OncePerBlockTransformer
import com.ethvm.kafka.streams.utils.ERC20Abi
import com.ethvm.kafka.streams.utils.toTopic
import mu.KotlinLogging
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.Grouped
import org.apache.kafka.streams.kstream.JoinWindows
import org.apache.kafka.streams.kstream.Joined
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Materialized
import org.apache.kafka.streams.kstream.TransformerSupplier
import java.math.BigInteger
import java.time.Duration
import java.util.Properties

class FungibleBalanceProcessor : AbstractKafkaProcessor() {

  override val id: String = "fungible-balance-processor"

  override val kafkaProps: Properties = Properties()
    .apply {
      putAll(baseKafkaProps.toMap())
      put(StreamsConfig.APPLICATION_ID_CONFIG, id)
      put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 8)
      put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000L)
      put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 2000000000)
    }

  override val logger = KotlinLogging.logger {}

  override fun buildTopology(): Topology {

    val builder = StreamsBuilder().apply {
      addStateStore(OncePerBlockTransformer.canonicalRecordsStore(appConfig.unitTesting))
    }

    syntheticEtherDeltas(builder)

    etherDeltasForTraces(builder)
    etherDeltasForFees(builder)
    erc20DeltasForReceipts(builder)

    aggregateBalances(builder)

    // Generate the topology
    return builder.build()
  }

  private fun aggregateBalances(builder: StreamsBuilder) {

    FungibleBalanceDelta.stream(builder)
      .groupByKey(Grouped.with(Serdes.FungibleBalanceKey(), Serdes.FungibleBalanceDelta()))
      .aggregate(
        {
          FungibleBalanceRecord.newBuilder()
            .setAmountBI(BigInteger.ZERO)
            .build()
        },
        { _, delta, balance ->

          FungibleBalanceRecord.newBuilder()
            .setAmountBI(delta.getAmountBI() + balance.getAmountBI())
            .build()
        },
        Materialized.with(Serdes.FungibleBalanceKey(), Serdes.FungibleBalance())
      )
      .toStream()
      .toTopic(FungibleBalance)

    FungibleBalance.stream(builder)
      .peek { k, v -> logger.debug { "Balance update | ${k.getAddress()}, ${k.getContract()} -> ${v.getAmountBI()}" } }
  }

  /**
   * Premine and other synthetic transfers such as DAO hard fork
   */
  private fun syntheticEtherDeltas(builder: StreamsBuilder) {

    // add a transformer to guarantee we only emit once per block number so we don't re-introduce synthetic events in the event of a fork

    val canonicalBlocks = CanonicalBlockHeader.stream(builder)
      .transform(
        TransformerSupplier { OncePerBlockTransformer(appConfig.unitTesting) },
        *OncePerBlockTransformer.STORE_NAMES
      )

    // premine balances

    canonicalBlocks
      .flatMap { k, _ ->

        if (k.getNumberBI() > BigInteger.ZERO)
          emptyList()
        else {

          var deltas =
            netConfig.genesis
              .accounts
              .entries
              .map { (address, premine) ->

                val balance = premine.balance

                FungibleBalanceDeltaRecord.newBuilder()
                  .setTokenType(FungibleTokenType.ETHER)
                  .setDeltaType(FungibleBalanceDeltaType.PREMINE_BALANCE)
                  .setTraceLocation(
                    TraceLocationRecord.newBuilder()
                      .setBlockNumberBI(BigInteger.ZERO)
                      .build()
                  )
                  .setAddress(address)
                  .setAmountBI(balance.hexToBI())
                  .build()
              }

          deltas.map { delta ->
            KeyValue(
              FungibleBalanceKeyRecord.newBuilder()
                .setAddress(delta.getAddress())
                .setContract(delta.getContractAddress())
                .build(),
              delta
            )
          }
        }
      }
      .filter { _, v -> v.getAmount() != null && v.getAmountBI() != BigInteger.ZERO }
      .toTopic(FungibleBalanceDelta)

    //

    canonicalBlocks
      .flatMap { k, _ ->

        val blockNumber = k.getNumberBI()

        netConfig
          .chainConfigForBlock(blockNumber)
          .hardForkFungibleDeltas(blockNumber)
          .map { delta ->

            KeyValue(
              FungibleBalanceKeyRecord.newBuilder()
                .setAddress(delta.getAddress())
                .setContract(delta.getContractAddress())
                .build(),
              delta
            )
          }
      }
      .filter { _, v -> v.getAmount() != null && v.getAmountBI() != BigInteger.ZERO }
      .toTopic(FungibleBalanceDelta)
  }

  /**
   *
   */
  private fun etherDeltasForTraces(builder: StreamsBuilder) {

    CanonicalTraces.stream(builder)
      .mapValues { _, tracesList ->

        val blockHash = tracesList.getTraces().firstOrNull()?.getBlockHash()

        when (tracesList) {
          null -> null
          else -> {

            FungibleBalanceDeltaListRecord.newBuilder()
              .setBlockHash(blockHash)
              .setDeltas(tracesList.toFungibleBalanceDeltas())
              .build()
          }
        }
      }.toTopic(CanonicalTracesEtherDeltas)

    mapToFungibleBalanceDeltas(CanonicalTracesEtherDeltas.stream(builder))
  }

  private fun etherDeltasForFees(builder: StreamsBuilder) {

    val txFeesStream = CanonicalTransactionFees.stream(builder)

    txFeesStream
      .mapValues { _, feeList ->

        if (feeList != null) {
          FungibleBalanceDeltaListRecord.newBuilder()
            .setBlockHash(feeList.getBlockHash())
            .setDeltas(feeList.toEtherBalanceDeltas())
            .build()
        } else {
          // pass along the tombstone
          null
        }
      }.toTopic(CanonicalTransactionFeesEtherDeltas)

    mapToFungibleBalanceDeltas(CanonicalTransactionFeesEtherDeltas.stream(builder))

    CanonicalBlockAuthor.stream(builder)
      .join(
        txFeesStream,
        { left, right ->

          if (left.getBlockHash() != right.getBlockHash()) {

            // We're in the middle of an update/fork so we publish a tombstone
            null
          } else {

            val totalTxFees = right.getTransactionFees()
              .map { it.getTransactionFeeBI() }
              .fold(BigInteger.ZERO) { memo, next -> memo + next }

            FungibleBalanceDeltaRecord.newBuilder()
              .setTokenType(FungibleTokenType.ETHER)
              .setDeltaType(FungibleBalanceDeltaType.MINER_FEE)
              .setTraceLocation(
                TraceLocationRecord.newBuilder()
                  .setBlockNumber(left.getBlockNumber())
                  .setBlockHash(left.getBlockHash())
                  .build()
              )
              .setAddress(left.getAuthor())
              .setAmountBI(totalTxFees)
              .build()
          }
        },
        JoinWindows.of(Duration.ofHours(2)),
        Joined.with(Serdes.CanonicalKey(), Serdes.BlockAuthor(), Serdes.TransactionFeeList())
      ).toTopic(CanonicalMinerFeesEtherDeltas)

    CanonicalMinerFeesEtherDeltas.stream(builder)
      .mapValues { v ->

        if (v != null) {
          FungibleBalanceDeltaListRecord.newBuilder()
            .setBlockHash(v.getTraceLocation().getBlockHash())
            .setDeltas(listOf(v))
            .build()
        } else {
          null
        }
      }
      .groupByKey()
      .reduce(
        { agg, next ->

          if (next!!.getBlockHash() == agg!!.getBlockHash()) {

            // an update has been published for a previously seen block
            // we assume no material change and therefore emit an event which will have no impact on the balances

            FungibleBalanceDeltaListRecord.newBuilder(agg)
              .setApply(false)
              .build()
          } else {

            // reverse previous deltas

            FungibleBalanceDeltaListRecord.newBuilder()
              .setBlockHash(next.getBlockHash())
              .setApply(true)
              .setDeltas(next.getDeltas())
              .setReversals(agg.getDeltas().map { it.reverse() })
              .build()
          }
        },
        Materialized.with(Serdes.CanonicalKey(), Serdes.FungibleBalanceDeltaList())
      )
      .toStream()
      .flatMap { _, v ->

        if (v!!.getApply()) {

          (v.getDeltas() + v.getReversals())
            .map { delta ->
              KeyValue(
                FungibleBalanceKeyRecord.newBuilder()
                  .setAddress(delta.getAddress())
                  .build(),
                delta
              )
            }
        } else {
          emptyList()
        }
      }.toTopic(FungibleBalanceDelta)
  }

  private fun erc20DeltasForReceipts(builder: StreamsBuilder) {

    CanonicalReceipts.stream(builder)
      .mapValues { _, v ->

        when (v) {
          null -> null
          else -> {

            // filter out receipts with ERC20 related logs

            val blockHash = v.getReceipts().firstOrNull()?.getBlockHash()

            val receiptsWithErc20Logs = v.getReceipts()
              .filter { receipt ->

                val logs = receipt.getLogs()

                when (logs.isEmpty()) {
                  true -> false
                  else ->
                    logs
                      .map { log -> ERC20Abi.matchEventHex(log.getTopics()).isDefined() }
                      .reduce { a, b -> a || b }
                }
              }

            val deltas = receiptsWithErc20Logs
              .flatMap { receipt ->

                val traceLocation = TraceLocationRecord.newBuilder()
                  .setBlockNumber(receipt.getBlockNumber())
                  .setBlockHash(receipt.getBlockHash())
                  .setTransactionHash(receipt.getTransactionHash())
                  .build()

                receipt.getLogs()
                  .map { log -> ERC20Abi.decodeTransferEventHex(log.getData(), log.getTopics()) }
                  .mapNotNull { transferOpt -> transferOpt.orNull() }
                  .flatMap { transfer ->

                    val contractAddress =
                      if (receipt.getTo() != null)
                        receipt.getTo()
                      else
                        receipt.getContractAddress()

                    listOf(
                      FungibleBalanceDeltaRecord.newBuilder()
                        .setTokenType(FungibleTokenType.ERC20)
                        .setDeltaType(FungibleBalanceDeltaType.TOKEN_TRANSFER)
                        .setTraceLocation(traceLocation)
                        .setAddress(transfer.from)
                        .setContractAddress(contractAddress)
                        .setCounterpartAddress(transfer.to)
                        .setAmountBI(transfer.amount.negate())
                        .build(),
                      FungibleBalanceDeltaRecord.newBuilder()
                        .setTokenType(FungibleTokenType.ERC20)
                        .setDeltaType(FungibleBalanceDeltaType.TOKEN_TRANSFER)
                        .setTraceLocation(traceLocation)
                        .setAddress(transfer.to)
                        .setCounterpartAddress(transfer.from)
                        .setContractAddress(contractAddress)
                        .setAmountBI(transfer.amount)
                        .build()
                    )
                  }
              }

            FungibleBalanceDeltaListRecord.newBuilder()
              .setBlockHash(blockHash)
              .setDeltas(deltas)
              .build()
          }
        }
      }.toTopic(CanonicalReceiptErc20Deltas)

    mapToFungibleBalanceDeltas(CanonicalReceiptErc20Deltas.stream(builder))
  }

  private fun mapToFungibleBalanceDeltas(stream: KStream<CanonicalKeyRecord, FungibleBalanceDeltaListRecord>) {

    stream
      .groupByKey(Grouped.with(Serdes.CanonicalKey(), Serdes.FungibleBalanceDeltaList()))
      .reduce(
        { agg, next ->

          if (next.getBlockHash() == agg.getBlockHash()) {

            // an update has been published for a previously seen block
            // we assume no material change and therefore emit an event which will have no impact on the balances

            logger.warn { "Update received. Agg = $agg, next = $next" }

            FungibleBalanceDeltaListRecord.newBuilder(agg)
              .setApply(false)
              .build()
          } else {

            // reverse previous deltas

            FungibleBalanceDeltaListRecord.newBuilder()
              .setBlockHash(next.getBlockHash())
              .setDeltas(next.getDeltas())
              .setReversals(agg.getDeltas().map { it.reverse() })
              .build()
          }
        },
        Materialized.with(Serdes.CanonicalKey(), Serdes.FungibleBalanceDeltaList())
      )
      .toStream()
      .flatMap { _, v ->

        if (v.getApply()) {

          (v.getDeltas() + v.getReversals())
            .map { delta ->
              KeyValue(
                FungibleBalanceKeyRecord.newBuilder()
                  .setAddress(delta.getAddress())
                  .setContract(delta.getContractAddress())
                  .build(),
                delta
              )
            }
        } else {
          emptyList()
        }
      }.toTopic(FungibleBalanceDelta)
  }

  override fun start(cleanUp: Boolean) {
    logger.info { "Starting ${this.javaClass.simpleName}..." }
    super.start(cleanUp)
  }
}
