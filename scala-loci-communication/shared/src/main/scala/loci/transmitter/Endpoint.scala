package loci
package transmitter

trait Endpoint[T, U] {
  val abstraction: AbstractionRef
  val closed: Notification[Unit] = abstraction.channel.closed
  def close(): Unit = abstraction.channel.close
  def send(value: T): Unit
  val receive: Notification[U]
}
