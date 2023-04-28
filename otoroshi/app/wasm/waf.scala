package otoroshi.wasm

import akka.stream.Materializer
import org.extism.sdk.parameters.{IntegerParameter, Parameters}
import org.extism.sdk._
import otoroshi.env.Env
import otoroshi.next.plugins.api.NgCachedConfigContext
import otoroshi.utils.syntax.implicits.BetterJsValue
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext;

object WAF extends AwaitCapable {

  def defaultFunction: ExtismFunction[EmptyUserData] =
    (
        plugin: ExtismCurrentPlugin,
        params: Array[LibExtism.ExtismVal],
        returns: Array[LibExtism.ExtismVal],
        data: Optional[EmptyUserData]
    ) => {
      System.out.println("defaultFunction");
    }


  def proxy_set_effective_context() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_set_effective_context",
    Array(LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_get_buffer_bytes() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_get_buffer_bytes",
    Array(LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_get_header_map_pairs() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_get_header_map_pairs",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_set_buffer_bytes() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_set_buffer_bytes",
    Array(LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_get_header_map_value() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_get_header_map_value",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_send_local_response() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_send_local_response",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_get_property() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_get_property",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_log() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_log",
    Array(LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32,LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_increment_metric() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_increment_metric",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I64),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )

  def proxy_define_metric() = new org.extism.sdk.HostFunction[EmptyUserData](
    "proxy_define_metric",
    Array(LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32, LibExtism.ExtismValType.I32),
    Array(LibExtism.ExtismValType.I32),
    defaultFunction,
    Optional.empty()
  )


  def getFunctions(config: WasmConfig, ctx: Option[NgCachedConfigContext])(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(proxy_set_effective_context(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_get_buffer_bytes(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_get_header_map_pairs(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_set_buffer_bytes(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_get_header_map_value(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_send_local_response(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_get_property(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_log(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_increment_metric(), _ => config.waf),
      HostFunctionWithAuthorization(proxy_define_metric(), _ => config.waf),
    )
  }

  def evaluate(plugin: Plugin, input: String): String = {
    plugin.call("_start", input)

    val parentId = 0
    val contextId = 12
    var builder = new IntegerParameter()

    val proxyOnContextCreateParameters = new Parameters(2)
    builder.add(proxyOnContextCreateParameters, contextId, 0)
    builder.add(proxyOnContextCreateParameters, parentId, 1)

    plugin.call("proxy_on_context_create", proxyOnContextCreateParameters, 0)

    val params = new Parameters(3)
    builder = new IntegerParameter()
    builder.add(params, contextId, 0)
    builder.add(params, 0, 1)
    builder.add(params, 0, 2)
    val requestHeadersAction = plugin.call("proxy_on_request_headers", params, 1)

    val bufferParams = new Parameters(6)
    builder.add(bufferParams, 0, 0) // i32(proxy_buffer_type_t) buffer_type
    builder.add(bufferParams, 0, 1) // i32(offset_t) offset
    builder.add(bufferParams, 0, 2) // i32(size_t) max_size
    builder.add(bufferParams, 0, 3) // i32(const char **) return_buffer_data
    builder.add(bufferParams, 0, 4) // i32(size_t *) return_buffer_size
    builder.add(bufferParams, 0, 5) // i32(uint32_t *) return_flags

    val bufferAction = plugin.call("proxy_get_buffer_bytes", bufferParams, 1)

    val sharedParams = new Parameters(5)
    builder.add(sharedParams, 0, 0) // i32 (const char*) key_data
    builder.add(sharedParams, 0, 1) // i32 (size_t) key_size
    builder.add(sharedParams, 0, 2) // i32 (const char*) value_data
    builder.add(sharedParams, 0, 3) // i32 (size_t) value_size
    builder.add(sharedParams, 0, 4) // i32 (uint32_t) cas

    val sharedDataAction = plugin.call("proxy_set_shared_data", sharedParams, 1)

    val sendHttpResponseParams = new Parameters(8)
    builder.add(sendHttpResponseParams, 0, 0) // i32 (uint32_t) response_code
    builder.add(sendHttpResponseParams, 0, 1) // i32(const char *) response_code_details_data
    builder.add(sendHttpResponseParams, 0, 2) // i32(size_t) response_code_details_size
    builder.add(sendHttpResponseParams, 0, 3) // i32(const char *) response_body_data
    builder.add(sendHttpResponseParams, 0, 4) // i32(size_t) response_body_size
    builder.add(sendHttpResponseParams, 0, 5) // i32(const char *) additional_headers_map_data
    builder.add(sendHttpResponseParams, 0, 6) // i32(size_t) additional_headers_size
    builder.add(sendHttpResponseParams, 0, 7) // i32(uint32_t) grpc_status

    val sendHttpResponseAction = plugin.call("proxy_send_http_response", sendHttpResponseParams, 1)

    println(requestHeadersAction.getValues(), bufferAction.getValues(), sharedDataAction.getValues(), sendHttpResponseAction.getValues())

    // new String(java.util.Arrays.copyOf(mem, size), StandardCharsets.UTF_8)

    Json.obj("result" -> true).stringify
  }

}