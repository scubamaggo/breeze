package breeze.stats

import util.Sorting
import breeze.util.{quickSelectImpl, quickSelect}


/*
 Copyright 2009 David Hall, Daniel Ramage

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

import breeze.generic.UFunc
import breeze.linalg.{DenseMatrix, DenseVector, convert, cov}
import breeze.linalg.support.CanTraverseValues
import breeze.linalg.support.CanTraverseValues.ValuesVisitor
import breeze.macros.expand
import breeze.math.Complex
import breeze.numerics.isOdd
import spire.implicits.cfor

case class MeanAndVariance(mean: Double, variance: Double, count: Long) {
  def stdDev: Double = math.sqrt(variance)
}

trait DescriptiveStatsTrait {
  object accumulateAndCount extends UFunc {
    @expand
    implicit def reduce[T, @expand.args(Double, Complex, Float) Scalar](implicit iter: CanTraverseValues[T, Scalar], @expand.sequence[Scalar](0.0, Complex.zero, 0.0f) zero: Scalar): Impl[T, (Scalar,Int)] = new Impl[T, (Scalar,Int)] {
      def apply(v: T): (Scalar,Int) = {
        val visit = new ValuesVisitor[Scalar] {
          var sum  = zero
          var n = 0
          def visit(a: Scalar): Unit = {
            sum += a
            n += 1
          }

          def zeros(numZero: Int, zeroValue: Scalar): Unit = {
            sum += (numZero * zeroValue)
            n += numZero
          }
        }
        iter.traverse(v, visit)

        (visit.sum, visit.n)
      }
    }
  }

  /**
    * A [[breeze.generic.UFunc]] for computing the mean of objects
    */
  object mean extends UFunc {
    @expand
    implicit def reduce[T, @expand.args(Double, Complex, Float) Scalar](implicit iter: CanTraverseValues[T, Scalar], @expand.sequence[Scalar](0.0, Complex.zero, 0.0f) zero: Scalar): Impl[T, Scalar] = new Impl[T, Scalar] {
      def apply(v: T): Scalar = {
        val (sum, count) = accumulateAndCount(v)
        sum / count
      }
    }

  }

  /**
    * A [[breeze.generic.UFunc]] for computing the mean and variance of objects.
    * This uses an efficient, numerically stable, one pass algorithm for computing both
    * the mean and the variance.
    */
  object meanAndVariance extends UFunc {
    implicit def reduceDouble[T](implicit iter: CanTraverseValues[T, Double]): Impl[T, MeanAndVariance] = new Impl[T, MeanAndVariance] {
      def apply(v: T): MeanAndVariance = {
        val visit = new ValuesVisitor[Double] {
          var mu = 0.0
          var s = 0.0
          var n: Long = 0
          def visit(y: Double): Unit = {
            n += 1
            val d = y - mu
            mu = mu + 1.0/n * d
            s = s + (n-1) * d / n * d
          }

          def zeros(numZero: Int, zeroValue: Double): Unit = {
            for(i <- 0 until numZero) visit(zeroValue)
          }
        }
        iter.traverse(v, visit)
        import visit._
        if (n > 1) {
          MeanAndVariance(mu, s/(n-1), n)
        } else {
          MeanAndVariance(mu, 0, n)
        }
      }
    }
  }

  /**
    * A [[breeze.generic.UFunc]] for computing the variance of objects.
    * The method just calls meanAndVariance and returns the second result.
    */
  object variance extends UFunc {
    implicit def reduceDouble[T](implicit mv: meanAndVariance.Impl[T, MeanAndVariance]): Impl[T, Double] = new Impl[T, Double] {
      def apply(v: T): Double = mv(v).variance
    }
  }

  /**
    * Computes the standard deviation by calling variance and then sqrt'ing
    */
  object stddev extends UFunc {
    implicit def reduceDouble[T](implicit vari: variance.Impl[T, Double]): Impl[T, Double] = new Impl[T, Double] {
      def apply(v: T): Double = scala.math.sqrt(vari(v))
    }
  }

  /**
    * A [[breeze.generic.UFunc]] for computing the median of objects
    */
  object median extends UFunc {

    @expand
    implicit def reduce[@expand.args(Int, Long, Double, Float) T]: Impl[DenseVector[T], T] =
      new Impl[DenseVector[T], T] {
        def apply(v: DenseVector[T]): T = {
          if(isOdd(v.length)){
            quickSelect(v.toArray, (v.length - 1)/2 )
          } else {
            val tempArray: Array[T] = v.toArray.clone()
            val secondMedianPosition = v.length/2
            //quickSelectImpl does not clone the array, allowing us to access intermediate semi-sorted results for reuse in the second calculation
            ( quickSelectImpl(tempArray, secondMedianPosition) +
              quickSelectImpl(tempArray, secondMedianPosition-1) )/2
          }
        }
      }

    @expand
    implicit def reduceM[@expand.args(Int, Long, Double) T]: Impl[DenseMatrix[T], Double] =
      new Impl[DenseMatrix[T], Double] {
        def apply(m: DenseMatrix[T]) = median(m.toDenseVector)
      }

  }

  object covmat extends UFunc {
    implicit val matrixCovariance: Impl[DenseMatrix[Double], DenseMatrix[Double]] = new Impl[DenseMatrix[Double], DenseMatrix[Double]] {
      def apply(data: DenseMatrix[Double]) = cov(data)
    }

    implicit val sequenceCovariance: Impl[Seq[DenseVector[Double]], DenseMatrix[Double]] = new Impl[Seq[DenseVector[Double]], DenseMatrix[Double]] {
      /*
       * We roughly follow the two_pass_covariance algorithm from here: http://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Covariance
       * However, we also use Bessel's correction, in order to agree with the rest of breeze.
       */
      def apply(data: Seq[DenseVector[Double]]): DenseMatrix[Double] = {
        data.headOption.map(firstRow => {
          val result = new DenseMatrix[Double](firstRow.size, firstRow.size)
          val dataSize = firstRow.size
          //First compute the mean
          var mean = firstRow.copy
          var numRows: Long = 1
          data.tail.foreach( x => {
            numRows += 1
            if (mean.size != x.size) {
              throw new IllegalArgumentException("Attempting to compute covariance of dataset where elements have different sizes")
            }
            cfor(0)(i => i < firstRow.size, i => i+1)(i => {
              mean.unsafeUpdate(i, mean.unsafeValueAt(i) + x.unsafeValueAt(i))
            })

          })
          val numRowsD = numRows.toDouble
          mean = mean / numRowsD

          //Second compute the covariance
          data.map(x => {
            cfor(0)(i => i < dataSize, i => i+1)(i => {
              val a = x.unsafeValueAt(i) - mean.unsafeValueAt(i)
              cfor(0)(j => j < dataSize, j => j+1)(j => {
                val b = x.unsafeValueAt(j) - mean.unsafeValueAt(j)
                result.unsafeUpdate(i, j, result.unsafeValueAt(i,j) + (a*b/(numRowsD-1))) //Use
              })
            })
          })

          result
        }).getOrElse(new DenseMatrix[Double](0,0))
      }
    }
  }

  object corrcoeff extends UFunc {
    implicit def matrixCorrelation[T](implicit covarianceCalculator: covmat.Impl[T,DenseMatrix[Double]]): Impl[T, DenseMatrix[Double]] = new Impl[T, DenseMatrix[Double]] {
      def apply(data: T) = {
        val covariance = covarianceCalculator(data)
        val d = new Array[Double](covariance.rows)
        cfor(0)(i => i < covariance.rows, i => i+1)(i => {
          d(i) = math.sqrt(covariance.unsafeValueAt(i,i))
        })

        cfor(0)(i => i < covariance.rows, i => i+1)(i => {
          cfor(0)(j => j < covariance.rows, j => j+1)(j => {
            if (i != j) {
              covariance.unsafeUpdate(i,j, covariance.unsafeValueAt(i,j) / (d(i) * d(j)) )
            } else {
              covariance.unsafeUpdate(i,j, 1.0)
            }
          })
        })
        covariance
      }
    }
  }
}

/**
  * Provides utilities for descriptive statistics, like the mean and variance.
  */
object DescriptiveStats {
  /**
    * returns the estimate of the data at p * it.size, where p in [0,1]
    */
  def percentile(it: TraversableOnce[Double], p: Double) = {
    if(p > 1 || p < 0) throw new IllegalArgumentException("p must be in [0,1]")
    val arr = it.toArray
    Sorting.quickSort(arr)
    // +1 so that the .5 == mean for even number of elements.
    val f = (arr.length + 1) * p
    val i = f.toInt
    if(i == 0) arr.head
    else if (i >= arr.length) arr.last
    else {
      arr(i-1) + (f - i) * (arr(i) - arr(i-1))
    }
  }

  /**
    * Returns both means and covariance between two vectors. Single pass algorithm.
    * <p>
    * Note:
    * Will happily compute covariance between vectors of different lengths
    * by truncating the longer vector.
    * </p>
    */

  def meanAndCov[T](it1 : TraversableOnce[T], it2 : TraversableOnce[T])(implicit frac: Fractional[T]) = {
    implicit def t(it:TraversableOnce[T]) = it.toIterable //convert to an iterable for zip operation
    import frac.mkNumericOps
    //mu1(n-1), mu2(n-1), Cov(n-1), n-1
    val (mu1,mu2,c,n) = (it1,it2).zipped.foldLeft( (frac.zero,frac.zero,frac.zero,frac.zero) ) {
      (acc,y) => val(oldMu1,oldMu2,oldC,oldN) = acc
      val newN = oldN + frac.fromInt(1)
      val newMu1 = oldMu1 + ((y._1 - oldMu1) / newN)
      val newMu2 = oldMu2 + ((y._2 - oldMu2) / newN)
      val newC = oldC + ((y._1 - oldMu1)*(y._2 - newMu2))//compute covariance in single pass
      (newMu1,newMu2,newC,newN)
    }
    if(n==1) (mu1,mu2,0) else (mu1,mu2,c/(n-frac.fromInt(1)))
  }

  /**
    * Returns covariance between two vectors.
    * <p>
    * Note:
    * Will happily compute covariance between vectors of different lengths
    * by truncating the longer vector.
    * </p>
    */

  def cov[T](it1 : Iterable[T], it2 : Iterable[T])(implicit n: Fractional[T]) = {
    meanAndCov(it1,it2)._3
  }

}
