package encry.modifiers.state.box

import com.google.common.primitives.{Bytes, Longs}
import encry.account.{Account, Address}
import encry.modifiers.state.box.EncryBox.BxTypeId
import encry.modifiers.state.box.proof.Proof
import encry.modifiers.state.box.proposition.{AccountProposition, AccountPropositionSerializer}
import encry.modifiers.state.box.serializers.SizedCompanionSerializer
import io.circe.Json
import io.circe.syntax._
import scorex.core.transaction.box.Box.Amount
import scorex.crypto.encode.Base58

import scala.util.{Failure, Success, Try}

case class AssetBox(override val proposition: AccountProposition,
                    override val nonce: Long,
                    override val amount: Amount)
  extends EncryBox[AccountProposition] with AmountCarryingBox {

  override type M = AssetBox

  override val typeId: BxTypeId = AssetBox.TypeId

  override def unlockTry(proof: Proof)(implicit ctx: Context): Try[Unit] =
    if (Account(ctx.transaction.accountPubKey.pubKeyBytes) != proposition.account) Failure(new Error("Unlock failed"))
    else Success()

  override def serializer: SizedCompanionSerializer[M] = AssetBoxSerializer

  override def json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "proposition" -> proposition.account.address.toString.asJson,
    "nonce" -> nonce.asJson,
    "value" -> amount.asJson
  ).asJson
}

object AssetBox {

  val TypeId: BxTypeId = 1.toByte

  def apply(address: Address, nonce: Long, amount: Amount): AssetBox =
    AssetBox(AccountProposition(address), nonce, amount)

  def apply(account: Account, nonce: Long, amount: Amount): AssetBox =
    AssetBox(AccountProposition(account), nonce, amount)
}

object AssetBoxSerializer extends SizedCompanionSerializer[AssetBox] {

  import Account._

  val Size: Int = AddressLength + 16

  override def toBytes(obj: AssetBox): Array[Byte] = {
    Bytes.concat(
      obj.proposition.bytes,
      Longs.toByteArray(obj.nonce),
      Longs.toByteArray(obj.amount)
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[AssetBox] = Try {
    val accountPropositionLen = AccountPropositionSerializer.Length
    val proposition = AccountPropositionSerializer.parseBytes(bytes.take(accountPropositionLen)).get
    val nonce = Longs.fromByteArray(bytes.slice(accountPropositionLen, accountPropositionLen + 8))
    val amount = Longs.fromByteArray(bytes.slice(accountPropositionLen + 8, accountPropositionLen + 16))
    AssetBox(proposition, nonce, amount)
  }
}
