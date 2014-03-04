package org.statismo.stk.core
package statisticalmodel

import common.BoxedDomain
import breeze.linalg.{ DenseVector, DenseMatrix, linspace }
import breeze.stats.distributions.Gaussian
import org.statismo.stk.core.common.DiscreteDomain
import org.statismo.stk.core.common.{ BoxedDomain1D, BoxedDomain2D, BoxedDomain3D }
import org.statismo.stk.core.numerics.{ UniformSampler1D, UniformSampler2D, UniformSampler3D }
import org.statismo.stk.core.io.MeshIO
import org.statismo.stk.core.mesh.TriangleMesh
import org.statismo.stk.core.image.Utils
import java.io.File
import org.statismo.stk.core.numerics.UniformSampler1D
import org.statismo.stk.core.kernels._
import org.statismo.stk.core.numerics.UniformSampler1D
import breeze.plot.{ plot, Figure }
import org.statismo.stk.core.image.DiscreteImageDomain
import org.statismo.stk.core.common.ImmutableLRU
import scala.collection.immutable.HashMap
import org.statismo.stk.core.geometry._
import org.statismo.stk.core.numerics.Sampler
import org.statismo.stk.core.numerics.UniformSampler3D
import scala.concurrent._
import ExecutionContext.Implicits.global
import org.statismo.stk.core.registration.Transformation
import org.statismo.stk.core.common.Domain
import org.statismo.stk.core.numerics.Sampler
import breeze.linalg.Axis
import org.statismo.stk.core.mesh.kdtree.KDTree
import org.statismo.stk.core.mesh.kdtree.KDTreeMap
import org.statismo.stk.core.geometry.Point

trait GaussianProcess[D <: Dim] {
  val domain: Domain[D]
  val mean: Point[D] => Vector[D]
  val cov: MatrixValuedPDKernel[D, D]
}

case class LowRankGaussianProcessConfiguration[D <: Dim](
  val domain: Domain[D],
  val sampler: Sampler[D, Point[D]],
  val mean: Point[D] => Vector[D],
  val cov: MatrixValuedPDKernel[D, D],
  val numBasisFunctions: Int)

class LowRankGaussianProcess[D <: Dim: DimTraits](val domain: Domain[D],
  val mean: Point[D] => Vector[D],
  val eigenPairs: IndexedSeq[(Float, Point[D] => Vector[D])])
  extends GaussianProcess[D] {

  val dimTraits = implicitly[DimTraits[D]]
  def outputDim = dimTraits.dimensionality
  def rank = eigenPairs.size

  val cov: MatrixValuedPDKernel[D, D] = new MatrixValuedPDKernel[D, D] {
    def apply(x: Point[D], y: Point[D]): MatrixNxN[D] = {
      val ptDim = dimTraits.dimensionality
      val phis = eigenPairs.map(_._2)

      var outer = MatrixNxN.zeros[D]
      for ((lambda_i, phi_i) <- eigenPairs) {
        outer = outer + (phi_i(x) outer phi_i(y)) * lambda_i
      }
      outer

    }
  }

  def instance(alpha: DenseVector[Float]): Point[D] => Vector[D] = {
    require(eigenPairs.size == alpha.size)
    x =>
      {
        val deformationsAtX = (0 until eigenPairs.size).map(i => {
          val (lambda_i, phi_i) = eigenPairs(i)
          phi_i(x) * alpha(i) * math.sqrt(lambda_i).toFloat
        })
        deformationsAtX.foldLeft(mean(x))(_ + _)
      }
  }

  def sample: Point[D] => Vector[D] = {
    val coeffs = for (_ <- 0 until eigenPairs.size) yield Gaussian(0, 1).draw().toFloat
    instance(DenseVector(coeffs.toArray))
  }

  def jacobian(p: DenseVector[Float]) = { x: Point[D] =>
    val dim = x.dimensionality
    val J = DenseMatrix.zeros[Float](dim, eigenPairs.size)
    (0 until eigenPairs.size).map(i => {
      val (lambda_i, phi_i) = eigenPairs(i)
      J(::, i) := (phi_i(x) * math.sqrt(lambda_i).toFloat).toBreezeVector
    })
    J
  }

  /**
   * returns a new gaussian process, where the values for the points are
   * pre-computed and hence fast to obtain
   */
  def specializeForPoints(points: IndexedSeq[Point[D]]) = {
    SpecializedLowRankGaussianProcess(this, points)
  }

  /**
   * Compute the marginal distribution for the given point
   */
  def marginal(pt: Point[D]): MVNormalForPoint[D] = {
    MVNormalForPoint(pt, mean(pt), cov(pt, pt))
  }


  def project(trainingData : IndexedSeq[(Point[D], Vector[D])], sigma2 : Double = 1e-6) : (Point[D] => Vector[D])  = {
    val newtd = trainingData.map { case (pt, df) => (pt, df, sigma2) }
    project(newtd)
  }

  def project(trainingData : IndexedSeq[(Point[D], Vector[D], Double)]) : Point[D] => Vector[D] = {
    val c = coefficients(trainingData)
    instance(c)
  }

  /**
   * Compute the coefficients alpha, that represent the given trainingData u best under this gp (in the least squares sense)
   * e.g. \sum_i alpha_i \lambda_i \phi_i(x) = u(x)
   */
  def coefficients(trainingData : IndexedSeq[(Point[D], Vector[D], Double)]) : DenseVector[Float] = {
    val (minv, qtL, yVec, mVec) = GaussianProcess.genericRegressionComputations(this, trainingData)
    val mean_coeffs = (minv * qtL).map(_.toFloat) * (yVec - mVec)
    mean_coeffs
  }

  def coefficients(trainingData : IndexedSeq[(Point[D], Vector[D])], sigma2 : Double) : DenseVector[Float] = {
    val newtd = trainingData.map { case (pt, df) => (pt, df, sigma2) }
    coefficients(newtd)
  }
}

class SpecializedLowRankGaussianProcess[D <: Dim: DimTraits](gp: LowRankGaussianProcess[D], val points: IndexedSeq[Point[D]], val meanVector: DenseVector[Float], val lambdas: IndexedSeq[Float], val eigenMatrix: DenseMatrix[Float])
  extends LowRankGaussianProcess[D](gp.domain, gp.mean, gp.eigenPairs) {

  private val (gpLambdas, gpPhis) = gp.eigenPairs.unzip
  private val pointToIdxMap = points.zipWithIndex.toMap
  private val stddev = DenseVector(lambdas.map(x => math.sqrt(x).toFloat).toArray)
  private val phis = (0 until lambdas.size).map(i => phiAtPoint(i)_)

  // define all fields required to qualify as a "ordinary" LowRankGaussianProcess
  override val domain = gp.domain
  override val outputDim = gp.outputDim
  override val eigenPairs = lambdas.zip(phis)
  override val mean = meanAtPoint _

  override val cov: MatrixValuedPDKernel[D, D] = {
    new MatrixValuedPDKernel[D, D] {
      def apply(x: Point[D], y: Point[D]): MatrixNxN[D] = {

        // if pt is in our map, we compute it efficiently, otherwise we 
        // fall back to generic version
        val cov: Option[MatrixNxN[D]] = for {
          ptId1 <- pointToIdxMap.get(x)
          ptId2 <- pointToIdxMap.get(y)
        } yield computeCov(ptId1, ptId2)

        cov.getOrElse(gp.cov(x, y))
      }
    }
  }

  def instanceAtPoints(alpha: DenseVector[Float]): IndexedSeq[(Point[D], Vector[D])] = {
    require(eigenPairs.size == alpha.size)
    val instVal = instanceVector(alpha)
    val ptVals = for (v <- instVal.toArray.grouped(outputDim)) yield dimTraits.createVector(v)
    points.zip(ptVals.toIndexedSeq)
  }

  def instanceVector(alpha: DenseVector[Float]): DenseVector[Float] = {
    require(eigenPairs.size == alpha.size)

    // the following code corrresponds to the breeze code:
    //eigenMatrix * (stddev :* alpha) + meanVector
    // but is much more efficient (and parallel)

    val q = stddev :* alpha
    val instance = DenseVector.zeros[Float](meanVector.size)
    for (i <- (0 until instance.size).par) {
      var j = 0;
      while (j < alpha.size) {
        instance(i) += eigenMatrix(i, j) * q(j)
        j += 1
      }
      instance(i) += meanVector(i)
    }
    instance

  }

  def sampleAtPoints: IndexedSeq[(Point[D], Vector[D])] = {
    val coeffs = for (_ <- 0 until eigenPairs.size) yield Gaussian(0, 1).draw().toFloat
    instanceAtPoints(DenseVector(coeffs.toArray))
  }

  private def meanAtPoint(pt: Point[D]): Vector[D] = {
    pointToIdxMap.get(pt) match {
      case Some(ptId) => {
        // we need the copy here, as otherwise vec.data will be the array of the  
        // original vector (from which we extracted a slice)
        val vec = meanVector(ptId * outputDim until (ptId + 1) * outputDim).copy
        dimTraits.createVector(vec.data)
      }
      case None => gp.mean(pt)
    }
  }

  private def phiAtPoint(i: Int)(pt: Point[D]): Vector[D] = {
    val dimTraits = implicitly[DimTraits[D]]
    pointToIdxMap.get(pt) match {
      case Some(ptId) => {
        // we need the copy here, as otherwise vec.data will be the array of the  
        // original vector (from which we extracted a slice)
        val df = eigenMatrix(ptId * gp.outputDim until (ptId + 1) * gp.outputDim, i).copy
        dimTraits.createVector(df.data)
      }
      case None => gpPhis(i)(pt)
    }
  }

  //compute covariance for two points with given ptIds
  private def computeCov(ptId1: Int, ptId2: Int) = {
    val eigenMatrixForPtId1 = eigenMatrix(ptId1 * outputDim until (ptId1 + 1) * outputDim, ::)
    val eigenMatrixForPtId2 = eigenMatrix(ptId2 * outputDim until (ptId2 + 1) * outputDim, ::)
    //val covValue = eigenMatrixForPtId1 * breeze.linalg.diag(stddev :* stddev) * eigenMatrixForPtId2.t 

    // same as commented line above, but just much more efficient (as breeze does not have diag matrix, 
    // the upper command does a lot of  unnecessary computations
    val covValue = DenseMatrix.zeros[Float](outputDim, outputDim)

    for (i <- (0 until outputDim).par) {
      val ind1 = ptId1 * outputDim + i
      var j = 0
      while (j < outputDim) {
        val ind2 = ptId2 * outputDim + j
        var k = 0
        var valueIJ = 0f
        while (k < eigenMatrix.cols) {
          valueIJ += eigenMatrix(ind1, k) * eigenMatrix(ind2, k) * lambdas(k)
          k += 1
        }
        covValue(i, j) = valueIJ
        j += 1
      }
    }

    dimTraits.createMatrixNxN(covValue.data)
  }
}

object SpecializedLowRankGaussianProcess {
  def apply[D <: Dim: DimTraits](gp: LowRankGaussianProcess[D], points: IndexedSeq[Point[D]]) = {

    // precompute all the at the given points
    val (gpLambdas, gpPhis) = gp.eigenPairs.unzip
    val m = DenseVector.zeros[Float](points.size * gp.outputDim)
    for (xWithIndex <- points.zipWithIndex.par) {
      val (x, i) = xWithIndex
      m(i * gp.outputDim until (i + 1) * gp.outputDim) := gp.mean(x).toBreezeVector
    }

    val U = DenseMatrix.zeros[Float](points.size * gp.outputDim, gp.rank)
    for (xWithIndex <- points.zipWithIndex.par; (phi_j, j) <- gpPhis.zipWithIndex) {
      val (x, i) = xWithIndex
      val v = phi_j(x)
      U(i * gp.outputDim until (i + 1) * gp.outputDim, j) := phi_j(x).toBreezeVector
    }

    new SpecializedLowRankGaussianProcess(gp, points, m, gpLambdas, U)
  }

}

class LowRankGaussianProcess1D(
  domain: Domain[OneD],
  mean: Point[OneD] => Vector[OneD],
  eigenPairs: IndexedSeq[(Float, Point[OneD] => Vector[OneD])])
  extends LowRankGaussianProcess[OneD](domain, mean, eigenPairs) {}

class LowRankGaussianProcess2D(
  domain: Domain[TwoD],
  mean: Point[TwoD] => Vector[TwoD],
  eigenPairs: IndexedSeq[(Float, Point[TwoD] => Vector[TwoD])])
  extends LowRankGaussianProcess[TwoD](domain, mean, eigenPairs) {}

class LowRankGaussianProcess3D(
  domain: Domain[ThreeD],
  mean: Point[ThreeD] => Vector[ThreeD],
  eigenPairs: IndexedSeq[(Float, Point[ThreeD] => Vector[ThreeD])])
  extends LowRankGaussianProcess[ThreeD](domain, mean, eigenPairs) {}

object GaussianProcess {


  def createLowRankGaussianProcess1D(configuration: LowRankGaussianProcessConfiguration[OneD]) = {
    val eigenPairs = Kernel.computeNystromApproximation(configuration.cov, configuration.numBasisFunctions, configuration.sampler)
    new LowRankGaussianProcess1D(configuration.domain, configuration.mean, eigenPairs)
  }

  def createLowRankGaussianProcess2D(configuration: LowRankGaussianProcessConfiguration[TwoD]) = {
    val eigenPairs = Kernel.computeNystromApproximation(configuration.cov, configuration.numBasisFunctions, configuration.sampler)
    new LowRankGaussianProcess2D(configuration.domain, configuration.mean, eigenPairs)
  }

  def createLowRankGaussianProcess3D(configuration: LowRankGaussianProcessConfiguration[ThreeD]) = {
    val eigenPairs = Kernel.computeNystromApproximation(configuration.cov, configuration.numBasisFunctions, configuration.sampler)
    new LowRankGaussianProcess3D(configuration.domain, configuration.mean, eigenPairs)
  }

  /**
   * create a LowRankGaussianProcess using PCA
   * Currently, this is done by discretizing the transformations and computing the mean and the covariance
   * of the discrete transformations.
   * @param Domain the domain on which the GP will be defined
   * @param transformations
   * @param sampler A (preferably) uniform sampler from which the points are sampled
   *
   * @TODO It should be explicitly enforced (using the type system) that the sampler is uniform
   * @TODO At some point this should be replaced by a functional PCA
   */
  def createLowRankGPFromTransformations[D <: Dim: DimTraits](domain: Domain[D], transformations: Seq[Transformation[D]], sampler: Sampler[D, Point[D]]): LowRankGaussianProcess[D] = {
    val dimTraits = implicitly[DimTraits[D]]
    val dim = dimTraits.dimensionality

    val samplePts = sampler.sample.map(_._1)

    val kdTreeMap = KDTreeMap.fromSeq(samplePts.zipWithIndex.toIndexedSeq)

    def findClosestPoint(pt: Point[D]): (Point[D], Int) = {
      val nearestPtsAndIndices = (kdTreeMap.findNearest(pt, n = 1))
      nearestPtsAndIndices(0)
    }
    def findClosestPoints(pt: Point[D], n: Int): Seq[(Point[D], Int)] = {
      val nearestPtsAndIndices = (kdTreeMap.findNearest(pt, n))
      nearestPtsAndIndices
    }

    val n = transformations.size
    val p = samplePts.size

    // create the data matrix
    val X = DenseMatrix.zeros[Float](n, p * dim)
    for ((t, i) <- transformations.zipWithIndex.par; (x, j) <- samplePts.zipWithIndex) {
      val ux = t(x) - x
      X(i, j * dim until (j + 1) * dim) := ux.toBreezeVector
    }

    def demean(X: DenseMatrix[Float]): (DenseMatrix[Double], DenseVector[Double]) = {
      val X0 = X.map(_.toDouble) // will be the demeaned result matrix
      val m = breeze.linalg.mean(X0, Axis._0)
      for (i <- 0 until X0.rows) {
        X0(i, ::) := X0(i, ::) - m
      }
      (X0, m.toDenseVector)
    }

    val (x0, meanVec) = demean(X)
    val (u, d2, vt) = breeze.linalg.svd(x0 * x0.t * (1.0 / (n - 1)))

    val D = d2.map(v => Math.sqrt(v))
    val Dinv = D.map(d => if (d > 1e-6) 1.0 / d else 0.0)

    // a Matrix with the eigenvectors
    val U = x0.t * vt.t * breeze.linalg.diag(Dinv) / Math.sqrt(n - 1)

    // to compensate for the numerical approximation using the sampled poitns
    val normFactor = sampler.volumeOfSampleRegion / sampler.numberOfPoints

    def interpolateAtPoint(x: Point[D], dataVec: DenseVector[Double]): Vector[D] = {
      val nNbrPts = Math.pow(2, dim).toInt
      val ptAndIds = findClosestPoints(x, nNbrPts)

      var v = dimTraits.zeroVector
      var sumW = 0.0
      for ((pt, id) <- ptAndIds) {
        val w = 1.0 / math.max((pt - x).norm, 1e-5)
        v += dimTraits.createVector(dataVec(id * dim until (id + 1) * dim).map(_.toFloat).data) * w
        sumW += w
      }
      v * (1.0 / sumW)
    }
    def mu(x: Point[D]): Vector[D] = {
      interpolateAtPoint(x, meanVec)
    }
    def phi(i: Int)(x: Point[D]): Vector[D] = {
      interpolateAtPoint(x, U(::, i)) * Math.sqrt(1.0 / normFactor)
    }

    val lambdas = d2.toArray.toIndexedSeq.map(l => (l * normFactor).toFloat)
    val phis = (0 until n).map(i => phi(i) _)
    val eigenPairs = lambdas zip phis
    new LowRankGaussianProcess[D](domain, mu _, eigenPairs)
  }

  // Gaussian process regression for a low rank gaussian process
  // Note that this implementation is literally the same as the one for the specializedLowRankGaussian process. The difference is just the return type. 
  // TODO maybe the implementations can be joined.
  def regression[D <: Dim: DimTraits](gp: LowRankGaussianProcess[D], trainingData: IndexedSeq[(Point[D], Vector[D])], sigma2: Double, meanOnly: Boolean = false): LowRankGaussianProcess[D] = {
    val trainingDataWithNoise = trainingData.map { case (x, y) => (x, y, sigma2) }
    
    gp match {
      case gp: SpecializedLowRankGaussianProcess[D] => regressionSpecializedLowRankGP(gp, trainingDataWithNoise, meanOnly)
      case gp => regressionLowRankGP(gp, trainingDataWithNoise, meanOnly)
    }

  }

  def regression[D <: Dim: DimTraits](gp: LowRankGaussianProcess[D], trainingData: IndexedSeq[(Point[D], Vector[D], Double)], meanOnly: Boolean = false): LowRankGaussianProcess[D] = {
    gp match {
      case gp: SpecializedLowRankGaussianProcess[D] => regressionSpecializedLowRankGP(gp, trainingData, meanOnly)
      case gp => regressionLowRankGP(gp, trainingData, meanOnly)
    }

  }

  private def regressionLowRankGP[D <: Dim: DimTraits](gp: LowRankGaussianProcess[D], trainingData: IndexedSeq[(Point[D], Vector[D], Double)], meanOnly: Boolean = false): LowRankGaussianProcess[D] = {
    val (lambdas, phis) = gp.eigenPairs.unzip
    val dimTraits = implicitly[DimTraits[D]]
    val outputDim = dimTraits.dimensionality

    val (minv, qtL, yVec, mVec) = GaussianProcess.genericRegressionComputations(gp, trainingData)
    val mean_coeffs = (minv * qtL).map(_.toFloat) * (yVec - mVec)

    val mean_p = gp.instance(mean_coeffs)

    if (meanOnly == true) {
      val emptyEigenPairs = IndexedSeq[(Float, Point[D] => Vector[D])]()
      new LowRankGaussianProcess[D](gp.domain, mean_p, emptyEigenPairs)

    } else {
      val D = breeze.linalg.diag(DenseVector(lambdas.map(math.sqrt(_)).toArray))
      val Sigma = D * minv * D
      val (innerUDbl, innerD2, _) = breeze.linalg.svd(Sigma)
      val innerU = innerUDbl.map(_.toFloat)
      @volatile
      var phisAtXCache = ImmutableLRU[Point[D], DenseMatrix[Float]](1000)

      def phip(i: Int)(x: Point[D]): Vector[D] = { // should be phi_p but _ is treated as partial function
        val (maybePhisAtX, newPhisAtXCache) = phisAtXCache.get(x)
        val phisAtX = maybePhisAtX.getOrElse {
          val newPhisAtX = {
            val innerPhisAtx = DenseMatrix.zeros[Float](outputDim, gp.rank)
            var j = 0;
            while (j < phis.size) {
              val phi_j = phis(j)
              innerPhisAtx(0 until outputDim, j) := phi_j(x).toBreezeVector
              j += 1
            }
            innerPhisAtx
          }
          phisAtXCache = (phisAtXCache + (x, newPhisAtX))._2 // ignore evicted key
          newPhisAtX
        }
        val vec = phisAtX * innerU(::, i)
        dimTraits.createVector(vec.data)
      }

      val phis_p = for (i <- 0 until phis.size) yield ((x : Point[D]) => phip(i)(x))
      val lambdas_p = innerD2.toArray.map(_.toFloat).toIndexedSeq
      new LowRankGaussianProcess[D](gp.domain, mean_p, lambdas_p.zip(phis_p))
    }
  }


  protected[statisticalmodel] def genericRegressionComputations[D <: Dim : DimTraits](gp : LowRankGaussianProcess[D], trainingData: IndexedSeq[(Point[D], Vector[D], Double)])
    : (DenseMatrix[Double], DenseMatrix[Double], DenseVector[Float], DenseVector[Float]) =
    {

      val dimTraits = implicitly[DimTraits[D]]
      val dim = dimTraits.dimensionality
      def flatten(v: IndexedSeq[Vector[D]]) = DenseVector(v.flatten(_.data).toArray)

      val (xs, ys, sigma2s) = trainingData.unzip3

      val yVec = flatten(ys)
      val meanValues = xs.map(gp.mean)
      val mVec = flatten(meanValues)

      val d = gp.outputDim
      val (lambdas, phis) = gp.eigenPairs.unzip

      val Q = DenseMatrix.zeros[Double](trainingData.size * d, phis.size)
      for ((x_i, i) <- xs.zipWithIndex; (phi_j, j) <- phis.zipWithIndex) {
        Q(i * d until i * d + d, j) := phi_j(x_i).toBreezeVector.map(_.toDouble) * math.sqrt(lambdas(j))
      }

      // compute Q^TL where L is a diagonal matrix that contains the inverse of the sigmas in the diagonal.
      // As there is only one sigma for each point (but the point has dim components) we need
      // to correct the index for sigma
      val QtL = Q.t.copy
      val sigma2sInv = sigma2s.map { sigma2 =>
        val divisor = math.max(1e-8, sigma2)
        1.0 / divisor
      }
      for (i <- 0 until QtL.cols) {
        QtL(::, i) *= sigma2sInv(i / dim)
      }

      val M = QtL * Q + DenseMatrix.eye[Double](phis.size)
      val Minv = breeze.linalg.pinv(M)
      (Minv, QtL, yVec, mVec)
    }


  /**
   * Gausssian process regression for a specialzed GP.
   * This implementation explicitly returns a SpecializedLowRankGaussainProcess
   * TODO the implementation is almost the same as for the standard regression. Maybe they couuld be merged
   */
  private def regressionSpecializedLowRankGP[D <: Dim: DimTraits](gp: SpecializedLowRankGaussianProcess[D], trainingData: IndexedSeq[(Point[D], Vector[D], Double)], meanOnly: Boolean = false): SpecializedLowRankGaussianProcess[D] = {

    val dimTraits = implicitly[DimTraits[D]]
    val dim = dimTraits.dimensionality
    val (xs, ys, sigma2s) = trainingData.unzip3
    //def flatten(v: IndexedSeq[Vector[D]]) = DenseVector(v.flatten(_.data).toArray)

    val (lambdas, phis) = gp.eigenPairs.unzip

    val (minv, qtL, yVec, mVec) = GaussianProcess.genericRegressionComputations(gp, trainingData)

    val mean_coeffs = (minv * qtL).map(_.toFloat) * (yVec - mVec)

    gp.instanceAtPoints(mean_coeffs)

    val mean_p = gp.instance(mean_coeffs)
    val mean_pVector = gp.instanceVector(mean_coeffs)

    if (meanOnly == true) {
      // create an empty gaussian process (not specialized), which is needed in order to be able to construct 
      // the specialized one
      val emptyEigenPairs = IndexedSeq[(Float, Point[D] => Vector[D])]()
      val meanOnlyGp = new LowRankGaussianProcess[D](gp.domain, mean_p, emptyEigenPairs)

      new SpecializedLowRankGaussianProcess[D](meanOnlyGp,
        gp.points,
        mean_pVector,
        IndexedSeq[Float](),
        DenseMatrix.zeros[Float](mean_pVector.size, 0))
    } else {
      val D = breeze.linalg.diag(DenseVector(lambdas.map(math.sqrt(_)).toArray))
      val Sigma = D * minv * D
      val (innerUDbl, innerD2, _) = breeze.linalg.svd(Sigma)
      val innerU = innerUDbl.map(_.toFloat)
      @volatile
      var phisAtXCache = ImmutableLRU[Point[D], DenseMatrix[Float]](1000)

      def phip(i: Int)(x: Point[D]): Vector[D] = { // should be phi_p but _ is treated as partial function
        val (maybePhisAtX, newPhisAtXCache) = phisAtXCache.get(x)
        val phisAtX = maybePhisAtX.getOrElse {
          val newPhisAtX = {
            val innerPhisAtx = DenseMatrix.zeros[Float](dim, gp.rank)
            var j = 0;
            while (j < phis.size) {
              val phi_j = phis(j)
              innerPhisAtx(0 until dim, j) := phi_j(x).toBreezeVector
              j += 1
            }
            innerPhisAtx
          }
          phisAtXCache = (phisAtXCache + (x, newPhisAtX))._2 // ignore evicted key
          newPhisAtX
        }
        val vec = phisAtX * innerU(::, i)
        dimTraits.createVector(vec.data)
      }

      val phis_p = for (i <- 0 until phis.size) yield ((x : Point[D])=> phip(i)(x))
      val lambdas_p = innerD2.toArray.map(_.toFloat).toIndexedSeq
      val unspecializedGP = new LowRankGaussianProcess[D](gp.domain, mean_p, lambdas_p.zip(phis_p))

      // we do the follwoing computation
      // val eigenMatrix_p = gp.eigenMatrix * innerU // IS this correct?
      // but in parallel
      val eigenMatrix_p = DenseMatrix.zeros[Float](gp.eigenMatrix.rows, innerU.cols)
      for (rowInd <- (0 until gp.eigenMatrix.rows).par) {
        eigenMatrix_p(rowInd, ::) := gp.eigenMatrix(rowInd, ::) * innerU
      }

      new SpecializedLowRankGaussianProcess(unspecializedGP, gp.points, mean_pVector, lambdas_p, eigenMatrix_p)
    }
  }

}