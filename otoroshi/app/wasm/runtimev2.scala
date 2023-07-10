package otoroshi.wasm

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.extism.sdk.manifest.{Manifest, MemoryOptions}
import org.extism.sdk.wasmotoroshi._
import org.extism.sdk.wasm.WasmSourceResolver
import otoroshi.env.Env
import otoroshi.models.WasmPlugin
import otoroshi.next.plugins.api.{NgPluginVisibility, NgStep}
import otoroshi.script._
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.CacheableWasmScript.CachedWasmScript
import otoroshi.wasm.proxywasm.VmData
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

sealed trait WasmVmAction

object WasmVmAction {
  case object WasmVmKillAction extends WasmVmAction
  case class WasmVmCallAction(
    parameters: WasmFunctionParameters,
    context: Option[VmData],
    promise: Promise[Either[JsValue, (String, ResultsWrapper)]]
  ) extends WasmVmAction
}

object WasmVm {
  val logger = Logger("otoroshi-wasm-vm")
  def fromConfig(config: WasmConfig)(implicit env: Env, ec: ExecutionContext): Future[Option[(WasmVm, WasmConfig)]] = {
    if (config.source.kind == WasmSourceKind.Local) {
      env.proxyState.wasmPlugin(config.source.path) match {
        case None => None.vfuture
        case Some(localPlugin) => {
          val localConfig = localPlugin.config
          localPlugin.pool().getPooledVm().map(vm => Some((vm, localConfig)))
        }
      }
    } else {
      config.pool().getPooledVm().map(vm => Some((vm, config)))
    }
  }
}

case class OPAWasmVm(opaDataAddr: Int, opaBaseHeapPtr: Int)

case class WasmVm(index: Int,
                  maxCalls: Int,
                  resetMemory: Boolean,
                  instance: WasmOtoroshiInstance,
                  vmDataRef: AtomicReference[VmData],
                  memories: Array[WasmOtoroshiLinearMemory],
                  functions: Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]],
                  pool: WasmVmPool,
                  var opaPointers: Option[OPAWasmVm] = None) {

  private val lastUsage: AtomicLong = new AtomicLong(System.currentTimeMillis())
  private val initializedRef: AtomicBoolean = new AtomicBoolean(false)
  private val killAtRelease: AtomicBoolean = new AtomicBoolean(false)
  private val inFlight = new AtomicInteger(0)
  private val callCounter = new AtomicInteger(0)
  private val queue = {
    val env = pool.env
    Source.queue[WasmVmAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
      .mapAsync(1)(handle)
      .toMat(Sink.ignore)(Keep.both)
      .run()(env.otoroshiMaterializer)._1
  }

  def calls: Int = callCounter.get()
  def current: Int = inFlight.get()

  private def handle(act: WasmVmAction): Future[Unit] = {
    Future.apply {
      lastUsage.set(System.currentTimeMillis())
      act match {
        case WasmVmAction.WasmVmKillAction => destroy()
        case action: WasmVmAction.WasmVmCallAction => {
          try {
            inFlight.decrementAndGet()
            // action.context.foreach(ctx => WasmContextSlot.setCurrentContext(ctx))
            action.context.foreach(ctx => vmDataRef.set(ctx))
            if (WasmVm.logger.isDebugEnabled) WasmVm.logger.debug(s"call vm ${index} with method ${action.parameters.functionName} on thread ${Thread.currentThread().getName} on path ${action.context.get.properties.get("request.path").map(v => new String(v))}")
            val res = action.parameters.call(instance)
            if (res.isRight && res.right.get._2.results.getValues() != null) {
              val ret = res.right.get._2.results.getValues()(0).v.i32
              if (ret > 7 || ret < 0) { // weird multi thread issues
                ignore()
                killAtRelease.set(true)
              }
            }
            action.promise.trySuccess(res)
          } catch {
            case t: Throwable => action.promise.tryFailure(t)
          } finally {
            if (resetMemory) {
              instance.reset()
            }
            WasmVm.logger.debug(s"functions: ${functions.size}")
            WasmVm.logger.debug(s"memories: ${memories.size}")
            // WasmContextSlot.clearCurrentContext()
            // vmDataRef.set(null)
            val count = callCounter.incrementAndGet()
            if (count >= maxCalls) {
              callCounter.set(0)
              if (WasmVm.logger.isDebugEnabled) WasmVm.logger.debug(s"killing vm ${index} with remaining ${inFlight.get()} calls (${count})")
              destroyAtRelease()
            }
          }
        }
      }
      ()
    }(WasmUtils.executor)
  }

  def reset(): Unit = instance.reset()

  def destroy(): Unit = {
    if (WasmVm.logger.isDebugEnabled) WasmVm.logger.debug(s"destroy vm: ${index}")
    WasmVm.logger.debug(s"destroy vm: ${index}")
    pool.clear(this)
    instance.close()
  }

  def isBusy(): Boolean = {
    inFlight.get() > 0
  }

  def destroyAtRelease(): Unit = {
    ignore()
    killAtRelease.set(true)
  }

  def release(): Unit = {
    if (killAtRelease.get()) {
      queue.offer(WasmVmAction.WasmVmKillAction)
    } else {
      pool.release(this)
    }
  }

  def lastUsedAd(): Long = lastUsage.get()

  def hasNotBeenUsedInTheLast(duration: FiniteDuration): Boolean = !hasBeenUsedInTheLast(duration)
  def consumesMoreThanMemoryPercent(percent: Double): Boolean = false // TODO: implements
  def tooSlow(max: Long): Boolean = false // TODO: implements

  def hasBeenUsedInTheLast(duration: FiniteDuration): Boolean = {
    val now = System.currentTimeMillis()
    val limit = lastUsage.get() + duration.toMillis
    now < limit
  }

  def ignore(): Unit = pool.ignore(this)

  def initialized(): Boolean = initializedRef.get()

  def initialize(f: => Any): Unit = {
    if (initializedRef.compareAndSet(false, true)) {
      f
    }
  }

  def finitialize[A](f: => Future[A]): Future[Unit] = {
    if (initializedRef.compareAndSet(false, true)) {
      f.map(_ => ())(pool.env.otoroshiExecutionContext)
    } else {
      ().vfuture
    }
  }

  def call(
    parameters: WasmFunctionParameters,
    context: Option[VmData],
  )(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (String, ResultsWrapper)]] = {
    val promise = Promise[Either[JsValue, (String, ResultsWrapper)]]()
    inFlight.incrementAndGet()
    lastUsage.set(System.currentTimeMillis())
    queue.offer(WasmVmAction.WasmVmCallAction(parameters, context, promise))
    promise.future
  }
}

case class WasmVmPoolAction(promise: Promise[WasmVm], options: WasmVmInitOptions) {
  private[wasm] def provideVm(vm: WasmVm): Unit = promise.trySuccess(vm)
}

object WasmVmPool {

  private[wasm] val logger = Logger("otoroshi-wasm-vm-pool")
  private[wasm] val engine = new WasmOtoroshiEngine()
  private val instances = new UnboundedTrieMap[String, WasmVmPool]()

  def allInstances(): Map[String, WasmVmPool] = instances.synchronized {
    instances.toMap
  }

  def forPlugin(plugin: WasmPlugin)(implicit env: Env): WasmVmPool = instances.synchronized {
    val key = plugin.id // s"plugin://${plugin.id}?cfg=${plugin.config.json.stringify.sha512}"
    instances.getOrUpdate(key) {
      new WasmVmPool(key, None, env)
    }
  }

  def forConfig(config: => WasmConfig)(implicit env: Env): WasmVmPool = instances.synchronized {
    val key = s"${config.source.cacheKey}?cfg=${config.json.stringify.sha512}"
    instances.getOrUpdate(key) {
      new WasmVmPool(key, config.some, env)
    }
  }

  private[wasm] def removePlugin(id: String): Unit = instances.synchronized {
    instances.remove(id)
  }
}

class WasmVmPool(stableId: => String, optConfig: => Option[WasmConfig], val env: Env) {

  WasmVmPool.logger.debug("new WasmVmPool")

  private val engine = new WasmOtoroshiEngine()
  private val counter = new AtomicInteger(-1)
  private val templateRef = new AtomicReference[WasmOtoroshiTemplate](null)
  private[wasm] val availableVms = new ConcurrentLinkedQueue[WasmVm]()
  private[wasm] val inUseVms = new ConcurrentLinkedQueue[WasmVm]()
  private val creatingRef = new AtomicBoolean(false)
  private val lastPluginVersion = new AtomicReference[String](null)
  private val requestsSource = Source.queue[WasmVmPoolAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
  private val prioritySource = Source.queue[WasmVmPoolAction](env.wasmQueueBufferSize, OverflowStrategy.dropTail)
  private val (priorityQueue, requestsQueue) = {
    prioritySource
      .mergePrioritizedMat(requestsSource, 99, 1, false)(Keep.both)
      .map(handleAction)
      .toMat(Sink.ignore)(Keep.both)
      .run()(env.otoroshiMaterializer)._1
  }

  private def handleAction(action: WasmVmPoolAction): Unit = try {
    wasmConfig() match {
      case None =>
        destroyCurrentVms()
        WasmVmPool.removePlugin(stableId)
        Future.failed(new RuntimeException(s"No more plugin ${stableId}"))
      case Some(wcfg) => {
        val changed = hasChanged(wcfg)
        val available = hasAvailableVm(wcfg)
        val creating = isVmCreating()
        val atMax = atMaxPoolCapacity(wcfg)
        if (changed) {
          WasmVmPool.logger.warn("plugin has changed, destroying old instances")
          destroyCurrentVms()
          createVm(wcfg, action.options, "has changed")
        }
        if (!available) {
          if (creating) {
            priorityQueue.offer(action)
            Future.successful(())
          } else {
            if (atMax) {
              priorityQueue.offer(action)
            } else {
              // create on
              createVm(wcfg, action.options, s"create - changed: ${changed} - available: ${available} - creating: ${creating} - atMax: $atMax - ${wcfg.instances} - ${inUseVms.size()}")
              priorityQueue.offer(action)
              // val vm = acquireVm()
              // action.provideVm(vm)
            }
          }
        } else {
          val vm = acquireVm()
          action.provideVm(vm)
        }
      }
    }
  } catch {
    case t: Throwable => t.printStackTrace()
  }

  private def createVm(config: WasmConfig, options: WasmVmInitOptions, from: String): Unit = synchronized {
    if (creatingRef.compareAndSet(false, true)) {
      val index = counter.incrementAndGet()
      WasmVmPool.logger.debug(s"creating vm: ${index}")// - $from")
      if (templateRef.get() == null) {
        val cache = WasmUtils.scriptCache(env)
        val key = config.source.cacheKey
        if (!cache.contains(key)) {
          WasmVmPool.logger.warn("fetching missing source")
          Await.result(config.source.getWasm()(env, env.otoroshiExecutionContext), 30.seconds) // TODO: fix it
        }
        val wasm = cache(key).asInstanceOf[CachedWasmScript].script
        val hash = wasm.sha256
        val resolver = new WasmSourceResolver()
        val source = resolver.resolve("wasm", wasm.toByteBuffer.array())
        templateRef.set(new WasmOtoroshiTemplate(engine, hash, new Manifest(
          Seq[org.extism.sdk.wasm.WasmSource](source).asJava,
          new MemoryOptions(config.memoryPages),
          config.config.asJava,
          config.allowedHosts.asJava,
          config.allowedPaths.asJava
        )))
      }
      val template = templateRef.get()
      val vmDataRef = new AtomicReference[VmData](null)
      val addedFunctions =  options.addHostFunctions(vmDataRef)
      val functions: Array[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = if (options.importDefaultHostFunctions) {
        HostFunctions.getFunctions(config, stableId, None)(env, env.otoroshiExecutionContext) ++ addedFunctions
      } else {
        addedFunctions.toArray[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]]
      }
      val memories = LinearMemories.getMemories(config)
      val instance = template.instantiate(engine, functions, memories, config.wasi)
      val vm = WasmVm(index, config.killOptions.maxCalls, options.resetMemory, instance, vmDataRef, memories, functions, this)
      availableVms.offer(vm)
      creatingRef.compareAndSet(true, false)
    }
  }

  private def acquireVm(): WasmVm = synchronized {
    if (availableVms.size() > 0) {
      availableVms.synchronized {
        val vm = availableVms.poll()
        availableVms.remove(vm)
        inUseVms.offer(vm)
        vm
      }
    } else {
      throw new RuntimeException("no instances available")
    }
  }

  private[wasm] def release(vm: WasmVm): Unit = synchronized {
    availableVms.synchronized {
      availableVms.offer(vm)
      inUseVms.remove(vm)
    }
  }

  private[wasm] def ignore(vm: WasmVm): Unit = synchronized {
    availableVms.synchronized {
      inUseVms.remove(vm)
    }
  }

  private[wasm] def clear(vm: WasmVm): Unit = synchronized {
    availableVms.synchronized {
      availableVms.remove(vm)
    }
  }

  private[wasm] def wasmConfig(): Option[WasmConfig] = {
    optConfig.orElse(env.proxyState.wasmPlugin(stableId).map(_.config))
  }

  private def hasAvailableVm(plugin: WasmConfig): Boolean = availableVms.size() > 0 && (inUseVms.size < plugin.instances)

  private def isVmCreating(): Boolean = creatingRef.get()

  private def atMaxPoolCapacity(plugin: WasmConfig): Boolean = (availableVms.size + inUseVms.size) >= plugin.instances

  private[wasm] def close(): Unit = availableVms.synchronized {
    engine.close()
  }

  private[wasm] def destroyCurrentVms(): Unit = availableVms.synchronized {
    WasmVmPool.logger.info("destroying all vms")
    availableVms.asScala.foreach(_.destroy())
    availableVms.clear()
    inUseVms.clear()
    //counter.set(0)
    creatingRef.set(false)
    lastPluginVersion.set(null)
  }

  private def hasChanged(config: WasmConfig): Boolean = {
    var oldHash = lastPluginVersion.get()
    if (oldHash == null) {
      oldHash = config.json.stringify.sha512
      lastPluginVersion.set(oldHash)
    }
    val currentHash = config.json.stringify.sha512
    oldHash != currentHash
  }

  def getPooledVm(options: WasmVmInitOptions = WasmVmInitOptions.empty()): Future[WasmVm] = {
    // println("-----------------")
    // println("\nscript-cache: \n");
    // println(WasmUtils.scriptCache(env).keySet.mkString("\n"))
    // println("\n-----------------")
    // println("\npool-cache: \n");
    // println(WasmVmPool.instances.keySet.mkString("\n"))
    // println("\n-----------------")
    val p = Promise[WasmVm]()
    requestsQueue.offer(WasmVmPoolAction(p, options))
    p.future
  }
}

case class WasmVmInitOptions(
  importDefaultHostFunctions: Boolean = true,
  resetMemory: Boolean = true,
  addHostFunctions: (AtomicReference[VmData]) => Seq[WasmOtoroshiHostFunction[_ <: WasmOtoroshiHostUserData]] = _ => Seq.empty
)

object WasmVmInitOptions {
  def empty(): WasmVmInitOptions = WasmVmInitOptions(
    importDefaultHostFunctions = true,
    resetMemory = true,
    addHostFunctions = _ => Seq.empty
  )
}

class WasmVmPoolCleaner extends Job {

  private val logger = Logger("otoroshi-wasm-vm-pool-cleaner")

  override def uniqueId: JobId = JobId("otoroshi.wasm.WasmVmPoolCleaner")

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgInternal

  override def steps: Seq[NgStep] = Seq(NgStep.Job)

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = JobInstantiation.OneInstancePerOtoroshiInstance

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 60.seconds.some

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = env.datastores.globalConfigDataStore.latest().plugins.config.select("wasm-vm-pool-cleaner-config").asOpt[JsObject].getOrElse(Json.obj())
    val globalNotUsedDuration = config.select("not-used-duration").asOpt[Long].map(v => v.millis).getOrElse(5.minutes)
    WasmVmPool.allInstances().foreach {
      case (key, pool) =>
        if (pool.inUseVms.isEmpty && pool.availableVms.isEmpty) {
          logger.warn(s"will destroy 1 wasm vms pool")
          pool.destroyCurrentVms()
          pool.close()
          WasmVmPool.removePlugin(key)
        } else {
          val options = pool.wasmConfig().map(_.killOptions)
          val unusedVms = pool.availableVms.asScala.filter(_.hasNotBeenUsedInTheLast(options.map(_.maxUnusedDuration).getOrElse(globalNotUsedDuration)))
          val tooMuchMemoryVms = (pool.availableVms.asScala ++ pool.inUseVms.asScala).filter(_.consumesMoreThanMemoryPercent(options.map(_.maxMemoryUsage).getOrElse(0.9)))
          val tooSlowVms = (pool.availableVms.asScala ++ pool.inUseVms.asScala).filter(_.tooSlow(options.map(_.maxAvgCallDuration).getOrElse(Long.MaxValue)))
          val allVms = unusedVms ++ tooMuchMemoryVms ++ tooSlowVms
          if (allVms.nonEmpty) {
            logger.warn(s"will destroy ${allVms.size} wasm vms")
          }
          allVms.foreach { vm =>
            if (vm.isBusy()) {
              vm.destroyAtRelease()
            } else {
              vm.ignore()
              vm.destroy()
            }
          }
        }
    }
    ().vfuture
  }
}

case class WasmVmKillOptions(
  maxCalls: Int = Int.MaxValue,
  maxMemoryUsage: Double = 0.9,
  maxAvgCallDuration: Long = Int.MaxValue,
  maxUnusedDuration: FiniteDuration = 5.minutes,
) {
  def json: JsValue = WasmVmKillOptions.format.writes(this)
}

object WasmVmKillOptions {
  val default = WasmVmKillOptions()
  val format = new Format[WasmVmKillOptions] {
    override def writes(o: WasmVmKillOptions): JsValue = Json.obj(
      "max_calls" -> o.maxCalls,
      "max_memory_usage" -> o.maxMemoryUsage,
      "max_avg_call_duration" -> o.maxAvgCallDuration,
      "max_unused_duration" -> o.maxUnusedDuration.toMillis,
    )
    override def reads(json: JsValue): JsResult[WasmVmKillOptions] = Try {
      WasmVmKillOptions(
        maxCalls = json.select("max_calls").asOpt[Int].getOrElse(Int.MaxValue),
        maxMemoryUsage = json.select("max_memory_usage").asOpt[Double].getOrElse(0.9),
        maxAvgCallDuration = json.select("max_avg_call_duration").asOpt[Long].getOrElse(Int.MaxValue),
        maxUnusedDuration = json.select("max_unused_duration").asOpt[Long].map(_.millis).getOrElse(5.minutes),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
  }
}