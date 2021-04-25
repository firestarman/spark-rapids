/*
 * Copyright (c) 2020-2021, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.rapids.execution.python

import scala.collection.mutable.ArrayBuffer

import com.nvidia.spark.rapids._
import com.nvidia.spark.rapids.RapidsPluginImplicits._
import com.nvidia.spark.rapids.python.PythonWorkerSemaphore

import org.apache.spark.TaskContext
import org.apache.spark.api.python.{ChainedPythonFunctions, PythonEvalType}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.physical.{AllTuples, ClusteredDistribution, Distribution, Partitioning}
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}
import org.apache.spark.sql.execution.python.AggregateInPandasExec
import org.apache.spark.sql.types.{DataType, StructField, StructType}
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.ColumnarBatch

class GpuAggregateInPandasExecMeta(
    aggPandas: AggregateInPandasExec,
    conf: RapidsConf,
    parent: Option[RapidsMeta[_, _, _]],
    rule: DataFromReplacementRule)
  extends SparkPlanMeta[AggregateInPandasExec](aggPandas, conf, parent, rule) {

  override def replaceMessage: String = "partially run on GPU"
  override def noReplacementPossibleMessage(reasons: String): String =
    s"cannot run even partially on the GPU because $reasons"

  private val groupingNamedExprs: Seq[BaseExprMeta[NamedExpression]] =
    aggPandas.groupingExpressions.map(GpuOverrides.wrapExpr(_, conf, Some(this)))

  private val udfs: Seq[BaseExprMeta[PythonUDF]] =
    aggPandas.udfExpressions.map(GpuOverrides.wrapExpr(_, conf, Some(this)))

  private val resultNamedExprs: Seq[BaseExprMeta[NamedExpression]] =
    aggPandas.resultExpressions.map(GpuOverrides.wrapExpr(_, conf, Some(this)))

  override val childExprs: Seq[BaseExprMeta[_]] = groupingNamedExprs ++ udfs ++ resultNamedExprs

  override def convertToGpu(): GpuExec =
    GpuAggregateInPandasExec(
      groupingNamedExprs.map(_.convertToGpu()).asInstanceOf[Seq[NamedExpression]],
      udfs.map(_.convertToGpu()).asInstanceOf[Seq[GpuPythonUDF]],
      resultNamedExprs.map(_.convertToGpu()).asInstanceOf[Seq[NamedExpression]],
      childPlans.head.convertIfNeeded()
    )
}

/**
 * Physical node for aggregation with group aggregate Pandas UDF.
 *
 * This plan works by sending the necessary (projected) input grouped data as Arrow record batches
 * to the python worker, the python worker invokes the UDF and sends the results to the executor,
 * finally the executor evaluates any post-aggregation expressions and join the result with the
 * grouped key.
 *
 * This node aims at accelerating the data transfer between JVM and Python for GPU pipeline, and
 * scheduling GPU resources for its Python processes.
 */
case class GpuAggregateInPandasExec(
    groupingExpressions: Seq[NamedExpression],
    udfExpressions: Seq[GpuPythonUDF],
    resultExpressions: Seq[NamedExpression],
    child: SparkPlan)
  extends UnaryExecNode with GpuPythonExecBase {

  override val output: Seq[Attribute] = resultExpressions.map(_.toAttribute)

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def producedAttributes: AttributeSet = AttributeSet(output)

  override def requiredChildDistribution: Seq[Distribution] = {
    if (groupingExpressions.isEmpty) {
      AllTuples :: Nil
    } else {
      ClusteredDistribution(groupingExpressions) :: Nil
    }
  }

  private def collectFunctions(udf: GpuPythonUDF): (ChainedPythonFunctions, Seq[Expression]) = {
    udf.children match {
      case Seq(u: GpuPythonUDF) =>
        val (chained, children) = collectFunctions(u)
        (ChainedPythonFunctions(chained.funcs ++ Seq(udf.func)), children)
      case children =>
        // There should not be any other UDFs, or the children can't be evaluated directly.
        assert(children.forall(_.find(_.isInstanceOf[GpuPythonUDF]).isEmpty))
        (ChainedPythonFunctions(Seq(udf.func)), udf.children)
    }
  }

  override def requiredChildOrdering: Seq[Seq[SortOrder]] =
    Seq(groupingExpressions.map(ShimLoader.getSparkShims.sortOrder(_, Ascending)))

  // One batch as input to keep the integrity for each group.
  // (This should be replaced by an iterator that can split batches on key boundaries eventually.
  //  For more details please refer to the following issue:
  //        https://github.com/NVIDIA/spark-rapids/issues/2200 )
  //
  // But there is a special case that the iterator can not work. when 'groupingExpressions' is
  // empty, the input must be a single batch, because the whole data is treated as a single
  // group, and it should be sent to Python at one time.
  override def childrenCoalesceGoal: Seq[CoalesceGoal] = Seq(RequireSingleBatch)

  override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    val (mNumInputRows, mNumInputBatches, mNumOutputRows, mNumOutputBatches,
      spillCallback) = commonGpuMetrics()

    lazy val isPythonOnGpuEnabled = GpuPythonHelper.isPythonOnGpuEnabled(conf)
    val sessionLocalTimeZone = conf.sessionLocalTimeZone
    val pythonRunnerConf = ArrowUtils.getPythonRunnerConfMap(conf)
    val pyOutAttributes = udfExpressions.map(_.resultAttribute)
    val pyOutSchema = StructType.fromAttributes(pyOutAttributes)
    val childOutput = child.output
    val resultExprs = resultExpressions

    val (pyFuncs, inputs) = udfExpressions.map(collectFunctions).unzip

    // Filter child output attributes down to only those that are UDF inputs.
    // Also eliminate duplicate UDF inputs.
    val allInputs = new ArrayBuffer[Expression]
    val dataTypes = new ArrayBuffer[DataType]
    val argOffsets = inputs.map { input =>
      input.map { e =>
        if (allInputs.exists(_.semanticEquals(e))) {
          allInputs.indexWhere(_.semanticEquals(e))
        } else {
          allInputs += e
          dataTypes += e.dataType
          allInputs.length - 1
        }
      }.toArray
    }.toArray

    // Schema of input rows to the python runner
    val aggInputSchema = StructType(dataTypes.zipWithIndex.map { case (dt, i) =>
      StructField(s"_$i", dt)
    })

    // Start processing
    child.executeColumnar().mapPartitionsInternal { inputIter =>
      val queue: BatchQueue = new BatchQueue()
      val context = TaskContext.get()
      context.addTaskCompletionListener[Unit](_ => queue.close())

      if (isPythonOnGpuEnabled) {
        GpuPythonHelper.injectGpuInfo(pyFuncs, isPythonOnGpuEnabled)
        PythonWorkerSemaphore.acquireIfNecessary(context)
      }

      // First projects the input batches to (groupingExpressions + allInputs), which is minimum
      // necessary for the following processes.
      // Doing this can reduce the data size to be split, probably getting a better performance.
      val groupingRefs = GpuBindReferences.bindGpuReferences(groupingExpressions, childOutput)
      val pyInputRefs = GpuBindReferences.bindGpuReferences(allInputs, childOutput)
      val miniIter = inputIter.map { batch =>
        mNumInputBatches += 1
        mNumInputRows += batch.numRows()
        withResource(batch) { b =>
          // Builds the key batch for the input batch, and puts it into the cache
          // for the combination later with the result batch from Python.
          val keyBatch = BatchGroupedIterator.extractKeyRowForGroups(b, groupingRefs)
          queue.add(keyBatch, spillCallback)
          GpuProjectExec.project(b, groupingRefs ++ pyInputRefs)
        }
      }

      // Second splits into separate group batches.
      val miniAttrs = groupingExpressions ++ allInputs
      val pyInputIter = BatchGroupedIterator(miniIter, miniAttrs.asInstanceOf[Seq[Attribute]],
          groupingRefs.indices, spillCallback)
        .map { groupedBatch =>
          // Resolves the python input.
          // `Seq.indices.map()` is NOT lazy, it is safe to slice the columns.
          withResource(groupedBatch) { grouped =>
            val pyInputColumns = pyInputRefs.indices.safeMap { idx =>
              grouped.column(idx + groupingRefs.size).asInstanceOf[GpuColumnVector].incRefCount()
            }
            new ColumnarBatch(pyInputColumns.toArray, groupedBatch.numRows())
          }
        }

      // Third, sends to Python to execute the aggregate and returns the result.
      if (pyInputIter.hasNext) {
        // Launch Python workers only when the data is not empty.
        val pyRunner = new GpuArrowPythonRunner(
          pyFuncs,
          PythonEvalType.SQL_GROUPED_AGG_PANDAS_UDF,
          argOffsets,
          aggInputSchema,
          sessionLocalTimeZone,
          pythonRunnerConf,
          // The whole group data should be written in a single call, so here is unlimited
          Int.MaxValue,
          () => queue.finish(),
          pyOutSchema)

        val pyOutputIterator = pyRunner.compute(pyInputIter, context.partitionId(), context)

        val combinedAttrs = groupingExpressions.map(_.toAttribute) ++ pyOutAttributes
        val resultRefs = GpuBindReferences.bindGpuReferences(resultExprs, combinedAttrs)

        // Gets the combined batch for each group and projects to the output schema.
        new CombiningIterator(queue, pyOutputIterator, pyRunner, mNumOutputRows,
            mNumOutputBatches) {

          private val retBatchCache = new ArrayBuffer[ColumnarBatch]
          context.addTaskCompletionListener[Unit](_ => retBatchCache.foreach(_.close()))

          override def next(): ColumnarBatch = {
            val numKeyRows = inputBatchQueue.peekBatchSize
            // The key batch cached contains the key rows for all the groups, but each result
            // batch from Python is only for one group. So needs to concatenate the result
            // batches before being combined.
            // The size of `retBatchCache` is equal to the total row number of the batches
            // collected, because each result batch contains only one row for the aggregate
            // result.
            // (The memory might be an issue if there are too many small groups. One possible
            //  solution is to reduce the batch size.)
            while(retBatchCache.size < numKeyRows) {
              retBatchCache += pythonOutputIter.next()
            }
            assert(retBatchCache.size == numKeyRows,
              "Row numbers of the key batch and the result batch do not match.")

            val concatBatch = ConcatAndConsumeAll.buildNonEmptyBatch(
              retBatchCache.toArray, pyOutSchema)
            retBatchCache.clear()
            withResource(concatBatch) { retBatch =>
              withResource(inputBatchQueue.remove()) { origBatch =>
                numOutputBatches += 1
                numOutputRows += numKeyRows
                withResource(combine(origBatch, retBatch)) { combined =>
                  GpuProjectExec.project(combined, resultRefs)
                }
              }
            }
          }
        }
      } else {
        // Empty partition, returns it directly
        inputIter
      }
    }
  } // end of doExecuteColumnar

}
