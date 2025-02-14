package loci
package runtime

import java.util.concurrent.atomic.AtomicLong

import loci.communicator.{Connection, Connector, Listener}
import loci.messaging.{ConnectionsBase, Message}
import loci.transmitter.RemoteAccessException

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

class RemoteConnections(peer: Peer.Signature, ties: Map[Peer.Signature, Peer.Tie])
  extends ConnectionsBase[Remote.Reference, Message[Method]] {

  protected def deserializeMessage(message: MessageBuffer) =
    Message.deserialize(message)

  protected def serializeMessage(message: Message[Method]) =
    Message.serialize(message)

  private def violatedException =
    new RemoteAccessException("tie constraints violated")

  private def messageException(message: Message[Method]) =
    new Message.Exception(s"unexpected connect message: $message")

  private val multiplicities =
    (ties.keys flatMap { _.bases } map { _ -> Peer.Tie.Multiple }).toMap ++ ties

  protected class State extends BaseState {
    private val counter = new AtomicLong(1)
    def createId() = counter.getAndIncrement
    val potentials = new ListBuffer[Peer.Signature]
  }

  protected val state = new State

  private val doConstraintsSatisfied = Notifier[Unit]

  private val doConstraintsViolated = Notifier[RemoteAccessException]

  def constraintsSatisfied: Notification[Unit] = doConstraintsSatisfied.notification

  def constraintsViolated: Notification[RemoteAccessException] = doConstraintsViolated.notification

  def connect(
      connector: Connector[ConnectionsBase.Protocol],
      remotePeer: Peer.Signature): Future[Remote.Reference] = {
    val promise = Promise[Remote.Reference]
    connectWithCallback(connector, remotePeer) { promise complete _ }
    promise.future
  }

  def connectWithCallback(
      connector: Connector[ConnectionsBase.Protocol],
      remotePeer: Peer.Signature)(
      handler: Try[Remote.Reference] => Unit): Unit = sync {
    if (!isTerminated) {
      if (constraintViolationsConnecting(remotePeer).isEmpty) {
        state.potentials += remotePeer

        connector.connect() {
          case Success(connection) =>
            val remote = Remote.Reference(
              state.createId(), remotePeer)(
              connection.protocol, this)

            var closedHandler: Notifiable[_] = null
            var receiveHandler: Notifiable[_] = null

            closedHandler = connection.closed notify { _ =>
              handler(Failure(terminatedException))
            }

            receiveHandler = connection.receive notify { data =>
              sync {
                state.potentials -= remotePeer

                if (receiveHandler != null)
                  receiveHandler.remove()
                if (closedHandler != null)
                  closedHandler.remove()

                val handleAccept =
                  handleAcceptMessage(connection, remote)
                val handleRequest =
                  handleRequestMessage(connection, remotePeer) andThen {
                    _ map { case (remote, _) => remote }
                  }

                val result = deserializeMessage(data) flatMap {
                  handleAccept orElse handleRequest orElse
                  handleUnknownMessage
                }

                if (result.isFailure)
                  connection.close()

                afterSync { handler(result) }
              }
            }

            connection send serializeMessage(
              RequestMessage(
                Peer.Signature.serialize(remotePeer),
                Peer.Signature.serialize(peer)))

          case Failure(exception) =>
            handler(Failure(exception))
        }
      }
      else
        handler(Failure(violatedException))
    }
    else
      handler(Failure(terminatedException))
  }

  def listen(
      listener: Listener[ConnectionsBase.Protocol],
      remotePeer: Peer.Signature,
      createDesignatedInstance: Boolean = false): Unit =
    listenWithCallback(listener, remotePeer, createDesignatedInstance) { _ => }

  def listenWithCallback(
      listener: Listener[ConnectionsBase.Protocol],
      remotePeer: Peer.Signature,
      createDesignatedInstance: Boolean = false)(
      handler: Try[(Remote.Reference, RemoteConnections)] => Unit): Try[Unit] =
    sync {
      if (!isTerminated) {
        val listening = listener.startListening() {
          case Success(connection) =>
            var receiveHandler: Notifiable[_] = null

            receiveHandler = connection.receive notify { data =>
              if (receiveHandler != null)
                receiveHandler.remove()

              val handleRequest = handleRequestMessage(
                connection, remotePeer, createDesignatedInstance)

              val result = deserializeMessage(data) flatMap {
                handleRequest orElse handleUnknownMessage
              }

              if (result.isFailure)
                connection.close()

              handler(result)
            }

          case Failure(exception) =>
            handler(Failure(exception))
        }

        listening foreach addListening

        listening map { _ => () }
      }
      else
        Failure(terminatedException)
    }

  private def handleRequestMessage(
      connection: Connection[ConnectionsBase.Protocol],
      remotePeer: Peer.Signature,
      createDesignatedInstance: Boolean = false)
  : PartialFunction[Message[Method], Try[(Remote.Reference, RemoteConnections)]] = {
    case RequestMessage(requested, requesting) =>
      sync {
        if (!isTerminated)
          Peer.Signature.deserialize(requested) flatMap { requestedPeer =>
            Peer.Signature.deserialize(requesting) flatMap { requestingPeer =>
              if (peer <= requestedPeer &&
                  requestingPeer <= remotePeer &&
                  constraintViolationsConnecting(remotePeer).isEmpty) {
                val instance =
                  if (!createDesignatedInstance) this
                  else new RemoteConnections(peer, ties)

                val remote = Remote.Reference(
                  instance.state.createId(), remotePeer)(
                  connection.protocol, this)

                connection send serializeMessage(AcceptMessage())

                val result = instance.addConnection(remote, connection)

                result map { _ => (remote, instance) }
              }
              else
                Failure(violatedException)
            }
          }
        else
          Failure(terminatedException)
      }
  }

  private def handleAcceptMessage(
      connection: Connection[ConnectionsBase.Protocol],
      remote: Remote.Reference)
  : PartialFunction[Message[Method], Try[Remote.Reference]] = {
    case AcceptMessage() =>
      sync {
        if (!isTerminated)
          addConnection(remote, connection) map { _ => remote }
        else
          Failure(terminatedException)
      }
  }

  private val handleUnknownMessage
    : PartialFunction[Message[Method], Try[Nothing]] = {
      case message => Failure(messageException(message))
    }

  override protected def addConnection(
      remote: Remote.Reference,
      connection: Connection[ConnectionsBase.Protocol]) =
    sync {
      handleConstraintChanges {
        super.addConnection(remote, connection)
      }
    }

  override protected def removeConnection(remote: Remote.Reference) =
    sync {
      handleConstraintChanges {
        super.removeConnection(remote)
      }
    }

  private def handleConstraintChanges[T](changeConnection: => T): T =
    if (!state.isTerminated) {
      val constraintsSatisfiedBefore = constraintViolations.isEmpty
      val result = changeConnection
      val constraintsSatisfiedAfter = constraintViolations.isEmpty

      if (!constraintsSatisfiedBefore && constraintsSatisfiedAfter)
        afterSync { doConstraintsSatisfied() }
      if (constraintsSatisfiedBefore && !constraintsSatisfiedAfter)
        afterSync { doConstraintsViolated(violatedException) }

      result
    }
    else
      changeConnection

  def constraintViolationsConnecting(peer: Peer.Signature): Option[Peer.Signature] = {
    val peerCounts = connections(includePotentials = true) count { _ == peer }

    if (!checkConstraints(peer, 1 + peerCounts))
      Some(peer)
    else
      None
  }

  def constraintViolations: Set[Peer.Signature] = {
    val peerCounts =
      (multiplicities map { case (peer, _) => (peer, 0) }) ++
      (connections(includePotentials = false) groupBy identity map {
        case (peer, list) => (peer, list.size)
      })

    (peerCounts collect { case (peer, count)
      if !checkConstraints(peer, count) => peer
    }).toSet
  }

  private def connections(includePotentials: Boolean): Seq[Peer.Signature] = {
    val remotePeers = remotes map { _.signature }
    val potentialPeers =
      if (includePotentials) synchronized { state.potentials }
      else Seq.empty

    (remotePeers ++ potentialPeers) flatMap { _.bases }
  }

  private def checkConstraints(peer: Peer.Signature, count: Int): Boolean =
    peer.bases collect (Function unlift { peer =>
      multiplicities get peer map {
        case Peer.Tie.Multiple => true
        case Peer.Tie.Optional => count <= 1
        case Peer.Tie.Single => count == 1
      }
    }) reduceOption { _ && _ } getOrElse false
}

