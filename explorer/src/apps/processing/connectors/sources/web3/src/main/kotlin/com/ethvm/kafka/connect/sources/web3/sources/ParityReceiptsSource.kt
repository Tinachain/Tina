package com.ethvm.kafka.connect.sources.web3.sources

import com.ethvm.avro.capture.CanonicalKeyRecord
import com.ethvm.avro.capture.TransactionReceiptListRecord
import com.ethvm.avro.capture.TransactionReceiptRecord
import com.ethvm.common.extensions.setNumberBI
import com.ethvm.kafka.connect.sources.web3.ext.JsonRpc2_0ParityExtended
import com.ethvm.kafka.connect.sources.web3.ext.toTransactionReceiptRecord
import com.ethvm.kafka.connect.sources.web3.utils.AvroToConnect
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTaskContext
import org.web3j.protocol.core.DefaultBlockParameter

class ParityReceiptsSource(
  sourceContext: SourceTaskContext,
  parity: JsonRpc2_0ParityExtended,
  private val receiptsTopic: String,
  syncStateTopic: String
) : AbstractParityEntitySource(sourceContext, parity, syncStateTopic) {

  override val partitionKey: Map<String, Any> = mapOf("model" to "receipt")

  override fun fetchRange(range: LongRange): List<SourceRecord> =
    range
      .map { blockNumber ->

        val blockNumberBI = blockNumber.toBigInteger()
        val blockParam = DefaultBlockParameter.valueOf(blockNumberBI)

        val partitionOffset = mapOf("blockNumber" to blockNumber)

        parity.parityGetBlockReceipts(blockParam).sendAsync()
          .thenApply { resp ->

            val receipts = resp.receipts ?: emptyList()

            val receiptKeyRecord = CanonicalKeyRecord
              .newBuilder()
              .setNumberBI(blockNumberBI)
              .build()

            val receiptRecord = TransactionReceiptListRecord
              .newBuilder()
              .setReceipts(receipts.map { it.toTransactionReceiptRecord(TransactionReceiptRecord.newBuilder()).build() })
              .build()

            val receiptKeySchemaAndValue = AvroToConnect.toConnectData(receiptKeyRecord)
            val receiptValueSchemaAndValue = AvroToConnect.toConnectData(receiptRecord)

            SourceRecord(
              partitionKey,
              partitionOffset,
              receiptsTopic,
              receiptKeySchemaAndValue.schema(),
              receiptKeySchemaAndValue.value(),
              receiptValueSchemaAndValue.schema(),
              receiptValueSchemaAndValue.value()
            )
          }
      }.map { future ->
        // wait for everything to complete
        future.join()
      }

  override fun tombstonesForRange(range: LongRange): List<SourceRecord> =
    range
      .map { blockNumber ->

        val blockNumberBI = blockNumber.toBigInteger()
        val partitionOffset = mapOf("blockNumber" to blockNumber)

        val receiptKeyRecord = CanonicalKeyRecord
          .newBuilder()
          .setNumberBI(blockNumberBI)
          .build()

        val receiptKeySchemaAndValue = AvroToConnect.toConnectData(receiptKeyRecord)

        SourceRecord(
          partitionKey,
          partitionOffset,
          receiptsTopic,
          receiptKeySchemaAndValue.schema(),
          receiptKeySchemaAndValue.value(),
          AvroToConnect.toConnectSchema(TransactionReceiptListRecord.`SCHEMA$`),
          null
        )
      }
}
