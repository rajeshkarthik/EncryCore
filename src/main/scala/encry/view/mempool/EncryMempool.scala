package encry.view.mempool

import encry.modifiers.mempool.EncryBaseTransaction
import encry.settings.EncryAppSettings
import encry.view.mempool.EncryMempool._
import monix.eval.Task
import monix.execution.Scheduler
import scorex.core.ModifierId
import scorex.core.transaction.MemoryPool
import scorex.core.utils.{NetworkTimeProvider, ScorexLogging}

import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Success, Try}

class EncryMempool private[mempool](val unconfirmed: TrieMap[TxKey, EncryBaseTransaction],
                                    settings: EncryAppSettings, timeProvider: NetworkTimeProvider)
  extends MemoryPool[EncryBaseTransaction, EncryMempool] with EncryMempoolReader with AutoCloseable with ScorexLogging {

  private implicit val cleanupScheduler: Scheduler = Scheduler.singleThread("mempool-cleanup-thread")

  private val removeExpired = Task {
    filter(tx => (timeProvider.time() - tx.timestamp) > settings.nodeSettings.utxMaxAge.toMillis)
  }.delayExecution(settings.nodeSettings.mempoolCleanupInterval)

  private val cleanup = removeExpired.runAsync

  override def close(): Unit = cleanup.cancel()

  override type NVCT = EncryMempool

  override def put(tx: EncryBaseTransaction): Try[EncryMempool] = put(Seq(tx))

  override def put(txs: Iterable[EncryBaseTransaction]): Try[EncryMempool] = {
    val validTxs = txs.filter(tx => tx.semanticValidity.isSuccess && !unconfirmed.contains(key(tx.id)))
    if (validTxs.nonEmpty) {
      if ((size + validTxs.size) <= settings.nodeSettings.mempoolMaxCapacity) {
        Success(putWithoutCheck(validTxs))
      } else {
        val overflow = (size + validTxs.size) - settings.nodeSettings.mempoolMaxCapacity
        Success(putWithoutCheck(validTxs.take(validTxs.size - overflow)))
      }
    } else Failure(new Error("Failed to put transaction into pool"))
  }

  override def putWithoutCheck(txs: Iterable[EncryBaseTransaction]): EncryMempool = {
    txs.foreach(tx => unconfirmed.put(key(tx.id), tx))
    completeAssembly(txs)
    this
  }

  override def remove(tx: EncryBaseTransaction): EncryMempool = {
    unconfirmed.remove(key(tx.id))
    this
  }

  def removeAsync(txs: Seq[EncryBaseTransaction]): Unit = Task {
    txs.foreach(remove)
  }.runAsync

  override def take(limit: Int): Iterable[EncryBaseTransaction] = unconfirmed.values.toSeq.take(limit)

  def takeAll: Iterable[EncryBaseTransaction] = unconfirmed.values.toSeq

  override def filter(condition: (EncryBaseTransaction) => Boolean): EncryMempool = {
    unconfirmed.retain { (_, v) =>
      condition(v)
    }
    this
  }
}

object EncryMempool {

  type TxKey = scala.collection.mutable.WrappedArray.ofByte

  type MemPoolRequest = Seq[ModifierId]

  type MemPoolResponse = Seq[EncryBaseTransaction]

  def empty(settings: EncryAppSettings, timeProvider: NetworkTimeProvider): EncryMempool =
    new EncryMempool(TrieMap.empty, settings, timeProvider)
}
