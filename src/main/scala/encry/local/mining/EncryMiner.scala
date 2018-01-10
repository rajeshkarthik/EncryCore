package encry.local.mining

import akka.actor.{Actor, ActorRef}
import akka.pattern._
import akka.util.Timeout
import encry.consensus.{Difficulty, PowCandidateBlock, PowConsensus}
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.mempool.{CoinbaseTransaction, EncryBaseTransaction}
import encry.settings.EncryAppSettings
import encry.view.history.EncryHistory
import encry.view.mempool.EncryMempool
import encry.view.state.UtxoState
import encry.view.wallet.EncryWallet
import io.circe.Json
import io.circe.syntax._
import scorex.core.LocalInterface.LocallyGeneratedModifier
import scorex.core.NodeViewHolder
import scorex.core.NodeViewHolder.{GetDataFromCurrentView, SemanticallySuccessfulModifier, Subscribe}
import scorex.core.transaction.state.{PrivateKey25519, PrivateKey25519Companion}
import scorex.core.utils.{NetworkTime, ScorexLogging}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

class EncryMiner(viewHolderRef: ActorRef, settings: EncryAppSettings, nodeId: Array[Byte])
  extends Actor with ScorexLogging {

  import EncryMiner._

  private val consensus = new PowConsensus(settings.chainSettings)

//  private var cancellableOpt: Option[Cancellable] = None
  private var isMining = false
  private val startTime = NetworkTime.time()
  private var nonce = 0
  private var candidateOpt: Option[PowCandidateBlock] = None

  override def preStart(): Unit = {
    viewHolderRef ! Subscribe(Seq(NodeViewHolder.EventType.SuccessfulSemanticallyValidModifier))
  }

  override def receive: Receive = {

    case SemanticallySuccessfulModifier(mod) =>
      if (isMining) {
        mod match {
          case block: EncryBlock =>
            if (!candidateOpt.flatMap(_.parentOpt).exists(_.id sameElements block.header.id))
              prepareCandidate(viewHolderRef, settings, nodeId)

          case _ =>
        }
      } else if (settings.nodeSettings.mining) {
        mod match {
          case block: EncryBlock if block.header.timestamp >= startTime =>
            self ! StartMining

          case _ =>
        }
      }

    case StartMining =>
      if (!isMining && settings.nodeSettings.mining) {
        log.info("Starting Mining")
        isMining = true
        prepareCandidate(viewHolderRef, settings, nodeId)
        self ! MineBlock
      }

    case StopMining =>
      isMining = false

    case MineBlock =>
      nonce = nonce + 1
      candidateOpt match {
        case Some(candidate) =>
          consensus.verifyCandidate(candidate, nonce) match {
            case Some(block) =>
              log.info(s"New block found: $block")

              viewHolderRef ! LocallyGeneratedModifier(block.header)
              viewHolderRef ! LocallyGeneratedModifier(block.payload)
              block.adProofsOpt.foreach { adp =>
                viewHolderRef ! LocallyGeneratedModifier(adp)
              }
              context.system.scheduler.scheduleOnce(settings.nodeSettings.miningDelay)(self ! MineBlock)
            case None =>
              if (isMining) self ! MineBlock
          }
        case None =>
          context.system.scheduler.scheduleOnce(1.second)(self ! MineBlock)
      }

    case MiningStatusRequest =>
      sender ! MiningStatusResponse(isMining, candidateOpt)
  }
}

object EncryMiner extends ScorexLogging {

  case object StartMining

  case object StopMining

  case object MineBlock

  case object MiningStatusRequest

  case class MiningStatusResponse(isMining: Boolean, candidateBlock: Option[PowCandidateBlock]) {
    lazy val json: Json = Map(
      "isMining" -> isMining.asJson,
      "candidateBlock" -> candidateBlock.map(_.json).getOrElse("None".asJson)
    ).asJson
  }

  def prepareCandidate(viewHolderRef: ActorRef, settings: EncryAppSettings,
                       nodeId: Array[Byte]): Future[Option[PowCandidateBlock]] = {
    implicit val timeout: Timeout = Timeout(settings.scorexSettings.restApi.timeout)
    (viewHolderRef ?
      GetDataFromCurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool, Option[PowCandidateBlock]] { view =>
        val bestHeaderOpt = view.history.bestFullBlockOpt.map(_.header)

        if (bestHeaderOpt.isDefined || settings.nodeSettings.offlineGeneration) Try {

          val timestamp = NetworkTime.time()

          var txs = view.state.filterValid(view.pool.takeAllUnordered.toSeq)
            .foldLeft(Seq[EncryBaseTransaction]()) { case (txsBuff, tx) =>
              // TODO: Correct right operand on CoinbaseTx.length here.
              if ((txsBuff.map(_.length).sum + tx.length) <= settings.chainSettings.blockMaxSize) txsBuff :+ tx
              txsBuff
            }

          // TODO: Which PubK should we pick here?
          val minerProposition = view.vault.publicKeys.head
          val privateKey: PrivateKey25519 = view.vault.secretByPublicImage(minerProposition).get
          val openBxs = txs.map(tx => tx.newBoxes.head).toIndexedSeq
          val amount = openBxs.map(_.value).sum
          val signature = PrivateKey25519Companion.sign(privateKey,
            CoinbaseTransaction.getHash(minerProposition, openBxs.map(_.id), timestamp, amount))

          val coinbase = CoinbaseTransaction(minerProposition, timestamp, signature, openBxs.map(_.id), amount)

          txs = txs.sortBy(_.timestamp) :+ coinbase

          val (adProof, adDigest) = view.state.proofsForTransactions(txs).get
          val difficulty = bestHeaderOpt.map(parent => view.history.requiredDifficultyAfter(parent))
            .getOrElse(Difficulty @@ settings.chainSettings.initialDifficulty)

          val candidate = new PowCandidateBlock(bestHeaderOpt, adProof, adDigest, txs, timestamp, difficulty)
          log.debug(s"Sending candidate block with ${candidate.transactions.length} transactions")

          candidate
        }.toOption
        else
          None
    }).mapTo[Option[PowCandidateBlock]]
  }
}
