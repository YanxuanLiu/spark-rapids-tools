/*
 * Copyright (c) 2022-2025, NVIDIA CORPORATION.
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

package com.nvidia.spark.rapids.tool.tuning

import java.util

import scala.beans.BeanProperty
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.matching.Regex

import com.nvidia.spark.rapids.tool.{AppSummaryInfoBaseProvider, ClusterSizingStrategy, ConstantGpuCountStrategy, GpuDevice, Platform, PlatformFactory}
import com.nvidia.spark.rapids.tool.profiling._
import org.yaml.snakeyaml.constructor.ConstructorException

import org.apache.spark.internal.Logging
import org.apache.spark.network.util.ByteUnit
import org.apache.spark.sql.rapids.tool.ToolUtils
import org.apache.spark.sql.rapids.tool.util.{PropertiesLoader, StringUtils, ValidatableProperties, WebCrawlerUtil}

/**
 * A wrapper class that stores all the GPU properties.
 * The BeanProperty enables loading and parsing the YAML formatted content using the
 * Constructor SnakeYaml approach.
 */
class GpuWorkerProps(
    @BeanProperty var memory: String,
    @BeanProperty var count: Int,
    private var name: String) extends ValidatableProperties {

  var device: Option[GpuDevice] = None

  def this() = {
    this("0m", 0, "")
  }

  /**
   * Define custom getter for GPU name.
   */
  def getName: String = name

  /**
   * Define custom setter for GPU name to ensure it is always in lower case.
   *
   * @see [[com.nvidia.spark.rapids.tool.GpuTypes]]
   */
  def setName(newName: String): Unit = {
    this.name = newName.toLowerCase
  }

  override def validate(): Unit = {
    if (getName != null && getName.nonEmpty) {
      device = GpuDevice.createInstance(getName).orElse {
        val supportedGpus = GpuDevice.deviceMap.keys.mkString(", ")
        throw new IllegalArgumentException(
          s"Unsupported GPU type provided: $getName. Supported GPU types: $supportedGpus")
      }
    }
  }

  def isMissingInfo: Boolean = {
    memory == null || memory.isEmpty || name == null || name.isEmpty ||
       count == 0 || memory.startsWith("0") || name == "None"
  }
  def isEmpty: Boolean = {
    count == 0 && (memory == null || memory.isEmpty || memory.startsWith("0")) &&
      (name == null || name.isEmpty || name == "None")
  }
  /**
   * If the GPU count is missing, it will set 1 as a default value
   *
   * @return true if the value has been updated.
   */
  def setDefaultGpuCountIfMissing(autoTunerConfigsProvider: AutoTunerConfigsProvider): Boolean = {
    // TODO - do we want to recommend 1 or base it on core count?  32 cores to 1 gpu may be to much.
    if (count == 0) {
      count = autoTunerConfigsProvider.DEF_WORKER_GPU_COUNT
      true
    } else {
      false
    }
  }
  def setDefaultGpuNameIfMissing(platform: Platform): Boolean = {
    if (!GpuDevice.deviceMap.contains(name)) {
      name = platform.gpuDevice.getOrElse(platform.defaultGpuDevice).toString
      true
    } else {
      false
    }
  }

  /**
   * If the GPU memory is missing, it will sets a default valued based on the GPU device type.
   * If it is still missing, it sets a default to 15109m (T4).
   *
   * @return true if the value has been updated.
   */
  def setDefaultGpuMemIfMissing(): Boolean = {
    if (memory == null || memory.isEmpty || memory.startsWith("0")) {
      memory = try {
        GpuDevice.createInstance(getName).getOrElse(GpuDevice.DEFAULT).getMemory
      } catch {
        case _: IllegalArgumentException => GpuDevice.DEFAULT.getMemory
      }
      true
    } else {
      false
    }
  }

  /**
   * Sets any missing field and return a list of messages to indicate what has been updated.
   * @return a list containing information of what was missing and the default value that has been
   *         used to initialize the field.
   */
  def setMissingFields(platform: Platform,
      autoTunerConfigsProvider: AutoTunerConfigsProvider): Seq[String] = {
    val res = new mutable.ListBuffer[String]()
    if (setDefaultGpuCountIfMissing(autoTunerConfigsProvider)) {
      res += s"GPU count is missing. Setting default to $getCount."
    }
    if (setDefaultGpuNameIfMissing(platform)) {
      res += s"GPU device is missing. Setting default to $getName."
    }
    if (setDefaultGpuMemIfMissing()) {
      res += s"GPU memory is missing. Setting default to $getMemory."
    }
    res
  }

  override def toString: String =
    s"{count: $count, memory: $memory, name: $name}"
}

/**
 * A wrapper class that stores all the system properties.
 * The BeanProperty enables loading and parsing the YAML formatted content using the
 * Constructor SnakeYaml approach.
 */
class SystemClusterProps(
    @BeanProperty var numCores: Int,
    @BeanProperty var memory: String,
    @BeanProperty var numWorkers: Int) {
  def this() = {
    this(0, "0m", 0)
  }
  def isMissingInfo: Boolean = {
    // keep for future expansion as we may add more fields later.
    numWorkers <= 0
  }
  def isEmpty: Boolean = {
    // consider the object incorrect if either numCores or memory are not set.
    memory == null || memory.isEmpty || numCores <= 0 || memory.startsWith("0")
  }
  def setDefaultNumWorkersIfMissing(autoTunerConfigsProvider: AutoTunerConfigsProvider): Boolean = {
    if (numWorkers <= 0) {
      numWorkers = autoTunerConfigsProvider.DEF_NUM_WORKERS
      true
    } else {
      false
    }
  }
  /**
   * Sets any missing field and return a list of messages to indicate what has been updated.
   * @return a list containing information of what was missing and the default value that has been
   *         used to initialize the field.
   */
  def setMissingFields(autoTunerConfigsProvider: AutoTunerConfigsProvider): Seq[String] = {
    val res = new mutable.ListBuffer[String]()
    if (setDefaultNumWorkersIfMissing(autoTunerConfigsProvider)) {
      res += s"Number of workers is missing. Setting default to $getNumWorkers."
    }
    res
  }
  override def toString: String =
    s"{numCores: $numCores, memory: $memory, numWorkers: $numWorkers}"
}

/**
 * A wrapper class that stores all the properties of the cluster.
 * The BeanProperty enables loading and parsing the YAML formatted content using the
 * Constructor SnakeYaml approach.
 *
 * @param system wrapper that includes the properties related to system information like cores and
 *               memory.
 * @param gpu wrapper that includes the properties related to GPU.
 * @param softwareProperties a set of software properties such as Spark properties.
 *                           The properties are typically loaded from the default cluster
 *                           configurations.
 */
class ClusterProperties(
    @BeanProperty var system: SystemClusterProps,
    @BeanProperty var gpu: GpuWorkerProps,
    @BeanProperty var softwareProperties: util.LinkedHashMap[String, String])
  extends ValidatableProperties {

  def this() = {
    this(new SystemClusterProps(), new GpuWorkerProps(), new util.LinkedHashMap[String, String]())
  }

  override def validate(): Unit = {
    gpu.validate()
  }

  def isEmpty: Boolean = {
    system.isEmpty && gpu.isEmpty
  }
  override def toString: String =
    s"{${system.toString}, ${gpu.toString}, $softwareProperties}"
}

/**
 * Represents different Spark master types.
 */
sealed trait SparkMaster {
  // Default executor memory to use in case not set by the user.
  val defaultExecutorMemoryMB: Long
}
case object Local extends SparkMaster {
  val defaultExecutorMemoryMB: Long = 0L
}
case object Yarn extends SparkMaster {
  val defaultExecutorMemoryMB: Long = 1024L
}
case object Kubernetes extends SparkMaster {
  val defaultExecutorMemoryMB: Long = 1024L
}
case object Standalone extends SparkMaster {
  // Would be the entire node memory by default
  val defaultExecutorMemoryMB: Long = 0L
}

object SparkMaster {
  def apply(master: Option[String]): Option[SparkMaster] = {
    master.flatMap {
      case url if url.contains("yarn") => Some(Yarn)
      case url if url.contains("k8s") => Some(Kubernetes)
      case url if url.contains("local") => Some(Local)
      case url if url.contains("spark://") => Some(Standalone)
      case _ => None
    }
  }
}

/**
 * AutoTuner module that uses event logs and worker's system properties to recommend Spark
 * RAPIDS configuration based on heuristics.
 *
 * Example:
 * a. Success:
 *    Input:
 *      system:
 *        num_cores: 64
 *        cpu_arch: x86_64
 *        memory: 512gb
 *        free_disk_space: 800gb
 *        time_zone: America/Los_Angeles
 *        num_workers: 4
 *      gpu:
 *        count: 8
 *        memory: 32gb
 *        name: NVIDIA V100
 *      softwareProperties:
 *        spark.driver.maxResultSize: 7680m
 *        spark.driver.memory: 15360m
 *        spark.executor.cores: '8'
 *        spark.executor.instances: '2'
 *        spark.executor.memory: 47222m
 *        spark.executorEnv.OPENBLAS_NUM_THREADS: '1'
 *        spark.extraListeners: com.google.cloud.spark.performance.DataprocMetricsListener
 *        spark.scheduler.mode: FAIR
 *        spark.sql.cbo.enabled: 'true'
 *        spark.ui.port: '0'
 *        spark.yarn.am.memory: 640m
 *
 *    Output:
 *       Spark Properties:
 *       --conf spark.executor.cores=8
 *       --conf spark.executor.instances=20
 *       --conf spark.executor.memory=16384m
 *       --conf spark.executor.memoryOverhead=5734m
 *       --conf spark.rapids.memory.pinnedPool.size=4096m
 *       --conf spark.rapids.sql.concurrentGpuTasks=2
 *       --conf spark.sql.files.maxPartitionBytes=4096m
 *       --conf spark.task.resource.gpu.amount=0.125
 *
 *       Comments:
 *       - 'spark.rapids.sql.concurrentGpuTasks' was not set.
 *       - 'spark.executor.memoryOverhead' was not set.
 *       - 'spark.rapids.memory.pinnedPool.size' was not set.
 *       - 'spark.sql.adaptive.enabled' should be enabled for better performance.
 *
 * b. Failure:
 *    Input: Incorrect File
 *    Output:
 *      Cannot recommend properties. See Comments.
 *
 *      Comments:
 *      - java.io.FileNotFoundException: File worker_info.yaml does not exist
 *      - 'spark.executor.memory' should be set to at least 2GB/core.
 *      - 'spark.executor.instances' should be set to (gpuCount * numWorkers).
 *      - 'spark.task.resource.gpu.amount' should be set to Max(1, (numCores / gpuCount)).
 *      - 'spark.rapids.sql.concurrentGpuTasks' should be set to Min(4, (gpuMemory / 7.5G)).
 *      - 'spark.rapids.memory.pinnedPool.size' should be set to 2048m.
 *      - 'spark.sql.adaptive.enabled' should be enabled for better performance.
 *
 * @param clusterProps The cluster properties including cores, mem, GPU, and software
 *                     (see [[ClusterProperties]]).
 * @param appInfoProvider the container holding the profiling result.
 */
class AutoTuner(
    val clusterProps: ClusterProperties,
    val appInfoProvider: AppSummaryInfoBaseProvider,
    val platform: Platform,
    val driverInfoProvider: DriverLogInfoProvider,
    val autoTunerConfigsProvider: AutoTunerConfigsProvider)
  extends Logging {

  var comments = new mutable.ListBuffer[String]()
  var recommendations: mutable.LinkedHashMap[String, TuningEntryTrait] =
    mutable.LinkedHashMap[String, TuningEntryTrait]()
  // list of recommendations to be skipped for recommendations
  // Note that the recommendations will be computed anyway to avoid breaking dependencies.
  private val skippedRecommendations: mutable.HashSet[String] = mutable.HashSet[String]()
  // list of recommendations having the calculations disabled, and only depend on default values
  protected val limitedLogicRecommendations: mutable.HashSet[String] = mutable.HashSet[String]()
  // When enabled, the profiler recommendations should only include updated settings.
  private var filterByUpdatedPropertiesEnabled: Boolean = true

  private lazy val sparkMaster: Option[SparkMaster] = {
    SparkMaster(appInfoProvider.getProperty("spark.master"))
  }

  private def isCalculationEnabled(prop: String) : Boolean = {
    !limitedLogicRecommendations.contains(prop)
  }

  /**
   * Used to get the property value from the source properties
   * (i.e. from app info and cluster properties)
   */
  private def getPropertyValueFromSource(key: String): Option[String] = {
    getAllSourceProperties.get(key)
  }

  /**
   * Used to get the property value in the following priority order:
   * 1. Recommendations (this also includes the user-enforced properties)
   * 2. Source Spark properties (i.e. from app info and cluster properties)
   */
  protected def getPropertyValue(key: String): Option[String] = {
    AutoTuner.getCombinedPropertyFn(recommendations, getAllSourceProperties)(key)
  }

  /**
   * Get combined properties from the app info and cluster properties.
   */
  private lazy val getAllSourceProperties: Map[String, String] = {
    // the cluster properties override the app properties as
    // it is provided by the user.
    appInfoProvider.getAllProperties ++ clusterProps.getSoftwareProperties.asScala
  }

  def initRecommendations(): Unit = {
    autoTunerConfigsProvider.recommendationsTarget.foreach { key =>
      // no need to add new records if they are missing from props
      getPropertyValueFromSource(key).foreach { propVal =>
        val recommendationVal = TuningEntry.build(key, Option(propVal), None)
        recommendations(key) = recommendationVal
      }
    }
    // Add the enforced properties to the recommendations.
    platform.userEnforcedRecommendations.foreach {
      case (key, value) =>
        val recomRecord = recommendations.getOrElseUpdate(key,
          TuningEntry.build(key, getPropertyValueFromSource(key), None))
        recomRecord.setRecommendedValue(value)
        appendComment(autoTunerConfigsProvider.getEnforcedPropertyComment(key))
    }
  }

  /**
   * Add default missing comments from the tuningEntry table if any.
   * @param key the property set by the autotuner.
   */
  private def appendMissingComment(key: String): Unit = {
    val missingComment = TuningEntryDefinition.TUNING_TABLE.get(key)
      .flatMap(_.getMissingComment())
      .getOrElse(s"was not set.")
    appendComment(s"'$key' $missingComment")
  }

  /**
   * Append a comment to the list by looking up the persistent comment if any in the tuningEntry
   * table.
   * @param key the property set by the autotuner.
   */
  private def appendPersistentComment(key: String): Unit = {
    TuningEntryDefinition.TUNING_TABLE.get(key).foreach { eDef =>
      eDef.getPersistentComment().foreach { comment =>
        appendComment(s"'$key' $comment")
      }
    }
  }

  /**
   * Append a comment to the list by looking up the updated comment if any in the tuningEntry
   * table. If it is not defined in the table, then add nothing.
   * @param key the property set by the autotuner.
   */
  private def appendUpdatedComment(key: String): Unit = {
    TuningEntryDefinition.TUNING_TABLE.get(key).foreach { eDef =>
      eDef.getUpdatedComment().foreach { comment =>
        appendComment(s"'$key' $comment")
      }
    }
  }

  def appendRecommendation(key: String, value: String): Unit = {
    if (skippedRecommendations.contains(key)) {
      // do not do anything if the recommendations should be skipped
      return
    }
    if (platform.getUserEnforcedSparkProperty(key).isDefined) {
      // If the property is enforced by the user, the recommendation should be
      // skipped as we have already added it during the initialization.
      return
    }
    // Update the recommendation entry or update the existing one.
    val recomRecord = recommendations.getOrElseUpdate(key,
      TuningEntry.build(key, getPropertyValue(key), None))
    // if the value is not null, then proceed to add the recommendation.
    Option(value).foreach { nonNullValue =>
      recomRecord.setRecommendedValue(nonNullValue)
      recomRecord.getOriginalValue match {
        case None =>
          // add missing comment if any
          appendMissingComment(key)
        case Some(originalValue) if originalValue != recomRecord.getTuneValue() =>
          // add updated comment if any
          appendUpdatedComment(key)
        case _ =>
          // do not add any comment if the tuned value is the same as the original value
      }
      // add the persistent comment if any.
      appendPersistentComment(key)
    }
  }

  /**
   * Safely appends the recommendation to the given key.
   * It skips if the value is 0.
   */
  def appendRecommendation(key: String, value: Int): Unit = {
    if (value > 0) {
      appendRecommendation(key: String, s"$value")
    }
  }

  /**
   * Safely appends the recommendation to the given key.
   * It skips if the value is 0.0.
   */
  def appendRecommendation(key: String, value: Double): Unit = {
    if (value > 0.0) {
      appendRecommendation(key: String, s"$value")
    }
  }
  /**
   * Safely appends the recommendation to the given key.
   * It appends "m" to the string value. It skips if the value is 0 or null.
   */
  def appendRecommendationForMemoryMB(key: String, value: String): Unit = {
    if (value != null && value.toDouble > 0.0) {
      appendRecommendation(key, s"${value}m")
    }
  }

  /**
   * Try to figure out the recommended instance type to use and set
   * the executor cores and instances based on that instance type.
   * Returns None if the platform doesn't support specific instance types.
   */
  private def configureGPURecommendedInstanceType(): Unit = {
    platform.createRecommendedGpuClusterInfo(recommendations, getAllSourceProperties,
      autoTunerConfigsProvider.recommendedClusterSizingStrategy)
    platform.recommendedClusterInfo.foreach { gpuClusterRec =>
      // TODO: Should we skip recommendation if cores per executor is lower than a min value?
      appendRecommendation("spark.executor.cores", gpuClusterRec.coresPerExecutor)
      if (gpuClusterRec.numExecutors > 0) {
        appendRecommendation("spark.executor.instances", gpuClusterRec.numExecutors)
      }
    }
  }

  def calcNumExecutorCores: Int = {
    val executorCores = platform.recommendedClusterInfo.map(_.coresPerExecutor).getOrElse(1)
    Math.max(1, executorCores)
  }

  /**
   * Recommendation for 'spark.rapids.sql.concurrentGpuTasks' based on gpu memory.
   * Assumption - cluster properties were updated to have a default values if missing.
   */
  private def calcGpuConcTasks(): Long = {
    Math.min(autoTunerConfigsProvider.MAX_CONC_GPU_TASKS,
      platform.recommendedGpuDevice.getGpuConcTasks)
  }

  /**
   * Recommendation for initial heap size based on certain amount of memory per core.
   * Note that we will later reduce this if needed for off heap memory.
   */
  def calcInitialExecutorHeap(executorContainerMemCalculator: () => Double,
      numExecCores: Int): Long = {
    val maxExecutorHeap = Math.max(0, executorContainerMemCalculator()).toInt
    // give up to 2GB of heap to each executor core
    // TODO - revisit this in future as we could let heap be bigger
    Math.min(maxExecutorHeap, autoTunerConfigsProvider.DEF_HEAP_PER_CORE_MB * numExecCores)
  }

  /**
   * Recommendation for maxBytesInFlight.
   *
   * TODO: To be removed in the future https://github.com/NVIDIA/spark-rapids-tools/issues/1710
   */
  private lazy val recommendedMaxBytesInFlight: Long = {
    platform.getUserEnforcedSparkProperty("spark.rapids.shuffle.multiThreaded.maxBytesInFlight")
      .map(StringUtils.convertToMB(_, Some(ByteUnit.BYTE)))
      .getOrElse(autoTunerConfigsProvider.DEF_MAX_BYTES_IN_FLIGHT_MB)
  }

  private case class MemorySettings(
    executorHeap: Option[Long],
    executorMemOverhead: Option[Long],
    pinnedMem: Option[Long],
    spillMem: Option[Long]
  ) {
    def hasAnyMemorySettings: Boolean = {
      executorMemOverhead.isDefined || pinnedMem.isDefined || spillMem.isDefined
    }
  }

  private lazy val userEnforcedMemorySettings: MemorySettings = {
    val executorHeap = platform.getUserEnforcedSparkProperty("spark.executor.memory")
      .map(StringUtils.convertToMB(_, Some(ByteUnit.BYTE)))
    val executorMemOverhead = platform.getUserEnforcedSparkProperty("spark.executor.memoryOverhead")
      .map(StringUtils.convertToMB(_, Some(ByteUnit.BYTE)))
    val pinnedMem = platform.getUserEnforcedSparkProperty("spark.rapids.memory.pinnedPool.size")
      .map(StringUtils.convertToMB(_, Some(ByteUnit.BYTE)))
    val spillMem = platform.getUserEnforcedSparkProperty("spark.rapids.memory.spillPool.size")
      .map(StringUtils.convertToMB(_, Some(ByteUnit.BYTE)))
    MemorySettings(executorHeap, executorMemOverhead, pinnedMem, spillMem)
  }

  private def generateInsufficientMemoryComment(
      executorHeap: Long,
      finalExecutorMemOverhead: Long,
      sparkOffHeapMemMB: Long,
      pySparkMemMB: Long): String = {
    val minTotalExecMemRequired = (
      // Calculate total system memory needed by dividing executor memory by usable fraction.
      // Accounts for memory reserved by the container manager (e.g., YARN).
      (executorHeap + finalExecutorMemOverhead + sparkOffHeapMemMB + pySparkMemMB) /
        platform.fractionOfSystemMemoryForExecutors
      ).toLong
    autoTunerConfigsProvider.notEnoughMemComment(minTotalExecMemRequired)
  }

  // scalastyle:off line.size.limit
  /**
   * Calculates recommended memory settings for a Spark executor container.
   *
   * The total memory for the executor is the sum of:
   *   executorHeap (spark.executor.memory)
   *   + executorMemOverhead (spark.executor.memoryOverhead)
   *   + sparkOffHeapMemMB (spark.memory.offHeap.size)
   *   + pySparkMemMB (spark.executor.pyspark.memory)
   *
   * Note: In the below examples, `0.8` is the fraction of the physical system memory
   * that is available to Spark executors (0.2 is reserved by Dataproc YARN).
   *
   * Example 1: g2-standard-8 machine (32 GB total memory) — Just enough memory
   *   - actualMemForExec =  32 GB * 0.8 = 25.6 GB
   *   - executorHeap = 16 GB
   *   - sparkOffHeapMemMB = 4 GB
   *   - execMemLeft = 25.6 GB - 16 GB - 4 GB = 5.6 GB
   *   - minOverhead = 1.6 GB (10% of executor heap) + 2 GB (min pinned) + 2 GB (min spill) = 5.6 GB
   *   - Since execMemLeft (5.6 GB) == minOverhead (5.6 GB), proceed with minimum memory recommendations:
   *   - Recommendation:
   *       - executorHeap = 16 GB, executorMemOverhead = 5.6 GB (with pinnedMem = 2 GB and spillMem = 2 GB)
   *
   * Example 2: g2-standard-16 machine (64 GB total memory) — Not enough memory
   *   - actualMemForExec = 64 GB * 0.8 = 51.2 GB
   *   - executorHeap = 32 GB
   *   - sparkOffHeapMemMB = 20 GB
   *   - execMemLeft = 51.2 GB - 32 GB - 20 GB = -0.8 GB
   *   - minOverhead = 2 GB (min pinned) + 2 GB (min spill) + 3.2 GB (10% of executor heap) = 7.2 GB
   *   - Since execMemLeft (-0.8 GB) < minOverhead (7.2 GB), do not proceed with recommendations
   *       - Add a warning comment indicating that the current setup is not optimal
   *           - minTotalExecMemRequired = (32 GB + 20 GB + 7.2 GB) / 0.8 = (59.2 GB / 0.8) = 74 GB (as we are using 80% of system memory)
   *           - Reduce off-heap size or use a larger machine with at least 74 GB system memory.
   *
   * Example 3: g2-standard-16 machine (64 GB total memory) — More memory available
   *   - actualMemForExec = 64 GB * 0.8 = 51.2 GB
   *   - executorHeap = 32 GB
   *   - sparkOffHeapMemMB = 10 GB
   *   - execMemLeft = 51.2 GB - 32 GB - 10 GB = 9.2 GB
   *   - minOverhead = 2 GB (min pinned) + 2 GB (min spill) + 3.2 GB (10% of executor heap) = 7.2 GB
   *   - Since execMemLeft (9.2 GB) > minOverhead (7.2 GB), proceed with recommendations.
   *       - Increase pinned and spill memory based on remaining memory (up to 4 GB max)
   *       - executorMemOverhead = 3 GB (pinned) + 3 GB (spill) + 3.2 GB = 9.2 GB
   *   - Recommendation:
   *       - executorHeap = 32 GB, executorMemOverhead = 9.2 GB (with pinnedMem = 3 GB and spillMem = 3 GB)
   *
   *
   * @param execHeapCalculator    Function that returns the executor heap size in MB
   * @param numExecutorCores      Number of executor cores
   * @param totalMemForExecExpr   Function that returns total memory available to the executor (MB)
   * @return Either a String with an error message if memory is insufficient,
   *         or a tuple containing:
   *           - pinned memory size (MB)
   *           - executor memory overhead size (MB)
   *           - executor heap size (MB)
   *           - boolean indicating if "maxBytesInFlight" should be set
   */
   // scalastyle:on line.size.limit
  private def calcOverallMemory(
      execHeapCalculator: () => Long,
      numExecutorCores: Int,
      totalMemForExecExpr: () => Double): Either[String, (MemorySettings, Boolean)] = {

    // Set executor heap using user enforced value or max of calculator result and 2GB/core
    val executorHeap = userEnforcedMemorySettings.executorHeap.getOrElse {
      Math.max(execHeapCalculator(),
        autoTunerConfigsProvider.DEF_HEAP_PER_CORE_MB * numExecutorCores)
    }
    // Our CSP instance map stores full node memory, but container managers
    // (e.g., YARN) may reserve a portion. Adjust to get the memory
    // actually available to the executor.
    val actualMemForExec = {
      totalMemForExecExpr.apply() * platform.fractionOfSystemMemoryForExecutors
    }.toLong
    // Get a combined spark properties function that includes user enforced properties
    // and properties from the event log
    val sparkPropertiesFn = AutoTuner.getCombinedPropertyFn(recommendations, getAllSourceProperties)
    val sparkOffHeapMemMB = platform.getSparkOffHeapMemoryMB(sparkPropertiesFn).getOrElse(0L)
    val pySparkMemMB = platform.getPySparkMemoryMB(sparkPropertiesFn).getOrElse(0L)
    val execMemLeft = actualMemForExec - executorHeap - sparkOffHeapMemMB - pySparkMemMB
    var setMaxBytesInFlight = false
    // reserve 10% of heap as memory overhead
    var executorMemOverhead = (
      executorHeap * autoTunerConfigsProvider.DEF_HEAP_OVERHEAD_FRACTION
    ).toLong
    val minOverhead = userEnforcedMemorySettings.executorMemOverhead.getOrElse {
      executorMemOverhead + autoTunerConfigsProvider.DEF_PINNED_MEMORY_MB +
        autoTunerConfigsProvider.DEF_SPILL_MEMORY_MB
    }
    logDebug(s"Memory calculations:  actualMemForExec=$actualMemForExec MB, " +
      s"executorHeap=$executorHeap MB, sparkOffHeapMem=$sparkOffHeapMemMB MB, " +
      s"pySparkMem=$pySparkMemMB MB minOverhead=$minOverhead MB")
    if (execMemLeft >= minOverhead) {
      // this is hopefully path in the majority of cases because CSPs generally have a good
      // memory to core ratio
      // Account for the setting of `maxBytesInFlight`
      if (numExecutorCores >= 16 && platform.isPlatformCSP &&
        execMemLeft >
          executorMemOverhead + recommendedMaxBytesInFlight +
            autoTunerConfigsProvider.DEF_PINNED_MEMORY_MB +
            autoTunerConfigsProvider.DEF_SPILL_MEMORY_MB) {
        executorMemOverhead += recommendedMaxBytesInFlight
        setMaxBytesInFlight = true
      }
      // Pinned memory uses any unused space up to 4GB. Spill memory is same size as pinned.
      var pinnedMem = userEnforcedMemorySettings.pinnedMem.getOrElse {
        Math.min(autoTunerConfigsProvider.MAX_PINNED_MEMORY_MB,
          (execMemLeft - executorMemOverhead) / 2)
      }
      // Spill storage is set to the pinned size by default. Its not guaranteed to use just pinned
      // memory though so the size worst case would be doesn't use any pinned memory and uses
      // all off heap memory.
      var spillMem = userEnforcedMemorySettings.spillMem.getOrElse(pinnedMem)
      var finalExecutorMemOverhead = userEnforcedMemorySettings.executorMemOverhead.getOrElse {
        executorMemOverhead + pinnedMem + spillMem
      }
      // Handle the case when the final executor memory overhead is larger than the
      // available memory left for the executor.
      if (execMemLeft < finalExecutorMemOverhead) {
        // If there is any user-enforced memory settings, add a warning comment
        // indicating that the current setup is not optimal and no memory-related
        // tunings are recommended.
        if (userEnforcedMemorySettings.hasAnyMemorySettings) {
          return Left(generateInsufficientMemoryComment(executorHeap, finalExecutorMemOverhead,
            sparkOffHeapMemMB, pySparkMemMB))
        }
        // Else update pinned and spill memory to use default values
        pinnedMem = autoTunerConfigsProvider.DEF_PINNED_MEMORY_MB
        spillMem = autoTunerConfigsProvider.DEF_SPILL_MEMORY_MB
        finalExecutorMemOverhead = executorMemOverhead + pinnedMem + spillMem
      }
      // Add recommendations for executor memory settings and a boolean for maxBytesInFlight
      Right((MemorySettings(Some(executorHeap), Some(finalExecutorMemOverhead), Some(pinnedMem),
        Some(spillMem)), setMaxBytesInFlight))
    } else {
      // Add a warning comment indicating that the current setup is not optimal
      // and no memory-related tunings are recommended.
      // TODO: For CSPs, we should recommend a different instance type.
      Left(generateInsufficientMemoryComment(executorHeap, minOverhead,
        sparkOffHeapMemMB, pySparkMemMB))
    }
  }

  private def configureShuffleReaderWriterNumThreads(numExecutorCores: Int): Unit = {
    // if on a CSP using blob store recommend more threads for certain sizes. This is based on
    // testing on customer jobs on Databricks
    // didn't test with > 16 thread so leave those as numExecutorCores
    if (numExecutorCores < 4) {
      // leave as defaults - should we reduce less then default of 20? need more testing
    } else if (numExecutorCores >= 4 && numExecutorCores < 16) {
      appendRecommendation("spark.rapids.shuffle.multiThreaded.reader.threads", 20)
      appendRecommendation("spark.rapids.shuffle.multiThreaded.writer.threads", 20)
    } else if (numExecutorCores >= 16 && numExecutorCores < 20 && platform.isPlatformCSP) {
      appendRecommendation("spark.rapids.shuffle.multiThreaded.reader.threads", 28)
      appendRecommendation("spark.rapids.shuffle.multiThreaded.writer.threads", 28)
    } else {
      val numThreads = (numExecutorCores * 1.5).toLong
      appendRecommendation("spark.rapids.shuffle.multiThreaded.reader.threads", numThreads.toInt)
      appendRecommendation("spark.rapids.shuffle.multiThreaded.writer.threads", numThreads.toInt)
    }
  }

  // Currently only applies many configs for CSPs where we have an idea what network/disk
  // configuration is like. On prem we don't know so don't set these for now.
  private def configureMultiThreadedReaders(numExecutorCores: Int,
      setMaxBytesInFlight: Boolean): Unit = {
    if (numExecutorCores < 4) {
      appendRecommendation("spark.rapids.sql.multiThreadedRead.numThreads",
        Math.max(20, numExecutorCores))
    } else if (numExecutorCores >= 4 && numExecutorCores < 8 && platform.isPlatformCSP) {
      appendRecommendation("spark.rapids.sql.multiThreadedRead.numThreads",
        Math.max(20, numExecutorCores))
    } else if (numExecutorCores >= 8 && numExecutorCores < 16 && platform.isPlatformCSP) {
      appendRecommendation("spark.rapids.sql.multiThreadedRead.numThreads",
        Math.max(40, numExecutorCores))
    } else if (numExecutorCores >= 16 && numExecutorCores < 20 && platform.isPlatformCSP) {
      appendRecommendation("spark.rapids.sql.multiThreadedRead.numThreads",
        Math.max(80, numExecutorCores))
      if (setMaxBytesInFlight) {
        appendRecommendation("spark.rapids.shuffle.multiThreaded.maxBytesInFlight", "4g")
      }
      appendRecommendation("spark.rapids.sql.reader.multithreaded.combine.sizeBytes",
        10 * 1024 * 1024)
      appendRecommendation("spark.rapids.sql.format.parquet.multithreaded.combine.waitTime", 1000)
    } else {
      val numThreads = (numExecutorCores * 2).toInt
      appendRecommendation("spark.rapids.sql.multiThreadedRead.numThreads",
        Math.max(20, numThreads).toInt)
      if (platform.isPlatformCSP) {
        if (setMaxBytesInFlight) {
          appendRecommendation("spark.rapids.shuffle.multiThreaded.maxBytesInFlight", "4g")
        }
        appendRecommendation("spark.rapids.sql.reader.multithreaded.combine.sizeBytes",
          10 * 1024 * 1024)
        appendRecommendation("spark.rapids.sql.format.parquet.multithreaded.combine.waitTime", 1000)
      }
    }
  }


  def calculateClusterLevelRecommendations(): Unit = {
    // only if we were able to figure out a node type to recommend do we make
    // specific recommendations
    if (platform.recommendedClusterInfo.isDefined) {
      val execCores = platform.recommendedClusterInfo.map(_.coresPerExecutor).getOrElse(1)
      // Set to low value for Spark RAPIDS usage as task parallelism will be honoured
      // by `spark.executor.cores`.
      appendRecommendation("spark.task.resource.gpu.amount",
        autoTunerConfigsProvider.DEF_TASK_GPU_RESOURCE_AMT)
      appendRecommendation("spark.rapids.sql.concurrentGpuTasks",
        calcGpuConcTasks().toInt)
      val availableMemPerExec =
        platform.recommendedNodeInstanceInfo.map(_.getMemoryPerExec).getOrElse(0.0)
      val shouldSetMaxBytesInFlight = if (availableMemPerExec > 0.0) {
        val availableMemPerExecExpr = () => availableMemPerExec
        val executorHeap = calcInitialExecutorHeap(availableMemPerExecExpr, execCores)
        val executorHeapExpr = () => executorHeap
        calcOverallMemory(executorHeapExpr, execCores, availableMemPerExecExpr) match {
          case Right((recomMemorySettings: MemorySettings, setMaxBytesInFlight)) =>
            // Sufficient memory available, proceed with recommendations
            appendRecommendationForMemoryMB("spark.rapids.memory.pinnedPool.size",
              s"${recomMemorySettings.pinnedMem.get}")
            // scalastyle:off line.size.limit
            // For YARN and Kubernetes, we need to set the executor memory overhead
            // Ref: https://spark.apache.org/docs/latest/configuration.html#:~:text=This%20option%20is%20currently%20supported%20on%20YARN%20and%20Kubernetes.
            // scalastyle:on line.size.limit
            if (sparkMaster.contains(Yarn) || sparkMaster.contains(Kubernetes)) {
              appendRecommendationForMemoryMB("spark.executor.memoryOverhead",
                s"${recomMemorySettings.executorMemOverhead.get}")
            }
            appendRecommendationForMemoryMB("spark.executor.memory",
              s"${recomMemorySettings.executorHeap.get}")
            setMaxBytesInFlight
          case Left(notEnoughMemComment) =>
            // Not enough memory available, add warning comments
            appendComment(notEnoughMemComment)
            appendComment("spark.rapids.memory.pinnedPool.size",
              autoTunerConfigsProvider.notEnoughMemCommentForKey(
                "spark.rapids.memory.pinnedPool.size"))
            if (sparkMaster.contains(Yarn) || sparkMaster.contains(Kubernetes)) {
              appendComment("spark.executor.memoryOverhead",
                autoTunerConfigsProvider.notEnoughMemCommentForKey(
                  "spark.executor.memoryOverhead"))
            }
            appendComment("spark.executor.memory",
              autoTunerConfigsProvider.notEnoughMemCommentForKey(
                "spark.executor.memory"))
            false
        }
      } else {
        logInfo("Available memory per exec is not specified")
        addMissingMemoryComments()
        false
      }
      configureShuffleReaderWriterNumThreads(execCores)
      configureMultiThreadedReaders(execCores, shouldSetMaxBytesInFlight)
      // TODO: Should we recommend AQE even if cluster properties are not enabled?
      recommendAQEProperties()
    } else {
      addDefaultComments()
    }
    appendRecommendation("spark.rapids.sql.batchSizeBytes",
      autoTunerConfigsProvider.BATCH_SIZE_BYTES)
    appendRecommendation("spark.locality.wait", "0")
  }

  def calculateJobLevelRecommendations(): Unit = {
    // TODO - do we do anything with 200 shuffle partitions or maybe if its close
    // set the Spark config  spark.shuffle.sort.bypassMergeThreshold
    getShuffleManagerClassName match {
      case Right(smClassName) => appendRecommendation("spark.shuffle.manager", smClassName)
      case Left(comment) => appendComment("spark.shuffle.manager", comment)
    }
    appendComment(autoTunerConfigsProvider.classPathComments("rapids.shuffle.jars"))
    recommendFileCache()
    recommendMaxPartitionBytes()
    recommendShufflePartitions()
    recommendKryoSerializerSetting()
    recommendGCProperty()
    if (platform.requirePathRecommendations) {
      recommendClassPathEntries()
    }
    recommendSystemProperties()
  }

  // if the user set the serializer to use Kryo, make sure we recommend using the GPU version
  // of it.
  def recommendKryoSerializerSetting(): Unit = {
    getPropertyValue("spark.serializer")
      .filter(_.contains("org.apache.spark.serializer.KryoSerializer")).foreach { _ =>
      // Logic:
      // - Trim whitespace, filter out empty entries and remove duplicates.
      // - Finally, append the GPU Kryo registrator to the existing set of registrators
      // Note:
      //  - ListSet preserves the original order of registrators
      // Example:
      //  property: "spark.kryo.registrator=reg1, reg2,, reg1"
      //  existingRegistrators: ListSet("reg1", "reg2")
      //  recommendation: "spark.kryo.registrator=reg1,reg2,GpuKryoRegistrator"
      val existingRegistrators = getPropertyValue("spark.kryo.registrator")
        .map(v => v.split(",").map(_.trim).filter(_.nonEmpty))
        .getOrElse(Array.empty)
        .to[scala.collection.immutable.ListSet]
      appendRecommendation("spark.kryo.registrator",
        (existingRegistrators + autoTunerConfigsProvider.GPU_KRYO_SERIALIZER_CLASS).mkString(",")
      )
      // set the kryo serializer buffer size to prevent OOMs
      val desiredBufferMax = autoTunerConfigsProvider.KRYO_SERIALIZER_BUFFER_MAX_MB
      val currentBufferMaxMb = getPropertyValue("spark.kryoserializer.buffer.max")
        .map(StringUtils.convertToMB(_, Some(ByteUnit.MiB)))
        .getOrElse(0L)
      if (currentBufferMaxMb < desiredBufferMax) {
        appendRecommendationForMemoryMB("spark.kryoserializer.buffer.max", s"$desiredBufferMax")
      }
    }
  }

  /**
   * Resolves the RapidsShuffleManager class name based on the Spark version.
   * If a valid class name is not found, an error message is returned.
   *
   * Example:
   * sparkVersion: "3.2.0-amzn-1"
   * return: Right("com.nvidia.spark.rapids.spark320.RapidsShuffleManager")
   *
   * sparkVersion: "3.1.2"
   * return: Left("Cannot recommend RAPIDS Shuffle Manager for unsupported '3.1.2' version.")
   *
   * @return Either an error message (Left) or the RapidsShuffleManager class name (Right)
   */
  def getShuffleManagerClassName: Either[String, String] = {
    appInfoProvider.getSparkVersion match {
      case Some(sparkVersion) =>
        platform.getShuffleManagerVersion(sparkVersion) match {
          case Some(smVersion) =>
            Right(autoTunerConfigsProvider.buildShuffleManagerClassName(smVersion))
          case None =>
            Left(autoTunerConfigsProvider.shuffleManagerCommentForUnsupportedVersion(
              sparkVersion, platform))
        }
      case None =>
        Left(autoTunerConfigsProvider.shuffleManagerCommentForMissingVersion)
    }
  }

  /**
   * If the cluster worker-info is missing entries (i.e., CPU and GPU count), it sets the entries
   * to default values. For each default value, a comment is added to the [[comments]].
   */
  def configureClusterPropDefaults: Unit = {
    if (!clusterProps.system.isEmpty) {
      if (clusterProps.system.isMissingInfo) {
        clusterProps.system.setMissingFields(autoTunerConfigsProvider)
          .foreach(m => appendComment(m))
      }
      if (clusterProps.gpu.isMissingInfo) {
        clusterProps.gpu.setMissingFields(platform, autoTunerConfigsProvider)
          .foreach(m => appendComment(m))
      }
    }
  }

  private def recommendGCProperty(): Unit = {
    val jvmGCFraction = appInfoProvider.getJvmGCFractions
    if (jvmGCFraction.nonEmpty) { // avoid zero division
      if ((jvmGCFraction.sum / jvmGCFraction.size) >
        autoTunerConfigsProvider.MAX_JVM_GCTIME_FRACTION) {
        // TODO - or other cores/memory ratio
        appendComment("Average JVM GC time is very high. " +
          "Other Garbage Collectors can be used for better performance.")
      }
    }
  }

  private def recommendAQEProperties(): Unit = {
    // Spark configuration (AQE is enabled by default)
    val aqeEnabled = getPropertyValue("spark.sql.adaptive.enabled")
      .getOrElse("false").toLowerCase
    if (aqeEnabled == "false") {
      // TODO: Should we recommend enabling AQE if not set?
      appendComment(autoTunerConfigsProvider.commentsForMissingProps("spark.sql.adaptive.enabled"))
    }
    appInfoProvider.getSparkVersion match {
      case Some(version) =>
        if (ToolUtils.isSpark320OrLater(version)) {
          // AQE configs changed in 3.2.0
          if (getPropertyValue("spark.sql.adaptive.coalescePartitions.minPartitionSize").isEmpty) {
            // the default is 1m, but 4m is slightly better for the GPU as we have a higher
            // per task overhead
            appendRecommendation("spark.sql.adaptive.coalescePartitions.minPartitionSize", "4m")
          }
        } else {
          if (getPropertyValue("spark.sql.adaptive.coalescePartitions.minPartitionNum").isEmpty) {
            // The ideal setting is for the parallelism of the cluster
            val numCoresPerExec = calcNumExecutorCores
            // TODO: Should this based on the recommended cluster instead of source cluster props?
            val numExecutorsPerWorker = clusterProps.gpu.getCount
            val numWorkers = clusterProps.system.getNumWorkers
            if (numExecutorsPerWorker != 0 && numWorkers != 0) {
              val total = numWorkers * numExecutorsPerWorker * numCoresPerExec
              appendRecommendation("spark.sql.adaptive.coalescePartitions.minPartitionNum",
                total.toString)
            }
          }
        }
      case None =>
    }

    val advisoryPartitionSizeProperty =
      getPropertyValue("spark.sql.adaptive.advisoryPartitionSizeInBytes")
    if (appInfoProvider.getMeanInput <
      autoTunerConfigsProvider.AQE_INPUT_SIZE_BYTES_THRESHOLD) {
      if (advisoryPartitionSizeProperty.isEmpty) {
        // The default is 64m, but 128m is slightly better for the GPU as the GPU has sub-linear
        // scaling until it is full and 128m makes the GPU more full, but too large can be
        // slightly problematic because this is the compressed shuffle size
        appendRecommendation("spark.sql.adaptive.advisoryPartitionSizeInBytes", "128m")
      }
    }
    var recInitialPartitionNum = 0
    if (appInfoProvider.getMeanInput > autoTunerConfigsProvider.AQE_INPUT_SIZE_BYTES_THRESHOLD &&
      appInfoProvider.getMeanShuffleRead >
        autoTunerConfigsProvider.AQE_SHUFFLE_READ_BYTES_THRESHOLD) {
      // AQE Recommendations for large input and large shuffle reads
      platform.recommendedGpuDevice.getAdvisoryPartitionSizeInBytes.foreach { size =>
        appendRecommendation("spark.sql.adaptive.advisoryPartitionSizeInBytes", size)
      }
      val initialPartitionNumProperty =
        getPropertyValue("spark.sql.adaptive.coalescePartitions.initialPartitionNum").map(_.toInt)
      if (initialPartitionNumProperty.getOrElse(0) <=
            autoTunerConfigsProvider.AQE_MIN_INITIAL_PARTITION_NUM) {
        recInitialPartitionNum = platform.recommendedGpuDevice.getInitialPartitionNum.getOrElse(0)
      }
      // We need to set this to false, else Spark ignores the target size specified by
      // spark.sql.adaptive.advisoryPartitionSizeInBytes.
      // Reference: https://spark.apache.org/docs/latest/sql-performance-tuning.html
      appendRecommendation("spark.sql.adaptive.coalescePartitions.parallelismFirst", "false")
    }

    val recShufflePartitions = recommendations.get("spark.sql.shuffle.partitions")
      .map(_.getTuneValue().toInt)

    // scalastyle:off line.size.limit
    // Determine whether to recommend initialPartitionNum based on shuffle partitions recommendation
    recShufflePartitions match {
      case Some(shufflePartitions) if shufflePartitions >= recInitialPartitionNum =>
        // Skip recommending 'initialPartitionNum' when:
        // - AutoTuner has already recommended 'spark.sql.shuffle.partitions' AND
        // - The recommended shuffle partitions value is sufficient (>= recInitialPartitionNum)
        // This is because AQE will use the recommended 'spark.sql.shuffle.partitions' by default.
        // Reference: https://spark.apache.org/docs/latest/sql-performance-tuning.html#coalescing-post-shuffle-partitions
      case _ =>
        // Set 'initialPartitionNum' when either:
        // - AutoTuner has not recommended 'spark.sql.shuffle.partitions' OR
        // - Recommended shuffle partitions is small (< recInitialPartitionNum)
        appendRecommendation("spark.sql.adaptive.coalescePartitions.initialPartitionNum",
          recInitialPartitionNum)
        appendRecommendation("spark.sql.shuffle.partitions", recInitialPartitionNum)
    }
    // scalastyle:on line.size.limit

    // TODO - can we set spark.sql.autoBroadcastJoinThreshold ???
    val autoBroadcastJoinKey = "spark.sql.adaptive.autoBroadcastJoinThreshold"
    val autoBroadcastJoinThresholdProperty =
      getPropertyValue(autoBroadcastJoinKey).map(StringUtils.convertToMB(_, Some(ByteUnit.BYTE)))
    if (autoBroadcastJoinThresholdProperty.isEmpty) {
      appendComment(autoBroadcastJoinKey, s"'$autoBroadcastJoinKey' was not set.")
    } else if (autoBroadcastJoinThresholdProperty.get >
        StringUtils.convertToMB(autoTunerConfigsProvider.AQE_AUTOBROADCAST_JOIN_THRESHOLD, None)) {
      appendComment(s"Setting '$autoBroadcastJoinKey' > " +
        s"${autoTunerConfigsProvider.AQE_AUTOBROADCAST_JOIN_THRESHOLD} could " +
        s"lead to performance\n" +
        "  regression. Should be set to a lower number.")
    }
  }

  /**
   * Checks the system properties and give feedback to the user.
   * For example file.encoding=UTF-8 is required for some ops like GpuRegEX.
   */
  private def recommendSystemProperties(): Unit = {
    appInfoProvider.getSystemProperty("file.encoding").collect {
      case encoding if !ToolUtils.isFileEncodingRecommended(encoding) =>
        appendComment(s"file.encoding should be [${ToolUtils.SUPPORTED_ENCODINGS.mkString}]" +
            " because GPU only supports the charset when using some expressions.")
    }
  }

  /**
   * Check the class path entries with the following rules:
   * 1- If ".*rapids-4-spark.*jar" is missing then add a comment that the latest jar should be
   *    included in the classpath unless it is part of the spark
   * 2- If there are more than 1 entry for ".*rapids-4-spark.*jar", then add a comment that
   *    there should be only 1 jar in the class path.
   * 3- If there are cudf jars, ignore that for now.
   * 4- If there is a new release recommend that to the user
   */
  private def recommendClassPathEntries(): Unit = {
    val missingRapidsJarsEntry = autoTunerConfigsProvider.classPathComments("rapids.jars.missing")
    val multipleRapidsJarsEntry = autoTunerConfigsProvider.classPathComments("rapids.jars.multiple")

    appInfoProvider.getRapidsJars match {
      case Seq() =>
        // No rapids jars
        appendComment(missingRapidsJarsEntry)
      case s: Seq[String] =>
        s.flatMap(e =>
          autoTunerConfigsProvider.pluginJarRegEx.findAllMatchIn(e).map(_.group(1))) match {
            case Seq() => appendComment(missingRapidsJarsEntry)
            case v: Seq[String] if v.length > 1 =>
              val comment = s"$multipleRapidsJarsEntry [${v.mkString(", ")}]"
              appendComment(comment)
            case Seq(jarVer) =>
              // compare jarVersion to the latest release
              val latestPluginVersion = WebCrawlerUtil.getLatestPluginRelease
              latestPluginVersion match {
                case Some(ver) =>
                  if (ToolUtils.compareVersions(jarVer, ver) < 0) {
                    val jarURL = WebCrawlerUtil.getPluginMvnDownloadLink(ver)
                    appendComment(
                      "A newer RAPIDS Accelerator for Apache Spark plugin is available:\n" +
                        s"  $jarURL\n" +
                        s"  Version used in application is $jarVer.")
                  }
                case None =>
                  logError("Could not pull the latest release of RAPIDS-plugin jar.")
                  val pluginRepoUrl = WebCrawlerUtil.getMVNArtifactURL("rapids.plugin")
                  appendComment(
                    "Failed to validate the latest release of Apache Spark plugin.\n" +
                    s"  Verify that the version used in application ($jarVer) is the latest on:\n" +
                    s"  $pluginRepoUrl")

            }
        }
    }
  }

  /**
   * Calculate max partition bytes using the max task input size and existing setting
   * for maxPartitionBytes. Note that this won't apply the same on iceberg.
   * The max bytes here does not distinguish between GPU and CPU reads so we could
   * improve that in the future.
   * Eg,
   * MIN_PARTITION_BYTES_RANGE = 128m, MAX_PARTITION_BYTES_RANGE = 256m
   * (1) Input:  maxPartitionBytes = 512m
   *             taskInputSize = 12m
   *     Output: newMaxPartitionBytes = 512m * (128m/12m) = 4g (hit max value)
   * (2) Input:  maxPartitionBytes = 2g
   *             taskInputSize = 512m,
   *     Output: newMaxPartitionBytes = 2g / (512m/128m) = 512m
   */
  protected def calculateMaxPartitionBytesInMB(maxPartitionBytes: String): Option[Long] = {
    // AutoTuner only supports a single app right now, so we get whatever value is here
    val inputBytesMax = appInfoProvider.getMaxInput / 1024 / 1024
    val maxPartitionBytesNum = StringUtils.convertToMB(maxPartitionBytes, Some(ByteUnit.BYTE))
    if (inputBytesMax == 0.0) {
      Some(maxPartitionBytesNum)
    } else {
      if (inputBytesMax > 0 &&
        inputBytesMax < autoTunerConfigsProvider.MIN_PARTITION_BYTES_RANGE_MB) {
        // Increase partition size
        val calculatedMaxPartitionBytes = Math.min(
          maxPartitionBytesNum *
            (autoTunerConfigsProvider.MIN_PARTITION_BYTES_RANGE_MB / inputBytesMax),
          autoTunerConfigsProvider.MAX_PARTITION_BYTES_BOUND_MB)
        Some(calculatedMaxPartitionBytes.toLong)
      } else if (inputBytesMax > autoTunerConfigsProvider.MAX_PARTITION_BYTES_RANGE_MB) {
        // Decrease partition size
        val calculatedMaxPartitionBytes = Math.min(
          maxPartitionBytesNum /
            (inputBytesMax / autoTunerConfigsProvider.MAX_PARTITION_BYTES_RANGE_MB),
          autoTunerConfigsProvider.MAX_PARTITION_BYTES_BOUND_MB)
        Some(calculatedMaxPartitionBytes.toLong)
      } else {
        // Do not recommend maxPartitionBytes
        None
      }
    }
  }

  /**
   * Recommendation for 'spark.rapids.file.cache' based on read characteristics of job.
   */
  private def recommendFileCache(): Unit = {
    if (appInfoProvider.getDistinctLocationPct <
      autoTunerConfigsProvider.DEF_DISTINCT_READ_THRESHOLD &&
      appInfoProvider.getRedundantReadSize >
        autoTunerConfigsProvider.DEF_READ_SIZE_THRESHOLD) {
      appendRecommendation("spark.rapids.filecache.enabled", "true")
      appendComment("Enable file cache only if Spark local disks bandwidth is > 1 GB/s" +
        " and you have sufficient disk space available to fit both cache and normal Spark" +
        " temporary data.")
    }
  }

  /**
   * Recommendation for 'spark.sql.files.maxPartitionBytes' based on input size for each task.
   * Note that the logic can be disabled by adding the property to "limitedLogicRecommendations"
   * which is one of the arguments of [[getRecommendedProperties]].
   */
  private def recommendMaxPartitionBytes(): Unit = {
    val maxPartitionProp =
      getPropertyValue("spark.sql.files.maxPartitionBytes")
        .getOrElse(autoTunerConfigsProvider.MAX_PARTITION_BYTES)
    val recommended =
      if (isCalculationEnabled("spark.sql.files.maxPartitionBytes")) {
        calculateMaxPartitionBytesInMB(maxPartitionProp).map(_.toString).orNull
      } else {
        s"${StringUtils.convertToMB(maxPartitionProp, Some(ByteUnit.BYTE))}"
      }
    appendRecommendationForMemoryMB("spark.sql.files.maxPartitionBytes", recommended)
  }

  /**
   * Internal method to recommend 'spark.sql.shuffle.partitions' based on spills and skew.
   * This method can be overridden by Profiling/Qualification AutoTuners to provide custom logic.
   */
  protected def recommendShufflePartitionsInternal(inputShufflePartitions: Int): Int = {
    var shufflePartitions = inputShufflePartitions
    val lookup = "spark.sql.shuffle.partitions"
    val shuffleStagesWithPosSpilling = appInfoProvider.getShuffleStagesWithPosSpilling
    if (shuffleStagesWithPosSpilling.nonEmpty) {
      val shuffleSkewStages = appInfoProvider.getShuffleSkewStages
      if (shuffleSkewStages.exists(id => shuffleStagesWithPosSpilling.contains(id))) {
        appendOptionalComment(lookup,
          "Shuffle skew exists (when task's Shuffle Read Size > 3 * Avg Stage-level size) in\n" +
            s"  stages with spilling. Increasing shuffle partitions is not recommended in this\n" +
            s"  case since keys will still hash to the same task.")
      } else {
        shufflePartitions *= autoTunerConfigsProvider.DEF_SHUFFLE_PARTITION_MULTIPLIER
        // Could be memory instead of partitions
        appendOptionalComment(lookup,
          s"'$lookup' should be increased since spilling occurred in shuffle stages.")
      }
    }
    shufflePartitions
  }

  /**
   * Recommendations for 'spark.sql.shuffle.partitions' based on spills and skew in shuffle stages.
   * Note that the logic can be disabled by adding the property to "limitedLogicRecommendations"
   * which is one of the arguments of [[getRecommendedProperties]].
   */
  private def recommendShufflePartitions(): Unit = {
    val lookup = "spark.sql.shuffle.partitions"
    var shufflePartitions =
      getPropertyValue(lookup).getOrElse(autoTunerConfigsProvider.DEF_SHUFFLE_PARTITIONS).toInt

    // TODO: Need to look at other metrics for GPU spills (DEBUG mode), and batch sizes metric
    if (isCalculationEnabled(lookup)) {
      shufflePartitions = recommendShufflePartitionsInternal(shufflePartitions)
    }
    // If the user has enabled AQE auto shuffle, the auto-tuner should recommend to disable this
    // feature before recommending shuffle partitions.
    val aqeAutoShuffle = getPropertyValue("spark.databricks.adaptive.autoOptimizeShuffle.enabled")
    if (!aqeAutoShuffle.isEmpty) {
      appendRecommendation("spark.databricks.adaptive.autoOptimizeShuffle.enabled", "false")
    }
    appendRecommendation("spark.sql.shuffle.partitions", s"$shufflePartitions")
  }

  /**
   * Analyzes unsupported driver logs and generates recommendations for configuration properties.
   */
  private def recommendFromDriverLogs(): Unit = {
    // Iterate through unsupported operators' reasons and check for matching properties
    driverInfoProvider.getUnsupportedOperators.map(_.reason).foreach { operatorReason =>
      autoTunerConfigsProvider.recommendationsFromDriverLogs.collect {
        case (config, recommendedValue) if operatorReason.contains(config) =>
          appendRecommendation(config, recommendedValue)
          appendComment(autoTunerConfigsProvider.commentForExperimentalConfig(config))
      }
    }
  }

  private def recommendPluginProps(): Unit = {
    val isPluginLoaded = getPropertyValue("spark.plugins") match {
      case Some(f) => f.contains("com.nvidia.spark.SQLPlugin")
      case None => false
    }
    // Set the plugin to True without need to check if it is already set.
    appendRecommendation("spark.rapids.sql.enabled", "true")
    if (!isPluginLoaded) {
      appendComment("RAPIDS Accelerator for Apache Spark jar is missing in \"spark.plugins\". " +
        "Please refer to " +
        "https://docs.nvidia.com/spark-rapids/user-guide/latest/getting-started/overview.html")
    }
  }

  def appendOptionalComment(lookup: String, comment: String): Unit = {
    if (!skippedRecommendations.contains(lookup)) {
      appendComment(comment)
    }
  }

  def appendComment(comment: String): Unit = {
    comments += comment
  }

  /**
   * Adds a comment for a configuration key when AutoTuner cannot provide a recommended value,
   * but the configuration is necessary.
   */
  private def appendComment(
      key: String,
      comment: String,
      fillInValue: Option[String] = None): Unit = {
    if (!skippedRecommendations.contains(key)) {
      val recomRecord = recommendations.getOrElseUpdate(key,
        TuningEntry.build(key, getPropertyValueFromSource(key), None))
      recomRecord.markAsUnresolved(fillInValue)
      comments += comment
    }
  }

  def convertClusterPropsToString(): String = {
    clusterProps.toString
  }

  /**
   * Add default comments for missing properties except the ones
   * which should be skipped.
   */
  private def addDefaultComments(): Unit = {
    appendComment("Could not infer the cluster configuration, recommendations " +
      "are generated using default values!")
    autoTunerConfigsProvider.commentsForMissingProps.foreach {
      case (key, value) =>
        if (!skippedRecommendations.contains(key)) {
          appendComment(value)
        }
    }
  }

  private def addMissingMemoryComments(): Unit = {
    autoTunerConfigsProvider.commentsForMissingMemoryProps.foreach {
      case (key, value) =>
        if (!skippedRecommendations.contains(key)) {
          appendComment(value)
        }
    }
  }

  private def toCommentProfileResult: Seq[RecommendedCommentResult] = {
    comments.map(RecommendedCommentResult).sortBy(_.comment)
  }

  private def toRecommendationsProfileResult: Seq[TuningEntryTrait] = {
    val recommendationEntries = if (filterByUpdatedPropertiesEnabled) {
      recommendations.values.filter(_.isTuned())
    } else {
      recommendations.values.filter(_.isEnabled())
    }
    recommendationEntries.toSeq.sortBy(_.name)
  }

  protected def finalizeTuning(): Unit = {
    recommendations.values.foreach(_.commit())
  }

  /**
   * The Autotuner loads the spark properties from either the ClusterProperties or the eventlog.
   * 1- runs the calculation for each criterion and saves it as a [[TuningEntryTrait]].
   * 2- The final list of recommendations include any [[TuningEntryTrait]] that has a
   *    recommendation that is different from the original property.
   * 3- Null values are excluded.
   * 4- A comment is added for each missing property in the spark property.
   *
   * @param skipList a list of properties to be skipped. If none, all recommendations are
   *                 returned. Note that the recommendations will be computed anyway internally
   *                 in case there are dependencies between the recommendations.
   *                 Default is empty.
   * @param limitedLogicList a list of properties that will do simple recommendations based on
   *                         static default values.
   * @param showOnlyUpdatedProps When enabled, the profiler recommendations should only include
   *                             updated settings.
   * @return pair of recommendations and comments. Both sequence can be empty.
   */
  def getRecommendedProperties(
      skipList: Option[Seq[String]] = Some(Seq()),
      limitedLogicList: Option[Seq[String]] = Some(Seq()),
      showOnlyUpdatedProps: Boolean = true):
      (Seq[TuningEntryTrait], Seq[RecommendedCommentResult]) = {
    if (appInfoProvider.isAppInfoAvailable) {
      limitedLogicList.foreach(limitedSeq => limitedLogicRecommendations ++= limitedSeq)
      skipList.foreach(skipSeq => skippedRecommendations ++= skipSeq)
      skippedRecommendations ++= platform.recommendationsToExclude
      initRecommendations()
      // update GPU device of platform based on cluster properties if it is not already set.
      // if the GPU device cannot be inferred from cluster properties, do not make any updates.
      if (platform.gpuDevice.isEmpty && !clusterProps.isEmpty && !clusterProps.gpu.isEmpty) {
        GpuDevice.createInstance(clusterProps.gpu.getName)
          .foreach(platform.setGpuDevice)
      }
      // configured GPU recommended instance type NEEDS to happen before any of the other
      // recommendations as they are based on
      // the instance type
      configureGPURecommendedInstanceType()
      configureClusterPropDefaults
      // Makes recommendations based on information extracted from the AppInfoProvider
      filterByUpdatedPropertiesEnabled = showOnlyUpdatedProps
      recommendPluginProps
      calculateJobLevelRecommendations()
      calculateClusterLevelRecommendations()

      // Add all platform specific recommendations
      platform.platformSpecificRecommendations.collect {
        case (property, value) if getPropertyValueFromSource(property).isEmpty =>
          appendRecommendation(property, value)
      }
    }
    recommendFromDriverLogs()
    finalizeTuning()
    (toRecommendationsProfileResult, toCommentProfileResult)
  }

  // Process the properties keys. This is needed in case there are some properties that should not
  // be listed in the final combined results. For example:
  // - The UUID of the app is not part of the submitted spark configurations
  // - make sure that we exclude the skipped list
  private def processPropKeys(
      srcMap: collection.Map[String, String]): collection.Map[String, String] = {
    (srcMap -- skippedRecommendations) -- autoTunerConfigsProvider.filteredPropKeys
  }

  // Combines the original Spark properties with the recommended ones.
  def combineSparkProperties(
      recommendedSet: Seq[TuningEntryTrait]): Seq[RecommendedPropertyResult] = {
    // get the original properties after filtering and removing unnecessary keys
    val originalPropsFiltered = processPropKeys(getAllSourceProperties)
    // Combine the original properties with the recommended properties.
    // The recommendations should always override the original ones
    val combinedProps = (originalPropsFiltered
      ++ recommendedSet.map(r => r.name -> r.getTuneValue()).toMap).toSeq.sortBy(_._1)
    combinedProps.collect {
      case (pK, pV) => RecommendedPropertyResult(pK, pV)
    }
  }
}

object AutoTuner {
  /**
   * Helper function to get a combined property function that can be used
   * to retrieve the value of a property in the following priority order:
   * 1. From the recommendations map
   *    - This will include the user-enforced Spark properties
   *    - This implies the properties to be present in the target application
   * 2. From the source Spark properties
   */
  def getCombinedPropertyFn(
    recommendations: mutable.LinkedHashMap[String, TuningEntryTrait],
    sourceSparkProperties: Map[String, String]): String => Option[String] = {
    (key: String) => {
      recommendations.get(key).map(_.getTuneValue())
        .orElse(sourceSparkProperties.get(key))
    }
  }
}

/**
 * Implementation of the `AutoTuner` specific for the Profiling Tool.
 * This class implements the logic to recommend AutoTuner configurations
 * specifically for GPU event logs.
 */
class ProfilingAutoTuner(
    clusterProps: ClusterProperties,
    appInfoProvider: BaseProfilingAppSummaryInfoProvider,
    platform: Platform,
    driverInfoProvider: DriverLogInfoProvider)
  extends AutoTuner(clusterProps, appInfoProvider, platform, driverInfoProvider,
    ProfilingAutoTunerConfigsProvider) {

  /**
   * Overrides the calculation for 'spark.sql.files.maxPartitionBytes'.
   * Logic:
   * - First, calculate the recommendation based on input sizes (parent implementation).
   * - If GPU OOM errors occurred in scan stages,
   *     - If calculated value is defined, choose the minimum between the calculated value and
   *       half of the current value.
   *     - Else, halve the current value.
   * - Else, use the value from the parent implementation.
   */
  override def calculateMaxPartitionBytesInMB(maxPartitionBytes: String): Option[Long] = {
    // First, calculate the recommendation based on input sizes
    val calculatedValueFromInputSize = super.calculateMaxPartitionBytesInMB(maxPartitionBytes)
    getPropertyValue("spark.sql.files.maxPartitionBytes") match {
      case Some(currentValue) if appInfoProvider.hasScanStagesWithGpuOom =>
        // GPU OOM detected. We may want to reduce max partition size.
        val halvedValue = StringUtils.convertToMB(currentValue, Some(ByteUnit.BYTE)) / 2
        // Choose the minimum between the calculated value and half of the current value.
        calculatedValueFromInputSize match {
          case Some(calculatedValue) => Some(math.min(calculatedValue, halvedValue))
          case None => Some(halvedValue)
        }
      case _ =>
        // Else, use the value from the parent implementation
        calculatedValueFromInputSize
    }
  }

  /**
   * Overrides the calculation for 'spark.sql.shuffle.partitions'.
   * This method checks for task OOM errors in shuffle stages and recommends to increase
   * shuffle partitions if task OOM errors occurred.
   */
  override def recommendShufflePartitionsInternal(inputShufflePartitions: Int): Int = {
    val calculatedValue = super.recommendShufflePartitionsInternal(inputShufflePartitions)
    val lookup = "spark.sql.shuffle.partitions"
    val currentValue = getPropertyValue(lookup).getOrElse(
      autoTunerConfigsProvider.DEF_SHUFFLE_PARTITIONS).toInt
    if (appInfoProvider.hasShuffleStagesWithOom) {
      // Shuffle Stages with Task OOM detected. We may want to increase shuffle partitions.
      val recShufflePartitions = currentValue *
        autoTunerConfigsProvider.DEF_SHUFFLE_PARTITION_MULTIPLIER
      appendOptionalComment(lookup,
        s"'$lookup' should be increased since task OOM occurred in shuffle stages.")
      math.max(calculatedValue, recShufflePartitions)
    } else {
      // Else, return the calculated value from the parent implementation
      calculatedValue
    }
  }
}

/**
 * Trait defining configuration defaults and parameters for the AutoTuner.
 */
trait AutoTunerConfigsProvider extends Logging {
  // Maximum number of concurrent tasks to run on the GPU
  val MAX_CONC_GPU_TASKS = 4L
  // Default cores per executor to be recommended for Spark RAPIDS
  val DEF_CORES_PER_EXECUTOR = 16
  // Default amount of a GPU memory allocated for each task.
  // This is set to a low value for Spark RAPIDS as task parallelism will be
  // honoured by `spark.executor.cores`.
  val DEF_TASK_GPU_RESOURCE_AMT = 0.001
  // Fraction of the executor JVM heap size that should be additionally reserved
  // for JVM off-heap overhead (thread stacks, native libraries, etc.)
  val DEF_HEAP_OVERHEAD_FRACTION = 0.1
  val MAX_JVM_GCTIME_FRACTION = 0.3
  // Minimum amount of JVM heap memory to request per CPU core in megabytes
  val MIN_HEAP_PER_CORE_MB: Long = 750L
  // Ideal amount of JVM heap memory to request per CPU core in megabytes
  val DEF_HEAP_PER_CORE_MB: Long = 2 * 1024L
  // Maximum amount of pinned memory to use per executor in MB
  val MAX_PINNED_MEMORY_MB: Long = 4 * 1024L
  // Default pinned memory to use per executor in MB
  val DEF_PINNED_MEMORY_MB: Long = 2 * 1024L
  val DEF_SPILL_MEMORY_MB: Long = DEF_PINNED_MEMORY_MB
  // the pageable pool doesn't exist anymore but by default we don't have any hard limits so
  // leave this for now to account for off heap memory usage.
  // TODO: Should we remove this as its unused by the plugin?
  // val DEF_PAGEABLE_POOL_MB: Long = 2 * 1024L
  // value in MB
  val MIN_PARTITION_BYTES_RANGE_MB = 128L
  // value in MB
  val MAX_PARTITION_BYTES_RANGE_MB = 256L
  // value in MB
  val MAX_PARTITION_BYTES_BOUND_MB: Int = 4 * 1024
  val MAX_PARTITION_BYTES: String = "512m"
  val DEF_SHUFFLE_PARTITIONS = "200"
  val DEF_SHUFFLE_PARTITION_MULTIPLIER: Int = 2
  // GPU count defaults to 1 if it is missing.
  val DEF_WORKER_GPU_COUNT = 1
  // Default Number of Workers 1
  val DEF_NUM_WORKERS = 1
  // Default distinct read location thresholds is 50%
  val DEF_DISTINCT_READ_THRESHOLD = 50.0
  // Default file cache size minimum is 100 GB
  val DEF_READ_SIZE_THRESHOLD = 100 * 1024L * 1024L * 1024L
  // TODO: Recommendation for maxBytesInFlight should be removed
  val DEF_MAX_BYTES_IN_FLIGHT_MB: Long = 4 * 1024L
  val SUPPORTED_SIZE_UNITS: Seq[String] = Seq("b", "k", "m", "g", "t", "p")
  private val DOC_URL: String = "https://nvidia.github.io/spark-rapids/docs/" +
    "additional-functionality/advanced_configs.html#advanced-configuration"
  // Value of batchSizeBytes that performs best overall
  val BATCH_SIZE_BYTES = 2147483647
  val AQE_INPUT_SIZE_BYTES_THRESHOLD = 35000
  val AQE_SHUFFLE_READ_BYTES_THRESHOLD = 50000
  val AQE_MIN_INITIAL_PARTITION_NUM = 200
  val AQE_AUTOBROADCAST_JOIN_THRESHOLD = "100m"
  val GPU_KRYO_SERIALIZER_CLASS = "com.nvidia.spark.rapids.GpuKryoRegistrator"
  // Desired Kryo serializer buffer size to prevent OOMs. Spark sets the default to 64MB.
  val KRYO_SERIALIZER_BUFFER_MAX_MB = 512L
  // Set of spark properties to be filtered out from the combined Spark properties.
  val filteredPropKeys: Set[String] = Set(
    "spark.app.id"
  )

  /**
   * Default strategy for cluster shape recommendation.
   * See [[com.nvidia.spark.rapids.tool.ClusterSizingStrategy]] for different strategies.
   */
  lazy val recommendedClusterSizingStrategy: ClusterSizingStrategy = ConstantGpuCountStrategy

  val commentsForMissingMemoryProps: Map[String, String] = Map(
    "spark.executor.memory" ->
      "'spark.executor.memory' should be set to at least 2GB/core.",
    "spark.rapids.memory.pinnedPool.size" ->
      s"'spark.rapids.memory.pinnedPool.size' should be set to ${DEF_PINNED_MEMORY_MB}m.")

  // scalastyle:off line.size.limit
  val commentsForMissingProps: Map[String, String] = Map(
    "spark.executor.cores" ->
      // TODO: This could be extended later to be platform specific.
      s"'spark.executor.cores' should be set to $DEF_CORES_PER_EXECUTOR.",
    "spark.executor.instances" ->
      "'spark.executor.instances' should be set to (cpuCoresPerNode * numWorkers) / 'spark.executor.cores'.",
    "spark.task.resource.gpu.amount" ->
      s"'spark.task.resource.gpu.amount' should be set to $DEF_TASK_GPU_RESOURCE_AMT.",
    "spark.rapids.sql.concurrentGpuTasks" ->
      s"'spark.rapids.sql.concurrentGpuTasks' should be set to Min(4, (gpuMemory / 7.5G)).",
    "spark.rapids.sql.enabled" ->
      "'spark.rapids.sql.enabled' should be true to enable SQL operations on the GPU.",
    "spark.sql.adaptive.enabled" ->
      "'spark.sql.adaptive.enabled' should be enabled for better performance."
  ) ++ commentsForMissingMemoryProps
  // scalastyle:off line.size.limit

  lazy val recommendationsTarget: Iterable[String] = TuningEntryDefinition.TUNING_TABLE.keys

  val classPathComments: Map[String, String] = Map(
    "rapids.jars.missing" ->
      ("RAPIDS Accelerator for Apache Spark plugin jar is missing\n" +
        "  from the classpath entries.\n" +
        "  If the Spark RAPIDS jar is being bundled with your\n" +
        "  Spark distribution, this step is not needed."),
    "rapids.jars.multiple" ->
      ("Multiple RAPIDS Accelerator for Apache Spark plugin jar\n" +
        "  exist on the classpath.\n" +
        "  Make sure to keep only a single jar."),
    "rapids.shuffle.jars" ->
      ("The RAPIDS Shuffle Manager requires spark.driver.extraClassPath\n" +
        "  and spark.executor.extraClassPath settings to include the\n" +
        "  path to the Spark RAPIDS plugin jar.\n" +
        "  If the Spark RAPIDS jar is being bundled with your Spark\n" +
        "  distribution, this step is not needed.")
  )

  // Recommended values for specific unsupported configurations
  val recommendationsFromDriverLogs: Map[String, String] = Map(
    "spark.rapids.sql.incompatibleDateFormats.enabled" -> "true"
  )

  def commentForExperimentalConfig(config: String): String = {
    s"Using $config does not guarantee to produce the same results as CPU. " +
      s"Please refer to $DOC_URL."
  }

  // the plugin jar is in the form of rapids-4-spark_scala_binary-(version)-*.jar
  val pluginJarRegEx: Regex = "rapids-4-spark_\\d\\.\\d+-(\\d{2}\\.\\d{2}\\.\\d+).*\\.jar".r

  private val shuffleManagerDocUrl = "https://docs.nvidia.com/spark-rapids/user-guide/latest/" +
    "additional-functionality/rapids-shuffle.html#rapids-shuffle-manager"

  /**
   * Abstract method to create an instance of the AutoTuner.
   */
  def createAutoTunerInstance(
    clusterProps: ClusterProperties,
    appInfoProvider: AppSummaryInfoBaseProvider,
    platform: Platform,
    driverInfoProvider: DriverLogInfoProvider): AutoTuner

  def handleException(
      ex: Throwable,
      appInfo: AppSummaryInfoBaseProvider,
      platform: Platform,
      driverInfoProvider: DriverLogInfoProvider): AutoTuner = {
    logError("Exception: " + ex.getStackTrace.mkString("Array(", ", ", ")"))
    val tuning = createAutoTunerInstance(new ClusterProperties(), appInfo,
      platform, driverInfoProvider)
    val msg = ex match {
      case cEx: ConstructorException => cEx.getContext
      case _ => if (ex.getCause != null) ex.getCause.toString else ex.toString
    }
    tuning.appendComment(msg)
    tuning
  }

  /**
   * Similar to [[buildAutoTuner]] but it allows constructing the AutoTuner without an
   * existing file. This can be used in testing.
   *
   * @param clusterProps the cluster properties as string.
   * @param singleAppProvider the wrapper implementation that accesses the properties of the profile
   *                          results.
   * @param platform represents the environment created as a target for recommendations.
   * @param driverInfoProvider wrapper implementation that accesses the information from driver log.
   * @return a new AutoTuner object.
   */
  def buildAutoTunerFromProps(
      clusterProps: String,
      singleAppProvider: AppSummaryInfoBaseProvider,
      platform: Platform = PlatformFactory.createInstance(clusterProperties = None),
      driverInfoProvider: DriverLogInfoProvider = BaseDriverLogInfoProvider.noneDriverLog
  ): AutoTuner = {
    try {
      val clusterPropsOpt = PropertiesLoader[ClusterProperties].loadFromContent(clusterProps)
      createAutoTunerInstance(clusterPropsOpt.getOrElse(new ClusterProperties()),
        singleAppProvider, platform, driverInfoProvider)
    } catch {
      case NonFatal(e) =>
        handleException(e, singleAppProvider, platform, driverInfoProvider)
    }
  }

  def buildAutoTuner(
      singleAppProvider: AppSummaryInfoBaseProvider,
      platform: Platform,
      driverInfoProvider: DriverLogInfoProvider = BaseDriverLogInfoProvider.noneDriverLog
  ): AutoTuner = {
    try {
      val autoT = createAutoTunerInstance(
        platform.clusterProperties.getOrElse(new ClusterProperties()),
        singleAppProvider, platform, driverInfoProvider)
      autoT
    } catch {
      case NonFatal(e) =>
        handleException(e, singleAppProvider, platform, driverInfoProvider)
    }
  }

  def buildShuffleManagerClassName(smVersion: String): String = {
    s"com.nvidia.spark.rapids.spark$smVersion.RapidsShuffleManager"
  }

  def shuffleManagerCommentForUnsupportedVersion(
      sparkVersion: String, platform: Platform): String = {
    val (latestSparkVersion, latestSmVersion) = platform.latestSupportedShuffleManagerInfo
    // scalastyle:off line.size.limit
    s"""
       |Cannot recommend RAPIDS Shuffle Manager for unsupported ${platform.sparkVersionLabel}: '$sparkVersion'.
       |To enable RAPIDS Shuffle Manager, use a supported ${platform.sparkVersionLabel} (e.g., '$latestSparkVersion')
       |and set: '--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$latestSmVersion.RapidsShuffleManager'.
       |See supported versions: $shuffleManagerDocUrl.
       |""".stripMargin.trim.replaceAll("\n", "\n  ")
    // scalastyle:on line.size.limit
  }

  def shuffleManagerCommentForMissingVersion: String = {
    "Could not recommend RapidsShuffleManager as Spark version cannot be determined."
  }

  def latestPluginJarComment(latestJarMvnUrl: String, currentJarVer: String): String = {
    s"""
       |A newer RAPIDS Accelerator for Apache Spark plugin is available:
       |$latestJarMvnUrl
       |Version used in application is $currentJarVer.
       |""".stripMargin.trim.replaceAll("\n", "\n  ")
  }

  def notEnoughMemComment(minSizeInMB: Long): String = {
    s"""
       |This node/worker configuration is not ideal for using the RAPIDS Accelerator
       |for Apache Spark because it doesn't have enough memory for the executors.
       |We recommend either using nodes with more memory or reducing 'spark.memory.offHeap.size',
       |as off-heap memory is unused by the RAPIDS Accelerator, unless explicitly required by
       |the application. Need at least $minSizeInMB MB memory per executor.
       |""".stripMargin.trim.replaceAll("\n", "\n  ")
  }

  def notEnoughMemCommentForKey(key: String): String = {
    s"Not enough memory to set '$key'. See comments for more details."
  }

  /**
   * Append a comment to the list indicating that the property was enforced by the user.
   * @param key the property set by the autotuner.
   */
  def getEnforcedPropertyComment(key: String): String = {
    s"'$key' was user-enforced in the target cluster properties."
  }
}

/**
 * Provides configuration settings for the Profiling Tool's AutoTuner. This object is as a concrete
 * implementation of the `AutoTunerConfigsProvider` interface.
 */
object ProfilingAutoTunerConfigsProvider extends AutoTunerConfigsProvider {
  def createAutoTunerInstance(
      clusterProps: ClusterProperties,
      appInfoProvider: AppSummaryInfoBaseProvider,
      platform: Platform,
      driverInfoProvider: DriverLogInfoProvider): AutoTuner = {
    appInfoProvider match {
      case profilingAppProvider: BaseProfilingAppSummaryInfoProvider =>
        new ProfilingAutoTuner(clusterProps, profilingAppProvider, platform, driverInfoProvider)
      case _ =>
        throw new IllegalArgumentException("'appInfoProvider' must be an instance of " +
          s"${classOf[BaseProfilingAppSummaryInfoProvider]}")
    }
  }
}
