package loci
package serializer

import _root_.upickle.default._
import transmitter.Serializable
import scala.util.Try

object upickle {
  implicit def upickleBasedSerializable[T]
      (implicit reader: Reader[T], writer: Writer[T]) = new Serializable[T] {
    def serialize(value: T) =
      MessageBuffer fromString write(value)(writer)
    def deserialize(value: MessageBuffer) =
      Try { read(value toString (0, value.length))(reader) }
  }
}
