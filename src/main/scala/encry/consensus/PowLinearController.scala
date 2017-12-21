package encry.consensus

import encry.modifiers.history.block.header.EncryBlockHeader
import encry.settings.ConsensusSettings
import encry.view.history.Height

import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.concurrent.duration.FiniteDuration

object PowLinearController {

  def getTarget(difficulty: Difficulty): BigInt =
    ConsensusSettings.maxTarget / difficulty

  // Retargeting to adjust difficulty.
  def getNewTarget(oldTarget: BigInt, lastEpochsIntervalMs: FiniteDuration): BigInt =
    oldTarget * lastEpochsIntervalMs.toMillis / ConsensusSettings.desiredEpochIntervalSec.toMillis *
      ConsensusSettings.retargetingEpochsQty

  def getNewDifficulty(oldDifficulty: Difficulty, lastEpochsIntervalMs: FiniteDuration): Difficulty =
    Difficulty @@ (ConsensusSettings.maxTarget / getNewTarget(getTarget(oldDifficulty), lastEpochsIntervalMs))

  // Used to provide `getLastEpochsInterval()` with the sequence of headers of right heights.
  def epochsHeightsForRetargetingAt(height: Height): Seq[Height] = {
    if ((height - 1) > ConsensusSettings.retargetingEpochsQty)
      (0 until ConsensusSettings.retargetingEpochsQty).map(i => (height - 1) - i).reverse.map(i => Height @@ i)
    else
      (0 until height)
        .map(i => (height - 1) - i).filter(i => i > 1).reverse.map(i => Height @@ i)
  }

  def getLastEpochsInterval(headers: Seq[EncryBlockHeader]): FiniteDuration = {
    val start = headers.head.timestamp
    val end = headers.last.timestamp
    FiniteDuration(end - start, MILLISECONDS)
  }
}