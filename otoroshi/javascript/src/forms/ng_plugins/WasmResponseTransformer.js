import WasmPlugin from './WasmPlugin';

export default {
  id: 'cp:otoroshi.next.plugins.WasmResponseTransformer',
  config_schema: WasmPlugin.config_schema,
  config_flow: WasmPlugin.config_flow,
};
