package loci
package transmitter

import loci.contexts.Immediate.Implicits.global

import scala.util.Try
import scala.annotation.implicitNotFound
import scala.concurrent.Future

@implicitNotFound("${B} is not marshallable")
trait Marshallable[B, R, P] {
  def marshal(value: B, abstraction: AbstractionRef): MessageBuffer
  def unmarshal(value: MessageBuffer, abstraction: AbstractionRef): Try[R]
  def unmarshal(value: Future[MessageBuffer], abstraction: AbstractionRef): P

  type Type = Marshallable[B, R, P]

  @inline final def self: Type = this

  def connected: Boolean
}

object Marshallable {
  @inline def apply[T](implicit marshallable: Marshallable[T, _, _])
  : marshallable.Type = marshallable.self

  @inline def Argument[T](implicit marshallable: Marshallable[T, T, _])
  : marshallable.Type = marshallable.self

  implicit def marshallable[B, I, R, P, T <: Transmittables](implicit
      resolution: Transmittable.Aux.Resolution[B, I, R, P, T],
      serializer: Serializable[I],
      contextBuilder: ContextBuilder[T]): Marshallable[B, R, P] =
    new Marshallable[B, R, P] {
      val transmittable = resolution.transmittable

      def connected = (transmittable: Transmittable[B, I, R]) match {
        case _: ConnectedTransmittable[_, _, _] => true
        case _: ConnectedTransmittable.Proxy[_, _, _] => true
        case _ => false
      }

      def marshal(value: B, abstraction: AbstractionRef) = {
        implicit val context = contextBuilder(
          transmittable.transmittables, abstraction, ContextBuilder.sending)
        serializer serialize (transmittable buildIntermediate value)
      }

      def unmarshal(value: MessageBuffer, abstraction: AbstractionRef) = {
        implicit val context = contextBuilder(
          transmittable.transmittables, abstraction, ContextBuilder.receiving)
        serializer deserialize value map transmittable.buildResult
      }

      def unmarshal(value: Future[MessageBuffer], abstraction: AbstractionRef) = {
        implicit val context = contextBuilder(
          transmittable.transmittables, abstraction, ContextBuilder.receiving)
        transmittable buildProxy (
          value transform (v => (serializer deserialize v).get, identity))
      }
    }
}
