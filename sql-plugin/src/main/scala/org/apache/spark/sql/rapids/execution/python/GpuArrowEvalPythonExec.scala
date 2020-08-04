/*
 * Copyright (c) 2019-2020, NVIDIA CORPORATION.
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

import ai.rapids.cudf.Cuda
import com.nvidia.spark.rapids._
import com.nvidia.spark.rapids.python.PythonConfEntries._
import com.nvidia.spark.rapids.python.PythonWorkerSemaphore

import scala.collection.JavaConverters._

import org.apache.spark.{SparkEnv, TaskContext}
import org.apache.spark.api.python.ChainedPythonFunctions
import org.apache.spark.internal.config.{CPUS_PER_TASK, EXECUTOR_CORES}
import org.apache.spark.internal.config.Python._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, PythonUDF}
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.python.{ArrowEvalPythonExec, ArrowPythonRunner, BatchIterator, EvalPythonExec}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.ArrowUtils
import org.apache.spark.sql.vectorized.ColumnarBatch

class GpuArrowPythonExecMeta(
     arrowPy: ArrowEvalPythonExec,
     conf: RapidsConf,
     parent: Option[RapidsMeta[_, _, _]],
     rule: ConfKeysAndIncompat)
  extends SparkPlanMeta[ArrowEvalPythonExec](arrowPy, conf, parent, rule) {

  // Handle the child expressions(Python UDF) ourselves.
  override val childExprs: Seq[BaseExprMeta[_]] = Seq.empty

  override def convertToGpu(): GpuExec =
    GpuArrowEvalPythonExec(
      wrapped.udfs, wrapped.resultAttrs, wrapped.child, wrapped.evalType
    )
}

/**
 * A physical plan of GPU version that evaluates a PythonUDF where data saved
 * in arrow format.
 *
 */
case class GpuArrowEvalPythonExec(
    udfs: Seq[PythonUDF],
    resultAttrs: Seq[Attribute],
    override val child: SparkPlan,
    evalType: Int)
  extends EvalPythonExec(udfs, resultAttrs, child) with GpuExec {

  override def supportsColumnar = false
  override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    // TBD
    super.doExecuteColumnar()
  }

  private lazy val rapidsConf = new RapidsConf(SparkEnv.get.conf)
  private lazy val gpuId = GpuDeviceManager.getDeviceId()
    .getOrElse(throw new IllegalStateException("No gpu id!"))
    .toString
  private lazy val isPythonPooledMemEnabled = rapidsConf.get(PYTHON_POOLED_MEM)
    .getOrElse(rapidsConf.isPooledMemEnabled)
    .toString
  private lazy val isPythonUvmEnabled = rapidsConf.get(PYTHON_UVM_ENABLED)
    .getOrElse(rapidsConf.isUvmEnabled)
    .toString
  private lazy val initAllocPerWorker = {
    val info = Cuda.memGetInfo()
    // Total size that python workers can use.
    // If users set it, use it. Otherwise calculate from the conf for JVM side.
    // FIXME Shall we reserve a little portion (here 0.02) for floating-point error ?
    val initFactionTotal = rapidsConf.get(PYTHON_RMM_ALLOC_FRACTION)
      .getOrElse(1 - 0.02 - rapidsConf.rmmAllocFraction)
    val initAllocTotal = (initFactionTotal * info.total).toLong
    if (initAllocTotal > info.free) {
      logWarning(s"Initial RMM allocation(${initAllocTotal / 1024 / 1024.0} MB) for " +
        s"all the Python workers is larger than free memory(${info.free / 1024 / 1024.0} MB)")
    } else {
      logDebug(s"Configure ${initAllocTotal / 1024 / 1024.0}MB GPU memory for " +
        s"all the Python workers.")
    }
    // Calculate the pool size for each Python worker.
    val concurrentPythonWorkers = rapidsConf.get(CONCURRENT_PYTHON_WORKERS)
    if (concurrentPythonWorkers > 0) {
      initAllocTotal / concurrentPythonWorkers
    } else {
      // When semaphore is disabled, which should replace the `concurrentPythonWorkers` in the
      // pool size computation, the number of running tasks or the number of cpu/task slots in
      // an executor ?
      // Here choose the later one for simplicity and it can cover most cases.
      // Even when the former is smaller than the later one, the choice would still work.
      // FIXME How about AQE or how to get the number of running tasks in an executor?
      val sparkConf = SparkEnv.get.conf
      initAllocTotal / (sparkConf.get(EXECUTOR_CORES) / sparkConf.get(CPUS_PER_TASK))
    }
  }.toString

  private def injectGpuInfo(funcs: Seq[ChainedPythonFunctions]): Unit = {
    // Insert GPU related env(s) into `envVars` for all the PythonFunction(s).
    // Yes `PythonRunner` will only use the first one, but just make sure it will
    // take effect no matter the order changes or not.
    funcs.foreach(_.funcs.foreach { pyF =>
      pyF.envVars.put("CUDA_VISIBLE_DEVICES", gpuId)
      pyF.envVars.put("RAPIDS_SQL_ENABLED", rapidsConf.isSqlEnabled.toString)
      pyF.envVars.put("RAPIDS_UVM_ENABLED", isPythonUvmEnabled)
      pyF.envVars.put("RAPIDS_POOLED_MEM_ENABLED", isPythonPooledMemEnabled)
      pyF.envVars.put("RAPIDS_POOLED_MEM_SIZE", initAllocPerWorker)
      // now max size is the same with initial size, may use a different one later.
      pyF.envVars.put("RAPIDS_POOLED_MEM_MAX_SIZE", initAllocPerWorker)
    })

    // Check and overwrite the related conf(s):
    // - pyspark worker module.
    //   For GPU case, need to customize the worker module for the GPU initialization
    //   and de-initialization
    val sparkConf = SparkEnv.get.conf
    sparkConf.get(PYTHON_WORKER_MODULE).foreach(value =>
      if (value != "rapids.worker") {
        logWarning(s"Found PySpark worker is set to '$value', overwrite it to 'rapids.worker'.")
      }
    )
    sparkConf.set(PYTHON_WORKER_MODULE, "rapids.worker")
    logWarning("Disable python daemon to enable customized 'rapids.worker'.")
    sparkConf.set(PYTHON_USE_DAEMON, false)
  }

  // 'evaluate' is from EvalPythonExec.
  protected override def evaluate(
      funcs: Seq[ChainedPythonFunctions],
      argOffsets: Array[Array[Int]],
      iter: Iterator[InternalRow],
      schema: StructType,
      context: TaskContext): Iterator[InternalRow] = {

    injectGpuInfo(funcs)
    PythonWorkerSemaphore.acquireIfNecessary(context)
    doEvaluate(funcs, argOffsets, iter, schema, context)
  }

  /**
    * Code below is copied from `ArrowEvalPythonExec`
    */
  private val batchSize = conf.arrowMaxRecordsPerBatch
  private val sessionLocalTimeZone = conf.sessionLocalTimeZone
  private val pythonRunnerConf = ArrowUtils.getPythonRunnerConfMap(conf)

  private def doEvaluate(
      funcs: Seq[ChainedPythonFunctions],
      argOffsets: Array[Array[Int]],
      iter: Iterator[InternalRow],
      schema: StructType,
      context: TaskContext): Iterator[InternalRow] = {

    val outputTypes = output.drop(child.output.length).map(_.dataType)

    // DO NOT use iter.grouped(). See BatchIterator.
    val batchIter = if (batchSize > 0) new BatchIterator(iter, batchSize) else Iterator(iter)

    val columnarBatchIter = new ArrowPythonRunner(
      funcs,
      evalType,
      argOffsets,
      schema,
      sessionLocalTimeZone,
      pythonRunnerConf).compute(batchIter, context.partitionId(), context)

    columnarBatchIter.flatMap { batch =>
      val actualDataTypes = (0 until batch.numCols()).map(i => batch.column(i).dataType())
      assert(outputTypes == actualDataTypes, "Invalid schema from pandas_udf: " +
        s"expected ${outputTypes.mkString(", ")}, got ${actualDataTypes.mkString(", ")}")
      batch.rowIterator.asScala
    }
  }
}
