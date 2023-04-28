mod types;

use extism_pdk::*;
use photon_rs::{
    native::open_image_from_bytes,
    transform::{resize, SamplingFilter},
};

const PLACEHOLDER_WIDTH: u32 = 10;

#[plugin_fn]
pub fn execute(
    Json(context): Json<types::WasmBackendContext>,
) -> FnResult<Json<types::WasmBackendResponse>> {

    let image = match open_image_from_bytes(&context.request.headers.get("image").unwrap().as_bytes()) {
        Ok(value) => value,
        Err(_) => {
            return Ok(Json(types::WasmBackendResponse {
                status: 400,
                headers: None,
                body_base64: None,
                body_bytes: None,
                body_str: None,
                body_json: None,
            }));
        }
    };
    let aspect_ratio = image.get_width() / image.get_height();

    Ok(Json(
        types::WasmBackendResponse {
            status: 400,
            body_str: Some(
                resize(
                    &image,
                    PLACEHOLDER_WIDTH,
                    PLACEHOLDER_WIDTH / aspect_ratio,
                    SamplingFilter::Lanczos3,
                )
                .get_base64()),
            headers: None,
            body_base64: None,
            body_bytes: None,
            body_json: None,
        }
    ))
}

/*

// WasmRouteMatcher

#[plugin_fn]
pub fn matches_route(Json(_context): Json<types::WasmMatchRouteContext>) -> FnResult<Json<types::WasmMatchRouteResponse>> {
    ///
}

// -------------------------

// WasmPreRoute

#[plugin_fn]
pub fn pre_route(Json(_context): Json<types::WasmPreRouteContext>) -> FnResult<Json<types::WasmPreRouteResponse>> {
    ///
}

// -------------------------

// WasmAccessValidator

#[plugin_fn]
pub fn can_access(Json(_context): Json<types::WasmAccessValidatorContext>) -> FnResult<Json<types::WasmAccessValidatorResponse>> {
    ///
}

// -------------------------

// WasmRequestTransformer


#[plugin_fn]
pub fn transform_request(Json(_context): Json<types::WasmRequestTransformerContext>) -> FnResult<Json<types::WasmTransformerResponse>> {
    ///
}

// -------------------------

// WasmBackend

#[plugin_fn]
pub fn call_backend(Json(_context): Json<types::WasmQueryContext>) -> FnResult<Json<types::WasmQueryResponse>> {
    ///
}

// -------------------------

// WasmResponseTransformer

#[plugin_fn]
pub fn transform_response(Json(_context): Json<types::WasmResponseTransformerContext>) -> FnResult<Json<types::WasmTransformerResponse>> {
    ///
}

// -------------------------

// WasmSink

#[plugin_fn]
pub fn sink_matches(Json(_context): Json<types::WasmSinkContext>) -> FnResult<Json<types::WasmSinkMatchesResponse>> {
    ///
}

#[plugin_fn]
pub fn sink_handle(Json(_context): Json<types::WasmSinkContext>) -> FnResult<Json<types::WasmSinkHandleResponse>> {
    ///
}

// -------------------------

// WasmRequestHandler

#[plugin_fn]
pub fn handle_request(Json(_context): Json<types::WasmRequestHandlerContext>) -> FnResult<Json<types::WasmRequestHandlerResponse>> {
    ///
}

// -------------------------

// WasmJob

#[plugin_fn]
pub fn job_run(Json(_context): Json<types::WasmJobContext>) -> FnResult<Json<types::WasmJobResult>> {
    ///
}

*/
