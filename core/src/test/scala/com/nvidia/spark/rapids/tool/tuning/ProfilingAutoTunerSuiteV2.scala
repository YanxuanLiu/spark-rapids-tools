/*
 * Copyright (c) 2025, NVIDIA CORPORATION.
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

import scala.collection.mutable

import com.nvidia.spark.rapids.tool.{GpuTypes, NodeInstanceMapKey, PlatformFactory, PlatformInstanceTypes, PlatformNames, ToolTestUtils}
import com.nvidia.spark.rapids.tool.profiling.Profiler

import org.apache.spark.sql.{SparkSession, TrampolineUtil}
import org.apache.spark.sql.rapids.tool.annotation.Since
import org.apache.spark.sql.rapids.tool.util.PropertiesLoader

/**
 * Test suite for the Profiling AutoTuner that uses the new target cluster properties format.
 *
 * This test suite introduces a cleaner way to specify target cluster configurations by explicitly
 * separating:
 * - Target cluster shape (cores, memory, GPU count/type)
 * - Target Spark properties (enforced configurations)
 *
 * This is in contrast to the legacy format in [[ProfilingAutoTunerSuite]] which overloaded the
 * same format for both source and target cluster properties.
 */
@Since("25.04.2")
class ProfilingAutoTunerSuiteV2 extends ProfilingAutoTunerSuiteBase {

  lazy val sparkSession: SparkSession = {
    SparkSession
      .builder()
      .master("local[*]")
      .appName("Rapids Spark Profiling Tool Unit Tests")
      .getOrCreate()
  }

  // Test that the properties from the custom target cluster props will be enforced.
  test("AutoTuner enforces properties from custom target cluster props") {
    // 1. Mock source cluster info for dataproc
    val instanceMapKey = NodeInstanceMapKey("g2-standard-16")
    val gpuInstance = PlatformInstanceTypes.DATAPROC_BY_INSTANCE_NAME(instanceMapKey)
    val sourceWorkerInfo = buildGpuWorkerInfoFromInstanceType(gpuInstance, Some(4))
    val sourceClusterInfoOpt =
      PropertiesLoader[ClusterProperties].loadFromContent(sourceWorkerInfo)
    // 2. Mock the properties loaded from eventLog
    val logEventsProps: mutable.Map[String, String] =
      mutable.LinkedHashMap[String, String](
        "spark.executor.cores" -> "8",
        "spark.executor.instances" -> "2",
        "spark.rapids.memory.pinnedPool.size" -> "5g",
        "spark.rapids.sql.enabled" -> "true",
        "spark.plugins" -> "com.nvidia.spark.SQLPlugin",
        "spark.executor.resource.gpu.amount" -> "1",
        // Below properties should be overridden by the enforced properties
        "spark.sql.shuffle.partitions" -> "200",
        "spark.sql.files.maxPartitionBytes" -> "1g",
        "spark.task.resource.gpu.amount" -> "0.001",
        "spark.rapids.sql.concurrentGpuTasks" -> "4"
      )
    // 3. Define enforced properties for the target cluster
    val enforcedSparkProperties = Map(
      "spark.sql.shuffle.partitions" -> "400",
      "spark.sql.files.maxPartitionBytes" -> "101m",
      "spark.task.resource.gpu.amount" -> "0.25",
      "spark.rapids.sql.concurrentGpuTasks" -> "2"
    )
    val targetClusterInfo = ToolTestUtils.buildTargetClusterInfo(
      enforcedSparkProperties = enforcedSparkProperties
    )
    val infoProvider = getMockInfoProvider(8126464.0, Seq(0), Seq(0.004), logEventsProps,
      Some(testSparkVersion))
    val platform = PlatformFactory.createInstance(PlatformNames.DATAPROC,
      sourceClusterInfoOpt, Some(targetClusterInfo))
    val autoTuner = buildAutoTunerForTests(sourceWorkerInfo, infoProvider, platform)
    val (properties, comments) = autoTuner.getRecommendedProperties()
    val autoTunerOutput = Profiler.getAutoTunerResultsAsString(properties, comments)
    // scalastyle:off line.size.limit
    val expectedResults =
      s"""|
          |Spark Properties:
          |--conf spark.dataproc.enhanced.execution.enabled=false
          |--conf spark.dataproc.enhanced.optimizer.enabled=false
          |--conf spark.executor.cores=16
          |--conf spark.executor.memory=32g
          |--conf spark.executor.memoryOverhead=15564m
          |--conf spark.locality.wait=0
          |--conf spark.rapids.memory.pinnedPool.size=4g
          |--conf spark.rapids.shuffle.multiThreaded.maxBytesInFlight=4g
          |--conf spark.rapids.shuffle.multiThreaded.reader.threads=28
          |--conf spark.rapids.shuffle.multiThreaded.writer.threads=28
          |--conf spark.rapids.sql.batchSizeBytes=2147483647b
          |--conf spark.rapids.sql.concurrentGpuTasks=2
          |--conf spark.rapids.sql.format.parquet.multithreaded.combine.waitTime=1000
          |--conf spark.rapids.sql.multiThreadedRead.numThreads=80
          |--conf spark.rapids.sql.reader.multithreaded.combine.sizeBytes=10m
          |--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$testSmVersion.RapidsShuffleManager
          |--conf spark.sql.adaptive.advisoryPartitionSizeInBytes=128m
          |--conf spark.sql.adaptive.autoBroadcastJoinThreshold=[FILL_IN_VALUE]
          |--conf spark.sql.adaptive.coalescePartitions.minPartitionSize=4m
          |--conf spark.sql.files.maxPartitionBytes=101m
          |--conf spark.sql.shuffle.partitions=400
          |--conf spark.task.resource.gpu.amount=0.25
          |
          |Comments:
          |- 'spark.dataproc.enhanced.execution.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.execution.enabled' was not set.
          |- 'spark.dataproc.enhanced.optimizer.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.optimizer.enabled' was not set.
          |- 'spark.executor.memory' was not set.
          |- 'spark.executor.memoryOverhead' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.maxBytesInFlight' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.reader.threads' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.writer.threads' was not set.
          |- 'spark.rapids.sql.batchSizeBytes' was not set.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.rapids.sql.concurrentGpuTasks")}
          |- 'spark.rapids.sql.format.parquet.multithreaded.combine.waitTime' was not set.
          |- 'spark.rapids.sql.multiThreadedRead.numThreads' was not set.
          |- 'spark.rapids.sql.reader.multithreaded.combine.sizeBytes' was not set.
          |- 'spark.shuffle.manager' was not set.
          |- 'spark.sql.adaptive.advisoryPartitionSizeInBytes' was not set.
          |- 'spark.sql.adaptive.autoBroadcastJoinThreshold' was not set.
          |- 'spark.sql.adaptive.enabled' should be enabled for better performance.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.sql.files.maxPartitionBytes")}
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.sql.shuffle.partitions")}
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.task.resource.gpu.amount")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.jars.missing")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.shuffle.jars")}
          |""".stripMargin
    // scalastyle:on line.size.limit
    compareOutput(expectedResults, autoTunerOutput)
  }

  // Test that the executor memory and memory overhead properties from the custom target cluster
  // props lead to AutoTuner warning about insufficient memory.
  test("AutoTuner warns about insufficient memory with executor heap and" +
    " memory overhead override") {
    // 1. Mock source cluster info for dataproc
    val instanceMapKey = NodeInstanceMapKey("g2-standard-16")
    val gpuInstance = PlatformInstanceTypes.DATAPROC_BY_INSTANCE_NAME(instanceMapKey)
    val sourceWorkerInfo = buildGpuWorkerInfoFromInstanceType(gpuInstance, Some(4))
    val sourceClusterInfoOpt =
      PropertiesLoader[ClusterProperties].loadFromContent(sourceWorkerInfo)
    // 2. Mock the properties loaded from eventLog
    val logEventsProps: mutable.Map[String, String] =
      mutable.LinkedHashMap[String, String](
        "spark.executor.cores" -> "8",
        "spark.executor.instances" -> "2",
        "spark.rapids.memory.pinnedPool.size" -> "5g",
        "spark.rapids.sql.enabled" -> "true",
        "spark.plugins" -> "com.nvidia.spark.SQLPlugin",
        "spark.executor.resource.gpu.amount" -> "1"
      )
    // 3. Define enforced properties for the target cluster
    // Note: These values should cause insufficient memory warning
    val enforcedSparkProperties = Map(
      "spark.executor.memory" -> "40g",
      "spark.executor.memoryOverhead" -> "30g"
    )
    val targetClusterInfo = ToolTestUtils.buildTargetClusterInfo(
      enforcedSparkProperties = enforcedSparkProperties
    )
    val infoProvider = getMockInfoProvider(8126464.0, Seq(0), Seq(0.004), logEventsProps,
      Some(testSparkVersion))
    val platform = PlatformFactory.createInstance(PlatformNames.DATAPROC,
      sourceClusterInfoOpt, Some(targetClusterInfo))
    val autoTuner = buildAutoTunerForTests(sourceWorkerInfo, infoProvider, platform)
    val (properties, comments) = autoTuner.getRecommendedProperties()
    val autoTunerOutput = Profiler.getAutoTunerResultsAsString(properties, comments)
    // scalastyle:off line.size.limit
    val expectedResults =
      s"""|
          |Spark Properties:
          |--conf spark.dataproc.enhanced.execution.enabled=false
          |--conf spark.dataproc.enhanced.optimizer.enabled=false
          |--conf spark.executor.cores=16
          |--conf spark.executor.memory=[FILL_IN_VALUE]
          |--conf spark.executor.memoryOverhead=[FILL_IN_VALUE]
          |--conf spark.locality.wait=0
          |--conf spark.rapids.memory.pinnedPool.size=[FILL_IN_VALUE]
          |--conf spark.rapids.shuffle.multiThreaded.reader.threads=28
          |--conf spark.rapids.shuffle.multiThreaded.writer.threads=28
          |--conf spark.rapids.sql.batchSizeBytes=2147483647b
          |--conf spark.rapids.sql.concurrentGpuTasks=3
          |--conf spark.rapids.sql.format.parquet.multithreaded.combine.waitTime=1000
          |--conf spark.rapids.sql.multiThreadedRead.numThreads=80
          |--conf spark.rapids.sql.reader.multithreaded.combine.sizeBytes=10m
          |--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$testSmVersion.RapidsShuffleManager
          |--conf spark.sql.adaptive.advisoryPartitionSizeInBytes=128m
          |--conf spark.sql.adaptive.autoBroadcastJoinThreshold=[FILL_IN_VALUE]
          |--conf spark.sql.adaptive.coalescePartitions.minPartitionSize=4m
          |--conf spark.sql.files.maxPartitionBytes=4g
          |--conf spark.task.resource.gpu.amount=0.001
          |
          |Comments:
          |- 'spark.dataproc.enhanced.execution.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.execution.enabled' was not set.
          |- 'spark.dataproc.enhanced.optimizer.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.optimizer.enabled' was not set.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.executor.memory")}
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.executor.memoryOverhead")}
          |- 'spark.rapids.shuffle.multiThreaded.reader.threads' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.writer.threads' was not set.
          |- 'spark.rapids.sql.batchSizeBytes' was not set.
          |- 'spark.rapids.sql.concurrentGpuTasks' was not set.
          |- 'spark.rapids.sql.format.parquet.multithreaded.combine.waitTime' was not set.
          |- 'spark.rapids.sql.multiThreadedRead.numThreads' was not set.
          |- 'spark.rapids.sql.reader.multithreaded.combine.sizeBytes' was not set.
          |- 'spark.shuffle.manager' was not set.
          |- 'spark.sql.adaptive.advisoryPartitionSizeInBytes' was not set.
          |- 'spark.sql.adaptive.autoBroadcastJoinThreshold' was not set.
          |- 'spark.sql.adaptive.enabled' should be enabled for better performance.
          |- 'spark.sql.files.maxPartitionBytes' was not set.
          |- 'spark.task.resource.gpu.amount' was not set.
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.executor.memory")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.executor.memoryOverhead")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.rapids.memory.pinnedPool.size")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.jars.missing")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.shuffle.jars")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemComment(89600)}
          |""".stripMargin
    // scalastyle:on line.size.limit
    compareOutput(expectedResults, autoTunerOutput)
  }

  // Test that the pinned pool property from the custom target cluster
  // props lead to AutoTuner warning about insufficient memory.
  test("AutoTuner warns about insufficient memory with pinned pool override") {
    // 1. Mock source cluster info for dataproc
    val instanceMapKey = NodeInstanceMapKey("g2-standard-16")
    val gpuInstance = PlatformInstanceTypes.DATAPROC_BY_INSTANCE_NAME(instanceMapKey)
    val sourceWorkerInfo = buildGpuWorkerInfoFromInstanceType(gpuInstance, Some(4))
    val sourceClusterInfoOpt =
      PropertiesLoader[ClusterProperties].loadFromContent(sourceWorkerInfo)
    // 2. Mock the properties loaded from eventLog
    val logEventsProps: mutable.Map[String, String] =
      mutable.LinkedHashMap[String, String](
        "spark.executor.cores" -> "8",
        "spark.executor.instances" -> "2",
        "spark.rapids.memory.pinnedPool.size" -> "5g",
        "spark.rapids.sql.enabled" -> "true",
        "spark.plugins" -> "com.nvidia.spark.SQLPlugin",
        "spark.executor.resource.gpu.amount" -> "1"
      )
    // 3. Define enforced properties for the target cluster
    val enforcedSparkProperties = Map(
      "spark.rapids.memory.pinnedPool.size" -> "30g", // Should cause insufficient memory warning
      "spark.sql.files.maxPartitionBytes" -> "101m"   // Should be enforced
    )
    val targetClusterInfo = ToolTestUtils.buildTargetClusterInfo(
      enforcedSparkProperties = enforcedSparkProperties
    )
    val infoProvider = getMockInfoProvider(8126464.0, Seq(0), Seq(0.004), logEventsProps,
      Some(testSparkVersion))
    val platform = PlatformFactory.createInstance(PlatformNames.DATAPROC,
      sourceClusterInfoOpt, Some(targetClusterInfo))
    val autoTuner = buildAutoTunerForTests(sourceWorkerInfo, infoProvider, platform)
    val (properties, comments) = autoTuner.getRecommendedProperties()
    val autoTunerOutput = Profiler.getAutoTunerResultsAsString(properties, comments)
    // scalastyle:off line.size.limit
    val expectedResults =
      s"""|
          |Spark Properties:
          |--conf spark.dataproc.enhanced.execution.enabled=false
          |--conf spark.dataproc.enhanced.optimizer.enabled=false
          |--conf spark.executor.cores=16
          |--conf spark.executor.memory=[FILL_IN_VALUE]
          |--conf spark.executor.memoryOverhead=[FILL_IN_VALUE]
          |--conf spark.locality.wait=0
          |--conf spark.rapids.memory.pinnedPool.size=[FILL_IN_VALUE]
          |--conf spark.rapids.shuffle.multiThreaded.reader.threads=28
          |--conf spark.rapids.shuffle.multiThreaded.writer.threads=28
          |--conf spark.rapids.sql.batchSizeBytes=2147483647b
          |--conf spark.rapids.sql.concurrentGpuTasks=3
          |--conf spark.rapids.sql.format.parquet.multithreaded.combine.waitTime=1000
          |--conf spark.rapids.sql.multiThreadedRead.numThreads=80
          |--conf spark.rapids.sql.reader.multithreaded.combine.sizeBytes=10m
          |--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$testSmVersion.RapidsShuffleManager
          |--conf spark.sql.adaptive.advisoryPartitionSizeInBytes=128m
          |--conf spark.sql.adaptive.autoBroadcastJoinThreshold=[FILL_IN_VALUE]
          |--conf spark.sql.adaptive.coalescePartitions.minPartitionSize=4m
          |--conf spark.sql.files.maxPartitionBytes=101m
          |--conf spark.task.resource.gpu.amount=0.001
          |
          |Comments:
          |- 'spark.dataproc.enhanced.execution.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.execution.enabled' was not set.
          |- 'spark.dataproc.enhanced.optimizer.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.optimizer.enabled' was not set.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.rapids.memory.pinnedPool.size")}
          |- 'spark.rapids.shuffle.multiThreaded.reader.threads' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.writer.threads' was not set.
          |- 'spark.rapids.sql.batchSizeBytes' was not set.
          |- 'spark.rapids.sql.concurrentGpuTasks' was not set.
          |- 'spark.rapids.sql.format.parquet.multithreaded.combine.waitTime' was not set.
          |- 'spark.rapids.sql.multiThreadedRead.numThreads' was not set.
          |- 'spark.rapids.sql.reader.multithreaded.combine.sizeBytes' was not set.
          |- 'spark.shuffle.manager' was not set.
          |- 'spark.sql.adaptive.advisoryPartitionSizeInBytes' was not set.
          |- 'spark.sql.adaptive.autoBroadcastJoinThreshold' was not set.
          |- 'spark.sql.adaptive.enabled' should be enabled for better performance.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.sql.files.maxPartitionBytes")}
          |- 'spark.task.resource.gpu.amount' was not set.
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.executor.memory")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.executor.memoryOverhead")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.rapids.memory.pinnedPool.size")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.jars.missing")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.shuffle.jars")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemComment(126975)}
          |""".stripMargin
    // scalastyle:on line.size.limit
    compareOutput(expectedResults, autoTunerOutput)
  }

  test("Test Kryo Serializer does not add GPU registrator again if already present") {
    // mock the properties loaded from eventLog
    val logEventsProps: mutable.Map[String, String] =
      mutable.LinkedHashMap[String, String](
        "spark.executor.cores" -> "16",
        "spark.executor.instances" -> "1",
        "spark.executor.memory" -> "80g",
        "spark.executor.resource.gpu.amount" -> "1",
        "spark.serializer" -> "org.apache.spark.serializer.KryoSerializer",
        "spark.kryo.registrator" ->
          "org.apache.SomeRegistrator,com.nvidia.spark.rapids.GpuKryoRegistrator"
      )
    val autoTuner = buildDefaultDataprocAutoTuner(logEventsProps)
    val (properties, comments) = autoTuner.getRecommendedProperties()
    val autoTunerOutput = Profiler.getAutoTunerResultsAsString(properties, comments)
    // scalastyle:off line.size.limit
    val expectedResults =
      s"""|
          |Spark Properties:
          |--conf spark.dataproc.enhanced.execution.enabled=false
          |--conf spark.dataproc.enhanced.optimizer.enabled=false
          |--conf spark.executor.memory=32g
          |--conf spark.executor.memoryOverhead=15564m
          |--conf spark.kryoserializer.buffer.max=512m
          |--conf spark.locality.wait=0
          |--conf spark.rapids.memory.pinnedPool.size=4g
          |--conf spark.rapids.shuffle.multiThreaded.maxBytesInFlight=4g
          |--conf spark.rapids.shuffle.multiThreaded.reader.threads=28
          |--conf spark.rapids.shuffle.multiThreaded.writer.threads=28
          |--conf spark.rapids.sql.batchSizeBytes=2147483647b
          |--conf spark.rapids.sql.concurrentGpuTasks=3
          |--conf spark.rapids.sql.enabled=true
          |--conf spark.rapids.sql.format.parquet.multithreaded.combine.waitTime=1000
          |--conf spark.rapids.sql.multiThreadedRead.numThreads=80
          |--conf spark.rapids.sql.reader.multithreaded.combine.sizeBytes=10m
          |--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$testSmVersion.RapidsShuffleManager
          |--conf spark.sql.adaptive.advisoryPartitionSizeInBytes=128m
          |--conf spark.sql.adaptive.autoBroadcastJoinThreshold=[FILL_IN_VALUE]
          |--conf spark.sql.adaptive.coalescePartitions.minPartitionSize=4m
          |--conf spark.sql.files.maxPartitionBytes=512m
          |--conf spark.task.resource.gpu.amount=0.001
          |
          |Comments:
          |- 'spark.dataproc.enhanced.execution.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.execution.enabled' was not set.
          |- 'spark.dataproc.enhanced.optimizer.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.optimizer.enabled' was not set.
          |- 'spark.executor.memoryOverhead' was not set.
          |- 'spark.kryoserializer.buffer.max' increasing the max buffer to prevent out-of-memory errors.
          |- 'spark.rapids.memory.pinnedPool.size' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.maxBytesInFlight' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.reader.threads' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.writer.threads' was not set.
          |- 'spark.rapids.sql.batchSizeBytes' was not set.
          |- 'spark.rapids.sql.concurrentGpuTasks' was not set.
          |- 'spark.rapids.sql.enabled' was not set.
          |- 'spark.rapids.sql.format.parquet.multithreaded.combine.waitTime' was not set.
          |- 'spark.rapids.sql.multiThreadedRead.numThreads' was not set.
          |- 'spark.rapids.sql.reader.multithreaded.combine.sizeBytes' was not set.
          |- 'spark.shuffle.manager' was not set.
          |- 'spark.sql.adaptive.advisoryPartitionSizeInBytes' was not set.
          |- 'spark.sql.adaptive.autoBroadcastJoinThreshold' was not set.
          |- 'spark.sql.adaptive.enabled' should be enabled for better performance.
          |- 'spark.sql.files.maxPartitionBytes' was not set.
          |- 'spark.task.resource.gpu.amount' was not set.
          |- RAPIDS Accelerator for Apache Spark jar is missing in "spark.plugins". Please refer to https://docs.nvidia.com/spark-rapids/user-guide/latest/getting-started/overview.html
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.jars.missing")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.shuffle.jars")}
          |""".stripMargin
    // scalastyle:on line.size.limit
    compareOutput(expectedResults, autoTunerOutput)
  }

  // Test that AutoTuner parses existing Kryo Registrator correctly
  // i.e. it removes duplicates, empty entries, and adds GpuKryoRegistrator
  test("Test AutoTuner parses existing Kryo Registrator correctly") {
    // mock the properties loaded from eventLog
    val logEventsProps: mutable.Map[String, String] =
      mutable.LinkedHashMap[String, String](
        "spark.executor.cores" -> "16",
        "spark.executor.instances" -> "1",
        "spark.executor.memory" -> "80g",
        "spark.executor.resource.gpu.amount" -> "1",
        "spark.serializer" -> "org.apache.spark.serializer.KryoSerializer",
        "spark.kryo.registrator" ->
          "org.apache.SomeRegistrator,, org.apache.OtherRegistrator,org.apache.SomeRegistrator"
      )
    val autoTuner = buildDefaultDataprocAutoTuner(logEventsProps)
    val (properties, comments) = autoTuner.getRecommendedProperties()
    val autoTunerOutput = Profiler.getAutoTunerResultsAsString(properties, comments)
    // scalastyle:off line.size.limit
    val expectedResults =
      s"""|
          |Spark Properties:
          |--conf spark.dataproc.enhanced.execution.enabled=false
          |--conf spark.dataproc.enhanced.optimizer.enabled=false
          |--conf spark.executor.memory=32g
          |--conf spark.executor.memoryOverhead=15564m
          |--conf spark.kryo.registrator=org.apache.SomeRegistrator,org.apache.OtherRegistrator,com.nvidia.spark.rapids.GpuKryoRegistrator
          |--conf spark.kryoserializer.buffer.max=512m
          |--conf spark.locality.wait=0
          |--conf spark.rapids.memory.pinnedPool.size=4g
          |--conf spark.rapids.shuffle.multiThreaded.maxBytesInFlight=4g
          |--conf spark.rapids.shuffle.multiThreaded.reader.threads=28
          |--conf spark.rapids.shuffle.multiThreaded.writer.threads=28
          |--conf spark.rapids.sql.batchSizeBytes=2147483647b
          |--conf spark.rapids.sql.concurrentGpuTasks=3
          |--conf spark.rapids.sql.enabled=true
          |--conf spark.rapids.sql.format.parquet.multithreaded.combine.waitTime=1000
          |--conf spark.rapids.sql.multiThreadedRead.numThreads=80
          |--conf spark.rapids.sql.reader.multithreaded.combine.sizeBytes=10m
          |--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$testSmVersion.RapidsShuffleManager
          |--conf spark.sql.adaptive.advisoryPartitionSizeInBytes=128m
          |--conf spark.sql.adaptive.autoBroadcastJoinThreshold=[FILL_IN_VALUE]
          |--conf spark.sql.adaptive.coalescePartitions.minPartitionSize=4m
          |--conf spark.sql.files.maxPartitionBytes=512m
          |--conf spark.task.resource.gpu.amount=0.001
          |
          |Comments:
          |- 'spark.dataproc.enhanced.execution.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.execution.enabled' was not set.
          |- 'spark.dataproc.enhanced.optimizer.enabled' should be disabled. WARN: Turning this property on might case the GPU accelerated Dataproc cluster to hang.
          |- 'spark.dataproc.enhanced.optimizer.enabled' was not set.
          |- 'spark.executor.memoryOverhead' was not set.
          |- 'spark.kryo.registrator' GpuKryoRegistrator must be appended to the existing value when using Kryo serialization.
          |- 'spark.kryoserializer.buffer.max' increasing the max buffer to prevent out-of-memory errors.
          |- 'spark.rapids.memory.pinnedPool.size' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.maxBytesInFlight' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.reader.threads' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.writer.threads' was not set.
          |- 'spark.rapids.sql.batchSizeBytes' was not set.
          |- 'spark.rapids.sql.concurrentGpuTasks' was not set.
          |- 'spark.rapids.sql.enabled' was not set.
          |- 'spark.rapids.sql.format.parquet.multithreaded.combine.waitTime' was not set.
          |- 'spark.rapids.sql.multiThreadedRead.numThreads' was not set.
          |- 'spark.rapids.sql.reader.multithreaded.combine.sizeBytes' was not set.
          |- 'spark.shuffle.manager' was not set.
          |- 'spark.sql.adaptive.advisoryPartitionSizeInBytes' was not set.
          |- 'spark.sql.adaptive.autoBroadcastJoinThreshold' was not set.
          |- 'spark.sql.adaptive.enabled' should be enabled for better performance.
          |- 'spark.sql.files.maxPartitionBytes' was not set.
          |- 'spark.task.resource.gpu.amount' was not set.
          |- RAPIDS Accelerator for Apache Spark jar is missing in "spark.plugins". Please refer to https://docs.nvidia.com/spark-rapids/user-guide/latest/getting-started/overview.html
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.jars.missing")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.shuffle.jars")}
          |""".stripMargin
    // scalastyle:on line.size.limit
    compareOutput(expectedResults, autoTunerOutput)
  }

  // This test uses target cluster properties with user-enforced Spark properties.
  // The platform is mocked as Kubernetes on OnPrem
  // to enable memory overhead calculation.
  // AutoTuner is expected to:
  // - Include the enforced Spark properties in the final configuration.
  test("Target cluster properties for OnPrem with enforced spark properties") {
    // 1. Mock source cluster info for OnPrem
    val sourceWorkerInfo = buildGpuWorkerInfoAsString(None, Some(8), Some("50g"))
    val sourceClusterInfoOpt =
      PropertiesLoader[ClusterProperties].loadFromContent(sourceWorkerInfo)
    // 2. Mock the properties loaded from eventLog
    val logEventsProps: mutable.Map[String, String] =
      mutable.LinkedHashMap[String, String](
        "spark.executor.cores" -> "8",
        "spark.executor.instances" -> "1",
        "spark.rapids.sql.enabled" -> "true",
        "spark.plugins" -> "com.nvidia.spark.SQLPlugin",
        "spark.executor.resource.gpu.amount" -> "1"
      )
    // 3. Define enforced properties for the target cluster
    val enforcedSparkProperties = Map(
      "spark.sql.shuffle.partitions" -> "101",
      "spark.sql.files.maxPartitionBytes" -> "101m",
      "spark.task.resource.gpu.amount" -> "0.25"
    )
    // sparkProperties:
    //   enforced:
    //    spark.sql.shuffle.partitions: 101
    //    spark.sql.files.maxPartitionBytes: 101m
    //    spark.task.resource.gpu.amount: 0.25
    val targetClusterInfo = ToolTestUtils.buildTargetClusterInfo(
      enforcedSparkProperties = enforcedSparkProperties
    )
    val infoProvider = getMockInfoProvider(0, Seq(0), Seq(0), logEventsProps,
      Some(testSparkVersion))
    val platform = PlatformFactory.createInstance(PlatformNames.ONPREM,
      sourceClusterInfoOpt, Some(targetClusterInfo))
    val autoTuner = buildAutoTunerForTests(sourceWorkerInfo, infoProvider, platform,
      sparkMaster = Some(Kubernetes))
    val (properties, comments) = autoTuner.getRecommendedProperties()
    val autoTunerOutput = Profiler.getAutoTunerResultsAsString(properties, comments)
    // scalastyle:off line.size.limit
    val expectedResults =
      s"""|
          |Spark Properties:
          |--conf spark.executor.memory=16g
          |--conf spark.executor.memoryOverhead=9g
          |--conf spark.locality.wait=0
          |--conf spark.rapids.memory.pinnedPool.size=3789m
          |--conf spark.rapids.shuffle.multiThreaded.reader.threads=20
          |--conf spark.rapids.shuffle.multiThreaded.writer.threads=20
          |--conf spark.rapids.sql.batchSizeBytes=2147483647b
          |--conf spark.rapids.sql.concurrentGpuTasks=2
          |--conf spark.rapids.sql.multiThreadedRead.numThreads=20
          |--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$testSmVersion.RapidsShuffleManager
          |--conf spark.sql.adaptive.advisoryPartitionSizeInBytes=128m
          |--conf spark.sql.adaptive.autoBroadcastJoinThreshold=[FILL_IN_VALUE]
          |--conf spark.sql.adaptive.coalescePartitions.minPartitionSize=4m
          |--conf spark.sql.files.maxPartitionBytes=101m
          |--conf spark.sql.shuffle.partitions=101
          |--conf spark.task.resource.gpu.amount=0.25
          |
          |Comments:
          |- 'spark.executor.memory' was not set.
          |- 'spark.executor.memoryOverhead' was not set.
          |- 'spark.rapids.memory.pinnedPool.size' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.reader.threads' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.writer.threads' was not set.
          |- 'spark.rapids.sql.batchSizeBytes' was not set.
          |- 'spark.rapids.sql.concurrentGpuTasks' was not set.
          |- 'spark.rapids.sql.multiThreadedRead.numThreads' was not set.
          |- 'spark.shuffle.manager' was not set.
          |- 'spark.sql.adaptive.advisoryPartitionSizeInBytes' was not set.
          |- 'spark.sql.adaptive.autoBroadcastJoinThreshold' was not set.
          |- 'spark.sql.adaptive.enabled' should be enabled for better performance.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.sql.files.maxPartitionBytes")}
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.sql.shuffle.partitions")}
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.task.resource.gpu.amount")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.jars.missing")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.shuffle.jars")}
          |""".stripMargin
    // scalastyle:on line.size.limit
    compareOutput(expectedResults, autoTunerOutput)
  }

  // This test uses target cluster properties with a worker node having 16 cores, 64g memory,
  // 1 L4 GPU, and user-enforced Spark properties. The platform is mocked as Kubernetes on OnPrem
  // to enable memory overhead calculation.
  // AutoTuner is expected to:
  // - Recommend 32g executor memory,
  // - Calculate overhead using the max pinned pool size (4g),
  // - Include the enforced Spark properties in the final configuration.
  test("Target cluster properties for OnPrem with workerInfo and enforced spark properties") {
    // 1. Mock source cluster info for OnPrem
    val sourceWorkerInfo = buildGpuWorkerInfoAsString(None, Some(8), Some("14000MiB"))
    val sourceClusterInfoOpt =
      PropertiesLoader[ClusterProperties].loadFromContent(sourceWorkerInfo)
    // 2. Mock the properties loaded from eventLog
    val logEventsProps: mutable.Map[String, String] =
      mutable.LinkedHashMap[String, String](
        "spark.executor.cores" -> "8",
        "spark.executor.instances" -> "2",
        "spark.rapids.memory.pinnedPool.size" -> "5g",
        "spark.rapids.sql.enabled" -> "true",
        "spark.plugins" -> "com.nvidia.spark.SQLPlugin",
        "spark.executor.resource.gpu.amount" -> "1"
      )
    // 3. Define enforced properties for the target cluster
    val enforcedSparkProperties = Map(
      "spark.sql.shuffle.partitions" -> "400",
      "spark.sql.files.maxPartitionBytes" -> "101m",
      "spark.task.resource.gpu.amount" -> "0.25",
      "spark.rapids.sql.concurrentGpuTasks" -> "1"  // For L4, default recommendation would be 3
    )
    // workerInfo:
    //   cpuCores: 16
    //   memoryGB: 64
    //   gpu:
    //     count: 1
    //     name: l4
    // sparkProperties:
    //   enforced:
    //    spark.sql.shuffle.partitions: 400
    //    spark.sql.files.maxPartitionBytes: 101m
    //    spark.task.resource.gpu.amount: 0.25
    //    spark.rapids.sql.concurrentGpuTasks: 2
    val targetClusterInfo = ToolTestUtils.buildTargetClusterInfo(
      cpuCores = Some(16), memoryGB = Some(64),
      gpuCount = Some(1), gpuDevice = Some(GpuTypes.L4),
      enforcedSparkProperties = enforcedSparkProperties
    )
    val infoProvider = getMockInfoProvider(0, Seq(0), Seq(0), logEventsProps,
      Some(testSparkVersion))
    val platform = PlatformFactory.createInstance(PlatformNames.ONPREM,
      sourceClusterInfoOpt, Some(targetClusterInfo))
    val autoTuner = buildAutoTunerForTests(sourceWorkerInfo, infoProvider, platform,
      sparkMaster = Some(Kubernetes))
    val (properties, comments) = autoTuner.getRecommendedProperties()
    val autoTunerOutput = Profiler.getAutoTunerResultsAsString(properties, comments)
    // scalastyle:off line.size.limit
    val expectedResults =
      s"""|
          |Spark Properties:
          |--conf spark.executor.cores=16
          |--conf spark.executor.memory=32g
          |--conf spark.executor.memoryOverhead=11468m
          |--conf spark.locality.wait=0
          |--conf spark.rapids.memory.pinnedPool.size=4g
          |--conf spark.rapids.shuffle.multiThreaded.reader.threads=24
          |--conf spark.rapids.shuffle.multiThreaded.writer.threads=24
          |--conf spark.rapids.sql.batchSizeBytes=2147483647b
          |--conf spark.rapids.sql.concurrentGpuTasks=1
          |--conf spark.rapids.sql.multiThreadedRead.numThreads=32
          |--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$testSmVersion.RapidsShuffleManager
          |--conf spark.sql.adaptive.advisoryPartitionSizeInBytes=128m
          |--conf spark.sql.adaptive.autoBroadcastJoinThreshold=[FILL_IN_VALUE]
          |--conf spark.sql.adaptive.coalescePartitions.minPartitionSize=4m
          |--conf spark.sql.files.maxPartitionBytes=101m
          |--conf spark.sql.shuffle.partitions=400
          |--conf spark.task.resource.gpu.amount=0.25
          |
          |Comments:
          |- 'spark.executor.memory' was not set.
          |- 'spark.executor.memoryOverhead' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.reader.threads' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.writer.threads' was not set.
          |- 'spark.rapids.sql.batchSizeBytes' was not set.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.rapids.sql.concurrentGpuTasks")}
          |- 'spark.rapids.sql.multiThreadedRead.numThreads' was not set.
          |- 'spark.shuffle.manager' was not set.
          |- 'spark.sql.adaptive.advisoryPartitionSizeInBytes' was not set.
          |- 'spark.sql.adaptive.autoBroadcastJoinThreshold' was not set.
          |- 'spark.sql.adaptive.enabled' should be enabled for better performance.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.sql.files.maxPartitionBytes")}
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.sql.shuffle.partitions")}
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.task.resource.gpu.amount")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.jars.missing")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.shuffle.jars")}
          |""".stripMargin
    // scalastyle:on line.size.limit
    compareOutput(expectedResults, autoTunerOutput)
  }

  // This test uses custom target cluster properties with 40g worker memory and 2 GPUs.
  // Now, each executor can use up to 20g (including memory and overhead).
  // The user enforces spark.executor.memory to 18g. This leaves insufficient room for overhead.
  // AutoTuner is expected to warn about the insufficient executor memory configuration.
  test("Target cluster properties for OnPrem with total executor memory " +
    "exceeding available worker memory") {
    // 1. Mock source cluster info for OnPrem
    val sourceWorkerInfo = buildGpuWorkerInfoAsString(None, Some(8), Some("14000MiB"))
    val sourceClusterInfoOpt =
      PropertiesLoader[ClusterProperties].loadFromContent(sourceWorkerInfo)
    // 2. Mock the properties loaded from eventLog
    val logEventsProps: mutable.Map[String, String] =
      mutable.LinkedHashMap[String, String](
        "spark.executor.cores" -> "16",
        "spark.executor.instances" -> "2",
        "spark.rapids.memory.pinnedPool.size" -> "5g",
        "spark.rapids.sql.enabled" -> "true",
        "spark.plugins" -> "com.nvidia.spark.SQLPlugin",
        "spark.executor.resource.gpu.amount" -> "1"
      )
    // 3. Define enforced properties for the target cluster
    val enforcedSparkProperties = Map(
      "spark.executor.cores" -> "8",
      "spark.executor.memory" -> "18g",   // Requesting more memory than available in the node
      "spark.sql.shuffle.partitions" -> "400"
    )
    // workerInfo:
    //   cpuCores: 16
    //   memoryGB: 40
    //   gpu:
    //     count: 2
    //     name: l4
    // sparkProperties:
    //   enforced:
    //    spark.executor.cores: 8
    //    spark.executor.memory: 18g
    //    spark.sql.shuffle.partitions: 400
    val targetClusterInfo = ToolTestUtils.buildTargetClusterInfo(
      cpuCores = Some(16), memoryGB = Some(40),
      gpuCount = Some(2), gpuDevice = Some(GpuTypes.L4),
      enforcedSparkProperties = enforcedSparkProperties
    )
    val infoProvider = getMockInfoProvider(0, Seq(0), Seq(0), logEventsProps,
      Some(testSparkVersion))
    val platform = PlatformFactory.createInstance(PlatformNames.ONPREM,
      sourceClusterInfoOpt, Some(targetClusterInfo))
    val autoTuner = buildAutoTunerForTests(sourceWorkerInfo, infoProvider, platform,
      sparkMaster = Some(Kubernetes))
    val (properties, comments) = autoTuner.getRecommendedProperties()
    val autoTunerOutput = Profiler.getAutoTunerResultsAsString(properties, comments)
    // scalastyle:off line.size.limit
    val expectedResults =
      s"""|
          |Spark Properties:
          |--conf spark.executor.cores=8
          |--conf spark.executor.memory=[FILL_IN_VALUE]
          |--conf spark.executor.memoryOverhead=[FILL_IN_VALUE]
          |--conf spark.locality.wait=0
          |--conf spark.rapids.memory.pinnedPool.size=[FILL_IN_VALUE]
          |--conf spark.rapids.shuffle.multiThreaded.reader.threads=20
          |--conf spark.rapids.shuffle.multiThreaded.writer.threads=20
          |--conf spark.rapids.sql.batchSizeBytes=2147483647b
          |--conf spark.rapids.sql.concurrentGpuTasks=3
          |--conf spark.rapids.sql.multiThreadedRead.numThreads=20
          |--conf spark.shuffle.manager=com.nvidia.spark.rapids.spark$testSmVersion.RapidsShuffleManager
          |--conf spark.sql.adaptive.advisoryPartitionSizeInBytes=128m
          |--conf spark.sql.adaptive.autoBroadcastJoinThreshold=[FILL_IN_VALUE]
          |--conf spark.sql.adaptive.coalescePartitions.minPartitionSize=4m
          |--conf spark.sql.files.maxPartitionBytes=512m
          |--conf spark.sql.shuffle.partitions=400
          |--conf spark.task.resource.gpu.amount=0.001
          |
          |Comments:
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.executor.cores")}
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.executor.memory")}
          |- 'spark.rapids.shuffle.multiThreaded.reader.threads' was not set.
          |- 'spark.rapids.shuffle.multiThreaded.writer.threads' was not set.
          |- 'spark.rapids.sql.batchSizeBytes' was not set.
          |- 'spark.rapids.sql.concurrentGpuTasks' was not set.
          |- 'spark.rapids.sql.multiThreadedRead.numThreads' was not set.
          |- 'spark.shuffle.manager' was not set.
          |- 'spark.sql.adaptive.advisoryPartitionSizeInBytes' was not set.
          |- 'spark.sql.adaptive.autoBroadcastJoinThreshold' was not set.
          |- 'spark.sql.adaptive.enabled' should be enabled for better performance.
          |- 'spark.sql.files.maxPartitionBytes' was not set.
          |- ${ProfilingAutoTunerConfigsProvider.getEnforcedPropertyComment("spark.sql.shuffle.partitions")}
          |- 'spark.task.resource.gpu.amount' was not set.
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.executor.memory")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.executor.memoryOverhead")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemCommentForKey("spark.rapids.memory.pinnedPool.size")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.jars.missing")}
          |- ${ProfilingAutoTunerConfigsProvider.classPathComments("rapids.shuffle.jars")}
          |- ${ProfilingAutoTunerConfigsProvider.notEnoughMemComment(24371)}
          |""".stripMargin
    // scalastyle:on line.size.limit
    compareOutput(expectedResults, autoTunerOutput)
  }

  // This test verifies that an error is thrown when the target cluster YAML file
  // contains both instance type (for CSP) and resource properties (for OnPrem).
  test("Should fail when target cluster contains both CSP instanceType and OnPrem resources") {
    TrampolineUtil.withTempDir { tempDir =>
      // workerInfo:
      //   instanceType: g2-standard-8
      //   cpuCores: 16
      //   memoryGB: 64
      //   gpu:
      //     count: 1
      //     name: l4
      intercept[IllegalArgumentException] {
        ToolTestUtils.createTargetClusterInfoFile(
          tempDir.getAbsolutePath,
          instanceType = Some("g2-standard-8"),
          cpuCores = Some(16), memoryGB = Some(64),
          gpuCount = Some(1), gpuDevice = Some(GpuTypes.L4))
      }
    }
  }

  // This test verifies that an error is thrown when the target cluster YAML file
  // contains resource properties (for OnPrem) except GPU.
  test("Should fail when target cluster contains OnPrem resources except GPU") {
    TrampolineUtil.withTempDir { tempDir =>
      // workerInfo:
      //   cpuCores: 16
      //   memoryGB: 64
      intercept[IllegalArgumentException] {
        ToolTestUtils.createTargetClusterInfoFile(
          tempDir.getAbsolutePath,
          cpuCores = Some(16), memoryGB = Some(64))
      }
    }
  }
}
