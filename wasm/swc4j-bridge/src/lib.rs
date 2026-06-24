use anyhow::{Error, Result};
use base64::Engine;
use deno_ast::swc::ast::EsVersion;
use deno_ast::swc::codegen::{text_writer::JsWriter, Config, Emitter};
use deno_ast::swc::common::{sync::Lrc, BytePos, FileName, FilePathMapping, LineCol, SourceMap};
use deno_ast::*;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::alloc::{alloc, dealloc, Layout};
use std::slice;
use std::str;

const VERSION: &str = "2.1.0";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BridgeRequest<T> {
    code: String,
    options: T,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TransformOptionsRequest {
    ascii_only: bool,
    emit_assert_for_import_attributes: bool,
    inline_sources: bool,
    keep_comments: bool,
    media_type: i32,
    minify: bool,
    omit_last_semi: bool,
    parse_mode: i32,
    source_map: i32,
    specifier: String,
    target: i32,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TranspileOptionsRequest {
    capture_ast: bool,
    capture_comments: bool,
    capture_tokens: bool,
    decorators: DecoratorsRequest,
    imports_not_used_as_values: i32,
    inline_sources: bool,
    jsx: Option<JsxRequest>,
    keep_comments: bool,
    media_type: i32,
    module_kind: i32,
    parse_mode: i32,
    scope_analysis: bool,
    source_map: i32,
    specifier: String,
    var_decl_imports: bool,
    verbatim_module_syntax: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
enum DecoratorsRequest {
    Ecma,
    LegacyTypeScript {
        #[serde(rename = "emitMetadata", alias = "emit_metadata")]
        emit_metadata: bool,
    },
    None,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", tag = "kind")]
enum JsxRequest {
    Automatic {
        development: bool,
        #[serde(rename = "importSource", alias = "import_source")]
        import_source: Option<String>,
    },
    Classic {
        factory: String,
        #[serde(rename = "fragmentFactory", alias = "fragment_factory")]
        fragment_factory: String,
    },
    Precompile {
        automatic: JsxAutomaticRequest,
        #[serde(rename = "dynamicProps", alias = "dynamic_props")]
        dynamic_props: Option<Vec<String>>,
        #[serde(rename = "skipElements", alias = "skip_elements")]
        skip_elements: Option<Vec<String>>,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct JsxAutomaticRequest {
    development: bool,
    import_source: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct BridgeOutput {
    ok: bool,
    code: Option<String>,
    error: Option<String>,
    media_type: Option<i32>,
    parse_mode: Option<i32>,
    source_map: Option<String>,
    version: Option<&'static str>,
}

#[derive(Debug)]
struct TransformOptions {
    ascii_only: bool,
    emit_assert_for_import_attributes: bool,
    inline_sources: bool,
    keep_comments: bool,
    media_type: MediaType,
    minify: bool,
    omit_last_semi: bool,
    parse_mode: BridgeParseMode,
    source_map: SourceMapOption,
    specifier: String,
    target: EsVersion,
}

#[derive(Debug)]
struct TranspileOptions {
    capture_ast: bool,
    capture_comments: bool,
    capture_tokens: bool,
    decorators: DecoratorsTranspileOption,
    imports_not_used_as_values: ImportsNotUsedAsValues,
    inline_sources: bool,
    jsx: Option<JsxRuntime>,
    keep_comments: bool,
    media_type: MediaType,
    module_kind: BridgeModuleKind,
    parse_mode: BridgeParseMode,
    scope_analysis: bool,
    source_map: SourceMapOption,
    specifier: String,
    var_decl_imports: bool,
    verbatim_module_syntax: bool,
}

#[derive(Clone, Copy, Debug)]
enum BridgeModuleKind {
    Auto,
    Esm,
    Cjs,
}

#[derive(Clone, Copy, Debug)]
enum BridgeParseMode {
    Program,
    Module,
    Script,
}

impl TransformOptions {
    fn get_specifier(&self) -> Result<ModuleSpecifier> {
        ModuleSpecifier::parse(self.specifier.as_str()).map_err(Error::msg)
    }
}

impl TranspileOptions {
    fn get_specifier(&self) -> Result<ModuleSpecifier> {
        ModuleSpecifier::parse(self.specifier.as_str()).map_err(Error::msg)
    }
}

impl From<TransformOptionsRequest> for TransformOptions {
    fn from(value: TransformOptionsRequest) -> Self {
        Self {
            ascii_only: value.ascii_only,
            emit_assert_for_import_attributes: value.emit_assert_for_import_attributes,
            inline_sources: value.inline_sources,
            keep_comments: value.keep_comments,
            media_type: media_type(value.media_type),
            minify: value.minify,
            omit_last_semi: value.omit_last_semi,
            parse_mode: parse_mode(value.parse_mode),
            source_map: source_map(value.source_map),
            specifier: value.specifier,
            target: es_version(value.target),
        }
    }
}

impl From<TranspileOptionsRequest> for TranspileOptions {
    fn from(value: TranspileOptionsRequest) -> Self {
        Self {
            capture_ast: value.capture_ast,
            capture_comments: value.capture_comments,
            capture_tokens: value.capture_tokens,
            decorators: decorators(value.decorators),
            imports_not_used_as_values: imports_not_used_as_values(value.imports_not_used_as_values),
            inline_sources: value.inline_sources,
            jsx: value.jsx.map(jsx_runtime),
            keep_comments: value.keep_comments,
            media_type: media_type(value.media_type),
            module_kind: module_kind(value.module_kind),
            parse_mode: parse_mode(value.parse_mode),
            scope_analysis: value.scope_analysis,
            source_map: source_map(value.source_map),
            specifier: value.specifier,
            var_decl_imports: value.var_decl_imports,
            verbatim_module_syntax: value.verbatim_module_syntax,
        }
    }
}

#[no_mangle]
pub extern "C" fn swc4j_alloc(len: usize) -> *mut u8 {
    if len == 0 {
        return std::ptr::null_mut();
    }
    let layout = Layout::array::<u8>(len).expect("valid allocation layout");
    unsafe { alloc(layout) }
}

#[no_mangle]
pub extern "C" fn swc4j_free(ptr: *mut u8, len: usize) {
    if ptr.is_null() || len == 0 {
        return;
    }
    let layout = Layout::array::<u8>(len).expect("valid allocation layout");
    unsafe {
        dealloc(ptr, layout);
    }
}

#[no_mangle]
pub extern "C" fn swc4j_version() -> u64 {
    ok_response(json!({
        "ok": true,
        "version": VERSION,
    }))
}

#[no_mangle]
pub extern "C" fn swc4j_transform(ptr: *const u8, len: usize) -> u64 {
    invoke(ptr, len, transform_request)
}

#[no_mangle]
pub extern "C" fn swc4j_transpile(ptr: *const u8, len: usize) -> u64 {
    invoke(ptr, len, transpile_request)
}

fn invoke<F>(ptr: *const u8, len: usize, handler: F) -> u64
where
    F: FnOnce(&str) -> Result<serde_json::Value>,
{
    if ptr.is_null() {
        return error_response("Request pointer is null");
    }
    let bytes = unsafe { slice::from_raw_parts(ptr, len) };
    match str::from_utf8(bytes).map_err(Error::msg).and_then(handler) {
        Ok(value) => ok_response(value),
        Err(error) => error_response(error.to_string()),
    }
}

fn transform_request(request: &str) -> Result<serde_json::Value> {
    let request: BridgeRequest<TransformOptionsRequest> = serde_json::from_str(request)?;
    let options = TransformOptions::from(request.options);
    let output = transform(request.code, &options)?;
    Ok(json!({
        "ok": true,
        "code": output.code,
        "mediaType": media_type_id(output.media_type),
        "parseMode": parse_mode_id(output.parse_mode),
        "sourceMap": output.source_map,
    }))
}

fn transpile_request(request: &str) -> Result<serde_json::Value> {
    let request: BridgeRequest<TranspileOptionsRequest> = serde_json::from_str(request)?;
    let options = TranspileOptions::from(request.options);
    let output = transpile(request.code, &options)?;
    Ok(json!({
        "ok": true,
        "code": output.code,
        "mediaType": media_type_id(output.media_type),
        "parseMode": parse_mode_id(output.parse_mode),
        "sourceMap": output.source_map,
    }))
}

fn ok_response(value: serde_json::Value) -> u64 {
    pack_string(serde_json::to_string(&value).expect("response JSON should serialize"))
}

fn error_response(error: impl ToString) -> u64 {
    let output = BridgeOutput {
        ok: false,
        code: None,
        error: Some(error.to_string()),
        media_type: None,
        parse_mode: None,
        source_map: None,
        version: None,
    };
    pack_string(serde_json::to_string(&output).expect("error JSON should serialize"))
}

fn pack_string(value: String) -> u64 {
    let bytes = value.into_bytes();
    let len = bytes.len();
    let ptr = swc4j_alloc(len);
    if len > 0 {
        unsafe {
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), ptr, len);
        }
    }
    ((len as u64) << 32) | (ptr as u32 as u64)
}

fn parse_by_mode(parse_params: ParseParams, parse_mode: BridgeParseMode) -> Result<ParsedSource> {
    match parse_mode {
        BridgeParseMode::Module => parse_module(parse_params),
        BridgeParseMode::Script => parse_script(parse_params),
        BridgeParseMode::Program => parse_program(parse_params),
    }
    .map_err(Error::msg)
}

fn transform(code: String, options: &TransformOptions) -> Result<TransformOutput> {
    let specifier = options.get_specifier()?;
    let parse_params = ParseParams {
        specifier: specifier.clone(),
        text: code.into(),
        media_type: options.media_type,
        capture_tokens: false,
        maybe_syntax: None,
        scope_analysis: false,
    };
    let code = parse_params.text.clone().to_string();
    let parsed_source = parse_by_mode(parse_params, options.parse_mode)?;
    let source_map = Lrc::new(SourceMap::new(FilePathMapping::empty()));
    let filename = Lrc::new(FileName::Url(specifier));
    source_map.new_source_file(filename, code);
    let mut buffer = Vec::new();
    let mut source_map_buffer = Vec::new();
    let mut writer = Box::new(JsWriter::new(
        source_map.clone(),
        "\n",
        &mut buffer,
        Some(&mut source_map_buffer),
    ));
    writer.set_indent_str("  ");
    let config = Config::default()
        .with_minify(options.minify)
        .with_ascii_only(options.ascii_only)
        .with_omit_last_semi(options.omit_last_semi)
        .with_target(options.target)
        .with_emit_assert_for_import_attributes(options.emit_assert_for_import_attributes);
    let swc_comments = parsed_source.comments().as_single_threaded();
    let mut emitter = Emitter {
        cfg: config,
        comments: if options.keep_comments {
            Some(&swc_comments)
        } else {
            None
        },
        cm: source_map.clone(),
        wr: writer,
    };
    match parsed_source.program_ref() {
        ProgramRef::Module(module) => emitter.emit_module(module)?,
        ProgramRef::Script(script) => emitter.emit_script(script)?,
    };
    let mut code = String::from_utf8(buffer).map_err(Error::msg)?;
    if options.omit_last_semi && code.ends_with(';') {
        code.truncate(code.len() - 1);
    }
    let source_map = source_map_text(
        options.source_map,
        options.inline_sources,
        &source_map,
        &source_map_buffer,
        &mut code,
    )?;
    Ok(TransformOutput {
        code,
        media_type: parsed_source.media_type(),
        parse_mode: parsed_parse_mode(&parsed_source),
        source_map,
    })
}

fn transpile(code: String, options: &TranspileOptions) -> Result<TranspileOutput> {
    if options.capture_ast || options.capture_comments || options.capture_tokens {
        return Err(Error::msg(
            "WASM transpile AST, comment, and token capture are not supported",
        ));
    }
    let specifier = options.get_specifier()?;
    let parse_params = ParseParams {
        specifier,
        text: code.into(),
        media_type: options.media_type,
        capture_tokens: false,
        maybe_syntax: None,
        scope_analysis: options.scope_analysis,
    };
    let parsed_source = parse_by_mode(parse_params, options.parse_mode)?;
    let transpile_options = deno_ast::TranspileOptions {
        decorators: options.decorators.clone(),
        imports_not_used_as_values: options.imports_not_used_as_values,
        jsx: options.jsx.clone(),
        var_decl_imports: options.var_decl_imports,
        verbatim_module_syntax: options.verbatim_module_syntax,
    };
    let transpile_module_options = TranspileModuleOptions {
        module_kind: match options.module_kind {
            BridgeModuleKind::Auto => None,
            BridgeModuleKind::Esm => Some(ModuleKind::Esm),
            BridgeModuleKind::Cjs => Some(ModuleKind::Cjs),
        },
    };
    let emit_options = EmitOptions {
        inline_sources: options.inline_sources,
        remove_comments: !options.keep_comments,
        source_map: options.source_map,
        source_map_base: None,
        source_map_file: None,
    };
    let result = parsed_source
        .clone()
        .transpile(&transpile_options, &transpile_module_options, &emit_options)
        .map_err(Error::msg)?;
    let emitted_source = result.into_source();
    Ok(TranspileOutput {
        code: emitted_source.text,
        media_type: parsed_source.media_type(),
        parse_mode: parsed_parse_mode(&parsed_source),
        source_map: emitted_source.source_map,
    })
}

fn source_map_text(
    source_map_option: SourceMapOption,
    inline_sources: bool,
    source_map: &Lrc<SourceMap>,
    source_map_buffer: &[(BytePos, LineCol)],
    code: &mut String,
) -> Result<Option<String>> {
    if source_map_option == SourceMapOption::None {
        return Ok(None);
    }
    let mut buffer = Vec::new();
    let source_map_config = SourceMapConfig {
        inline_sources,
        maybe_base: None,
    };
    source_map
        .build_source_map(source_map_buffer, None, source_map_config)
        .to_writer(&mut buffer)?;
    if source_map_option == SourceMapOption::Inline {
        if !code.ends_with('\n') {
            code.push('\n');
        }
        code.push_str("//# sourceMappingURL=data:application/json;base64,");
        base64::prelude::BASE64_STANDARD.encode_string(buffer, code);
        Ok(None)
    } else {
        Ok(Some(String::from_utf8(buffer).map_err(Error::msg)?))
    }
}

#[derive(Debug)]
struct TransformOutput {
    code: String,
    media_type: MediaType,
    parse_mode: BridgeParseMode,
    source_map: Option<String>,
}

#[derive(Debug)]
struct TranspileOutput {
    code: String,
    media_type: MediaType,
    parse_mode: BridgeParseMode,
    source_map: Option<String>,
}

fn parsed_parse_mode(parsed_source: &ParsedSource) -> BridgeParseMode {
    if parsed_source.compute_is_script() {
        BridgeParseMode::Script
    } else {
        BridgeParseMode::Module
    }
}

fn decorators(value: DecoratorsRequest) -> DecoratorsTranspileOption {
    match value {
        DecoratorsRequest::Ecma => DecoratorsTranspileOption::Ecma,
        DecoratorsRequest::LegacyTypeScript { emit_metadata } => {
            DecoratorsTranspileOption::LegacyTypeScript { emit_metadata }
        }
        DecoratorsRequest::None => DecoratorsTranspileOption::None,
    }
}

fn jsx_runtime(value: JsxRequest) -> JsxRuntime {
    match value {
        JsxRequest::Automatic {
            development,
            import_source,
        } => JsxRuntime::Automatic(JsxAutomaticOptions {
            development,
            import_source,
        }),
        JsxRequest::Classic {
            factory,
            fragment_factory,
        } => JsxRuntime::Classic(JsxClassicOptions {
            factory,
            fragment_factory,
        }),
        JsxRequest::Precompile {
            automatic,
            dynamic_props,
            skip_elements,
        } => JsxRuntime::Precompile(JsxPrecompileOptions {
            automatic: JsxAutomaticOptions {
                development: automatic.development,
                import_source: automatic.import_source,
            },
            dynamic_props,
            skip_elements,
        }),
    }
}

fn imports_not_used_as_values(id: i32) -> ImportsNotUsedAsValues {
    match id {
        1 => ImportsNotUsedAsValues::Preserve,
        2 => ImportsNotUsedAsValues::Error,
        _ => ImportsNotUsedAsValues::Remove,
    }
}

fn media_type(id: i32) -> MediaType {
    match id {
        0 => MediaType::JavaScript,
        1 => MediaType::Jsx,
        2 => MediaType::Mjs,
        3 => MediaType::Cjs,
        4 => MediaType::TypeScript,
        5 => MediaType::Mts,
        6 => MediaType::Cts,
        7 => MediaType::Dts,
        8 => MediaType::Dmts,
        9 => MediaType::Dcts,
        10 => MediaType::Tsx,
        11 => MediaType::Css,
        12 => MediaType::Json,
        13 => MediaType::Jsonc,
        14 => MediaType::Json5,
        15 => MediaType::Html,
        16 => MediaType::Markdown,
        17 => MediaType::Sql,
        18 => MediaType::Wasm,
        19 => MediaType::SourceMap,
        _ => MediaType::Unknown,
    }
}

fn media_type_id(value: MediaType) -> i32 {
    match value {
        MediaType::JavaScript => 0,
        MediaType::Jsx => 1,
        MediaType::Mjs => 2,
        MediaType::Cjs => 3,
        MediaType::TypeScript => 4,
        MediaType::Mts => 5,
        MediaType::Cts => 6,
        MediaType::Dts => 7,
        MediaType::Dmts => 8,
        MediaType::Dcts => 9,
        MediaType::Tsx => 10,
        MediaType::Css => 11,
        MediaType::Json => 12,
        MediaType::Jsonc => 13,
        MediaType::Json5 => 14,
        MediaType::Html => 15,
        MediaType::Markdown => 16,
        MediaType::Sql => 17,
        MediaType::Wasm => 18,
        MediaType::SourceMap => 19,
        MediaType::Unknown => 20,
    }
}

fn module_kind(id: i32) -> BridgeModuleKind {
    match id {
        1 => BridgeModuleKind::Esm,
        2 => BridgeModuleKind::Cjs,
        _ => BridgeModuleKind::Auto,
    }
}

fn parse_mode(id: i32) -> BridgeParseMode {
    match id {
        1 => BridgeParseMode::Module,
        2 => BridgeParseMode::Script,
        _ => BridgeParseMode::Program,
    }
}

fn parse_mode_id(value: BridgeParseMode) -> i32 {
    match value {
        BridgeParseMode::Program => 0,
        BridgeParseMode::Module => 1,
        BridgeParseMode::Script => 2,
    }
}

fn source_map(id: i32) -> SourceMapOption {
    match id {
        1 => SourceMapOption::Separate,
        2 => SourceMapOption::None,
        _ => SourceMapOption::Inline,
    }
}

fn es_version(id: i32) -> EsVersion {
    match id {
        1 => EsVersion::Es3,
        2 => EsVersion::Es5,
        3 => EsVersion::Es2015,
        4 => EsVersion::Es2016,
        5 => EsVersion::Es2017,
        6 => EsVersion::Es2018,
        7 => EsVersion::Es2019,
        8 => EsVersion::Es2020,
        9 => EsVersion::Es2021,
        10 => EsVersion::Es2022,
        11 => EsVersion::Es2023,
        12 => EsVersion::Es2024,
        _ => EsVersion::EsNext,
    }
}
