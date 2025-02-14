package loci
package transmitter

import scala.annotation.compileTimeOnly
import scala.concurrent.Future
import scala.language.experimental.macros
import scala.reflect.macros.whitebox

trait TransmittableDummy {
  this: Transmittable.type =>

  @compileTimeOnly("Value is not transmittable")
  final implicit def resolutionFailure[T](implicit ev: DummyImplicit): Transmittable[T, T, T] {
    type Proxy = Future[T]
    type Transmittables = Transmittables.None
  } = macro TransmittableResolutionFailure[T]

  @compileTimeOnly("Value is not transmittable")
  final def dummy[T]: Transmittable[T, T, T] {
    type Proxy = Future[T]
    type Transmittables = Transmittables.None
  } = IdenticallyTransmittable()
}

object TransmittableResolutionFailure {
  def apply[T: c.WeakTypeTag](c: whitebox.Context)(ev: c.Tree): c.Tree = {
    import c.universe._

    // the current macro expansion always appears twice
    // see: http://stackoverflow.com/a/20466423
    val recursionCount = c.openMacros.count { other =>
      c.enclosingPosition == other.enclosingPosition &&
      c.macroApplication.toString == other.macroApplication.toString
    }
    if (recursionCount > 2)
      c.abort(c.enclosingPosition, "Skipping transmittable resolution failure macro for recursive invocation")

    val resolutionType = weakTypeOf[Transmittable.Aux.Resolution[T, _, _, _, _]]

    if ((c inferImplicitValue resolutionType).nonEmpty)
      c.abort(c.enclosingPosition, "Skipping transmittable resolution failure macro to prioritize other implicit")

    val tpe = weakTypeOf[T]
    val message = s"$tpe is not transmittable"

    q"""{
      @${termNames.ROOTPKG}.scala.annotation.compileTimeOnly($message) def resolutionFailure() = ()
      resolutionFailure()
      ${termNames.ROOTPKG}.loci.transmitter.Transmittable.dummy[$tpe]
    }"""
  }
}
