package otoroshi.wasm.proxywasm

import akka.stream.Materializer
import org.extism.sdk.otoroshi._
import otoroshi.env.Env
import otoroshi.wasm._

import java.util.Optional
import scala.concurrent.ExecutionContext

object ProxyWasmFunctions {

  private def getCurrentVmData(): VmData = {
    WasmContextSlot.getCurrentContext() match {
      case Some(data: VmData) => data
      case _                  => throw new RuntimeException("missing vm data")
    }
  }

  def build(
      state: ProxyWasmState
  )(implicit ec: ExecutionContext, env: Env, mat: Materializer): Seq[OtoroshiHostFunction[EnvUserData]] = {
    Seq(
      new OtoroshiHostFunction[EnvUserData](
        "proxy_log",
        parameters(3),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) => state.proxyLog(plugin, params(0).v.i32, params(1).v.i32, params(2).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_get_buffer_bytes",
        parameters(5),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) =>
          state.proxyGetBuffer(
            plugin,
            getCurrentVmData(),
            params(0).v.i32,
            params(1).v.i32,
            params(2).v.i32,
            params(3).v.i32,
            params(4).v.i32
          ),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_set_effective_context",
        parameters(1),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) => state.proxySetEffectiveContext(plugin, params(0).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_get_header_map_pairs",
        parameters(3),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) =>
          state.proxyGetHeaderMapPairs(plugin, getCurrentVmData(), params(0).v.i32, params(1).v.i32, params(2).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_set_buffer_bytes",
        parameters(5),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) =>
          state.proxySetBuffer(
            plugin,
            getCurrentVmData(),
            params(0).v.i32,
            params(1).v.i32,
            params(2).v.i32,
            params(3).v.i32,
            params(4).v.i32
          ),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_get_header_map_value",
        parameters(5),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) =>
          state.proxyGetHeaderMapValue(
            plugin,
            getCurrentVmData(),
            params(0).v.i32,
            params(1).v.i32,
            params(2).v.i32,
            params(3).v.i32,
            params(4).v.i32
          ),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_get_property",
        parameters(4),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) =>
          state.proxyGetProperty(
            plugin,
            getCurrentVmData(),
            params(0).v.i32,
            params(1).v.i32,
            params(2).v.i32,
            params(3).v.i32
          ),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_increment_metric",
        Seq(Bridge.ExtismValType.I32, Bridge.ExtismValType.I64).toArray,
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) => state.proxyIncrementMetricValue(plugin, getCurrentVmData(), params(0).v.i32, params(1).v.i64),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_define_metric",
        parameters(4),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) => state.proxyDefineMetric(plugin, params(0).v.i32, params(1).v.i32, params(2).v.i32, params(3).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_set_tick_period_milliseconds",
        parameters(1),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) => state.proxySetTickPeriodMilliseconds(getCurrentVmData(), params(0).v.i32),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_replace_header_map_value",
        parameters(5),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) =>
          state.proxyReplaceHeaderMapValue(
            plugin,
            getCurrentVmData(),
            params(0).v.i32,
            params(1).v.i32,
            params(2).v.i32,
            params(3).v.i32,
            params(4).v.i32
          ),
        Optional.empty[EnvUserData]()
      ),
      new OtoroshiHostFunction[EnvUserData](
        "proxy_send_local_response",
        parameters(8),
        parameters(1),
        (
            plugin: OtoroshiInternal,
            params: Array[Bridge.ExtismVal],
            returns: Array[Bridge.ExtismVal],
            data: Optional[EnvUserData]
        ) =>
          state.proxySendHttpResponse(
            plugin,
            params(0).v.i32,
            params(1).v.i32,
            params(2).v.i32,
            params(3).v.i32,
            params(4).v.i32,
            params(5).v.i32,
            params(6).v.i32,
            params(7).v.i32
          ),
        Optional.empty[EnvUserData]()
      )
    )
  }

  private def parameters(n: Int): Array[Bridge.ExtismValType] = {
    (0 until n).map(_ => Bridge.ExtismValType.I32).toArray
  }
}
