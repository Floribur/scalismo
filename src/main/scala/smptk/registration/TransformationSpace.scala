package smptk
package registration

import scala.language.higherKinds
import TransformationSpace.ParameterVector
import breeze.linalg.DenseVector
import breeze.linalg._
import breeze.linalg.DenseMatrix
import image._
import smptk.image.Geometry._


trait ValueCaching[CV[A] <: CoordVector[A]] extends (CV[Double] => CV[Double])  {
  
  val cache =  scala.collection.mutable.HashMap.empty[CV[Double], CV[Double]]

  abstract override def apply(p:CV[Double]): CV[Double] = {
    cache.getOrElseUpdate(p,  super.apply(p))
  } 
} 


trait TransformationSpaceConfiguration {
  val withValueCaching : Boolean
}

trait TransformationSpace[CV[A] <: CoordVector[A]] extends Function1[ParameterVector, Transformation[CV]] {
  self: TransformationSpace[CV] =>

  type JacobianImage = Function1[CV[Double], DenseMatrix[Double]]
  def parametersDimensionality: Int
  def takeDerivativeWRTParameters(alpha: ParameterVector): JacobianImage

  def product(that: TransformationSpace[CV]): TransformationSpace[CV] = {
    new ProductTransformationSpace(self, that)
  }

  def inverseTransform(p: ParameterVector): Option[Transformation[CV]]
  def identityTransformParameters: DenseVector[Double]
}

trait Transformation[CV[A] <: CoordVector[A]] extends (CV[Double] => CV[Double]) {
  def takeDerivative(x: CV[Double]): DenseMatrix[Double]
}


class ProductTransformationSpace[CV[A] <: CoordVector[A], OuterType <: TransformationSpace[CV], InnerType <: TransformationSpace[CV]](outer: OuterType, inner: InnerType) extends TransformationSpace[CV] {

  def parametersDimensionality = outer.parametersDimensionality + inner.parametersDimensionality
  def identityTransformParameters = DenseVector.vertcat(outer.identityTransformParameters, inner.identityTransformParameters)

  def apply(p: ParameterVector) = {

    new Transformation[CV] {
      def apply(x: CV[Double]) = {
        val (pThis, pThat) = splitProductParameterVector(p)
        val outerTransform = outer(pThis)
        val innerTransfrom = inner(pThat)
        (outerTransform compose innerTransfrom)(x)
      }
      def takeDerivative(x: CV[Double]) = {
        val (pThis, pThat) = splitProductParameterVector(p)
        val outerTransform = outer(pThis)
        val innerTransform = inner(pThat)
        outerTransform.takeDerivative(innerTransform(x)) * innerTransform.takeDerivative(x)
      }
    }
  }

  def inverseTransform(p: ParameterVector): Option[Transformation[CV]] = {
    val (pOuter, pInner) = splitProductParameterVector(p)

    for {
      outerInverse <- outer.inverseTransform(pOuter);
      innerInverse <- inner.inverseTransform(pInner)
    } yield {
      new Transformation[CV] {
        def apply(x: CV[Double]) = (innerInverse compose outerInverse)(x)
        def takeDerivative(x: CV[Double]) = {
          innerInverse.takeDerivative(outerInverse(x)) * outerInverse.takeDerivative(x)
        }
      }
    }

  }

  def takeDerivativeWRTParameters(p: ParameterVector) = {

    val split = splitProductParameterVector(p)

    (x: CV[Double]) => {
      DenseMatrix.horzcat(
        outer.takeDerivativeWRTParameters(split._1)(x),
        outer(split._1).takeDerivative(inner(split._2)(x)) * inner.takeDerivativeWRTParameters(split._2)(x))
    }
  }

  private def splitProductParameterVector(p: ParameterVector): (ParameterVector, ParameterVector) = {
    val pThis = p.slice(0, outer.parametersDimensionality, 1)
    val pThat = p.slice(outer.parametersDimensionality, p.length, 1)
    (pThis, pThat)
  }

}

case class TranslationSpace1D extends TransformationSpace[CoordVector1D] {
  
  override def identityTransformParameters = DenseVector(0.)
  
  def apply(p: ParameterVector) = {
    new Transformation[CoordVector1D] {

      def apply(pt: Point1D) = CoordVector1D(p(0) + pt(0))
      def takeDerivative(x: Point1D) = {
        DenseMatrix.eye[Double](1)
      }
    }
  }
  def inverseTransform(p: ParameterVector) = {
    Some(TranslationSpace1D()(-p))
  }

  def parametersDimensionality: Int = 1
  def takeDerivativeWRTParameters(p: ParameterVector) = { x: Point1D =>
    DenseMatrix.eye[Double](1)
  }
}

case class TranslationSpace2D extends TransformationSpace[CoordVector2D] {

  
  def parametersDimensionality: Int = 2
  override def identityTransformParameters = DenseVector(0., 0.)
  
  def apply(p: ParameterVector) = {
    new Transformation[CoordVector2D] {
      def apply(pt: Point2D) = CoordVector2D(p(0) + pt(0), p(1) + pt(1))
      def takeDerivative(x: Point2D) = {
        DenseMatrix.eye[Double](2)
      }
    }
  }
  def inverseTransform(p: ParameterVector) = {
    Some(TranslationSpace2D()(-p))
  }

  def takeDerivativeWRTParameters(p: ParameterVector) = { x: Point2D =>
    DenseMatrix.eye[Double](2)
  }
}

case class TranslationSpace3D extends TransformationSpace[CoordVector3D] {
  
  def parametersDimensionality: Int = 3
  override def identityTransformParameters = DenseVector(0., 0., 0.)
  
  def apply(p: ParameterVector) = {
    new Transformation[CoordVector3D] {
      def apply(pt: Point3D) = CoordVector3D(p(0) + pt(0), p(1) + pt(1), p(2) + pt(2))
      def takeDerivative(x: Point3D) = {
        DenseMatrix.eye[Double](3)
      }
    }
  }
  def inverseTransform(p: ParameterVector) = {
    Some(TranslationSpace3D()(-p))
  }

  def takeDerivativeWRTParameters(p: ParameterVector) = { x: Point3D =>
    DenseMatrix.eye[Double](3)
  }
}


case class RotationSpace3D(val centre: CoordVector3D[Double]) extends TransformationSpace[CoordVector3D] {

  def parametersDimensionality: Int = 3 //  Euler angles 
  override def identityTransformParameters = DenseVector(0., 0., 0.)

  def rotationParametersToParameterVector(phi: Double, theta: Double, psi: Double): ParameterVector = {
    DenseVector(phi, theta, psi)
  }
  def apply(p: ParameterVector) = {
    require(p.length == 3) 
    //
    // rotation matrix according to the "x-convention" where Phi is rotation over x-axis, theta over y, and psi over z
    val cospsi = Math.cos(p(2))
    val sinpsi = Math.sin(p(2))
   
    val costh = Math.cos(p(1))
    val sinth = Math.sin(p(1))
    
    val cosphi = Math.cos(p(0))
    val sinphi = Math.sin(p(0))
    
    val rotMatrix = DenseMatrix(
        (  costh*cosphi , sinpsi*sinth*cosphi-cospsi*sinphi , sinpsi*sinphi+cospsi*sinth*cosphi ),
        (  costh*sinphi , cospsi*cosphi+sinpsi*sinth*sinphi , cospsi*sinth*sinphi-sinpsi*cosphi ),
        ( -sinth, sinpsi*costh, cospsi*costh)  )
        
        
 //   println("Rotation matrix in transform "+rotMatrix)     
        
    new Transformation[CoordVector3D] {
      def apply(pt: Point3D) = {

        val rotCentered = rotMatrix * DenseVector(pt(0) - centre(0), pt(1) - centre(1),  pt(2) - centre(2))
        CoordVector3D((rotCentered(0) + centre(0)), (rotCentered(1) + centre(1)), (rotCentered(2) + centre(2)))

      }
      def takeDerivative(x: Point3D) = {
        rotMatrix
      }
    }
  }

  def inverseTransform(p: ParameterVector) = {
    Some(RotationSpace3D(centre)(-p))
  }

  def takeDerivativeWRTParameters(p: ParameterVector) = { x: Point3D =>
 
    val cospsi = Math.cos(p(2))
    val sinpsi = Math.sin(p(2)) 
    val costh = Math.cos(p(1))
    val sinth = Math.sin(p(1))
    val cosphi = Math.cos(p(0))
    val sinphi = Math.sin(p(0))
    
    val x0minc0 = x(0)-centre(0)
    val x1minc1 = x(1)-centre(1)
    val x2minc2 = x(2)-centre(2)

    // 3 by 3 matrix (nbrows=point dim, nb cols = param dim )
    val dr00 = (-sinphi * costh *x0minc0)+(-sinphi*sinpsi*sinth - cospsi*cosphi)*x1minc1 + (sinpsi*cosphi-cospsi*sinth*sinphi)*x2minc2
    val dr01 = -sinth*cosphi*x0minc0 + costh*sinpsi*cosphi*x1minc1 + cospsi*costh*cosphi*x2minc2
    val dr02 = (cospsi*sinth*cosphi + sinpsi*sinphi) *x1minc1 + (cospsi*sinphi-sinpsi*sinth*cosphi)*x2minc2
    
    val dr10 = costh*cosphi*x0minc0 + (-sinphi*cospsi + sinpsi*sinth*cosphi)*x1minc1 + (cospsi*sinth*cosphi+sinpsi*sinphi)*x2minc2
    val dr11 = -sinth*sinphi*x0minc0 + sinpsi*costh*sinphi*x1minc1 + cospsi*costh*sinphi*x2minc2
    val dr12 = (-sinpsi*cosphi+cospsi*sinth*sinphi)*x1minc1 + (-sinpsi*sinth*sinphi-cospsi*cosphi)*x2minc2
    
    val dr20 = 0.
    val dr21 = -costh*x0minc0-sinpsi*sinth*x1minc1-cospsi*sinth*x2minc2
    val dr22 = cospsi*costh*x1minc1 -sinpsi*costh*x2minc2
    
//    val dr00 = (cospsi*sinth*cosphi+sinpsi*sinphi)*x1minc1+(cospsi*sinphi-sinpsi*sinth*cosphi)*x2minc2
//    val dr01 = (-sinth*cosphi*x0minc0 + costh*cosphi*sinpsi*x1minc1 + cospsi*costh*cosphi* x2minc2)
//    val dr02 = (-sinphi*costh*x0minc0)+ (-sinphi*sinth*sinpsi - cospsi*cosphi) * x1minc1 + (cosphi*sinpsi-sinphi*sinth*cospsi)* x2minc2
//    val dr10 = (-sinpsi*cosphi+cospsi*sinth*sinphi)*x1minc1+ (-sinpsi*sinth*sinphi-cospsi*cosphi)*x2minc2
//    val dr11 = (-sinth*sinphi*x0minc0 + costh*sinpsi*sinphi*x1minc1+cospsi*costh*sinphi*x2minc2)
//    val dr12 = costh*cosphi*x0minc0+(-sinphi*cospsi+cosphi*sinpsi*sinth)*x1minc1+(cosphi*sinth*cospsi+sinpsi*sinphi)*x2minc2
//    val dr20 = cospsi*costh*x1minc1
//    val dr21 = -costh*x0minc0 + -sinth*sinpsi*x1minc1 + (-sinth*cospsi)*x2minc2
//    val dr22 = 0.
    
    DenseMatrix(
      (dr00, dr01, dr02),        
      (dr10 , dr11, dr12),     
      (dr20  , dr21 ,dr22)
    )
  }
}

case class RotationSpace2D(val centre: CoordVector2D[Double]) extends TransformationSpace[CoordVector2D] {

  def parametersDimensionality: Int = 1 //  angle
override def identityTransformParameters = DenseVector(0.)	
  
  def rotationParametersToParameterVector(phi: Double): ParameterVector = {
    DenseVector(phi)
  }
  def apply(p: ParameterVector) = {
    require(p.length == 1)

    val rotMatrix = DenseMatrix((math.cos(p(0)), -math.sin(p(0))), (math.sin(p(0)), math.cos(p(0))))

    new Transformation[CoordVector2D] {
      def apply(pt: Point2D) = {

        val rotCentered = rotMatrix * DenseVector(pt(0) - centre(0), pt(1) - centre(1))
        CoordVector2D((rotCentered(0) + centre(0)), (rotCentered(1) + centre(1)))

      }
      def takeDerivative(x: Point2D) = {
        rotMatrix
      }
    }
  }

  def inverseTransform(p: ParameterVector) = {
    Some(RotationSpace2D(centre)(-p))
  }

  def takeDerivativeWRTParameters(p: ParameterVector) = { x: Point2D =>
    val sa = math.sin(p(0))
    val ca = math.cos(p(0))
    val cx = centre(0)
    val cy = centre(1)

    DenseMatrix(
      (-sa * (x(0) - cx) - ca * (x(1) - cy)),
      (ca * (x(0) - cx) - sa * (x(1) - cy)))
  }
}

case class ScalingSpace3D() extends TransformationSpace[CoordVector3D] {

  def parametersDimensionality: Int = 1
    override def identityTransformParameters = DenseVector(1.)
  def apply(p: ParameterVector) = {
    require(p.length == 1)

    new Transformation[CoordVector3D] {
      def apply(x: Point3D) = {
        CoordVector3D(x(0) * p(0), x(1) * p(0), x(2) * p(2))
      }

      def takeDerivative(x: Point3D) = {
        DenseMatrix.eye[Double](3) * p(0)
      }
    }
  }

  def inverseTransform(p: ParameterVector) = {
    if (p(0) == 0) {
      throw new Exception("Inverse transfrom of scaling by 0 not allowed !!")
      None
    } else
      Some(ScalingSpace3D()(DenseVector(1 / p(0))))
  }

  def takeDerivativeWRTParameters(p: ParameterVector) = {
    x: Point3D => DenseMatrix((x(0)), (x(1)), (x(2)))
  }
}

case class ScalingSpace2D() extends TransformationSpace[CoordVector2D] {

  def parametersDimensionality: Int = 1
    override def identityTransformParameters = DenseVector(1.)
  def apply(p: ParameterVector) = {
    require(p.length == 1)

    new Transformation[CoordVector2D] {
      def apply(x: Point2D) = {
        CoordVector2D(x(0) * p(0), x(1) * p(0))
      }

      def takeDerivative(x: Point2D) = {
        DenseMatrix.eye[Double](2) * p(0)
      }
    }
  }

  def inverseTransform(p: ParameterVector) = {
    if (p(0) == 0) {
      throw new Exception("Inverse transfrom of scaling by 0 not allowed !!")
      None
    } else
      Some(ScalingSpace2D()(DenseVector(1 / p(0))))
  }

  def takeDerivativeWRTParameters(p: ParameterVector) = {
    x: Point2D => DenseMatrix((x(0)), (x(1)))
  }
}

case class RigidTransformationSpace3D(center: Point3D)
  extends ProductTransformationSpace[CoordVector3D, TranslationSpace3D, RotationSpace3D](TranslationSpace3D(), RotationSpace3D(center)) {

}

case class RigidTransformationSpace2D(center: Point2D)
  extends ProductTransformationSpace[CoordVector2D, TranslationSpace2D, RotationSpace2D](TranslationSpace2D(), RotationSpace2D(center)) {

}

object TransformationSpace {
  type ParameterVector = DenseVector[Double]

}