/* tslint:disable */
/* eslint-disable */
export function float_to_str(n: number): Uint8Array;
export function int_to_str(n: bigint): Uint8Array;
export function nat_to_str(n: bigint): Uint8Array;
export function byte_to_str(n: number): Uint8Array;
export function hash_string(bytes: Uint8Array): bigint;
export function normalise_string(bytes: Uint8Array): Uint8Array;
export function index_string(bytes: Uint8Array): Int32Array;
export function compile_regex_pattern(pattern_bytes: Uint8Array): any;
export function does_regex_match(pattern_bytes: Uint8Array, text_bytes: Uint8Array): boolean;
export function validate_string(bytes: Uint8Array): void;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
  readonly memory: WebAssembly.Memory;
  readonly float_to_str: (a: number) => [number, number];
  readonly int_to_str: (a: bigint) => [number, number];
  readonly nat_to_str: (a: bigint) => [number, number];
  readonly byte_to_str: (a: number) => [number, number];
  readonly hash_string: (a: number, b: number) => bigint;
  readonly normalise_string: (a: number, b: number) => [number, number];
  readonly index_string: (a: number, b: number) => any;
  readonly compile_regex_pattern: (a: number, b: number) => [number, number, number];
  readonly does_regex_match: (a: number, b: number, c: number, d: number) => number;
  readonly validate_string: (a: number, b: number) => [number, number];
  readonly __wbindgen_export_0: WebAssembly.Table;
  readonly __wbindgen_free: (a: number, b: number, c: number) => void;
  readonly __wbindgen_malloc: (a: number, b: number) => number;
  readonly __externref_table_dealloc: (a: number) => void;
  readonly __wbindgen_start: () => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;
/**
* Instantiates the given `module`, which can either be bytes or
* a precompiled `WebAssembly.Module`.
*
* @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
*
* @returns {InitOutput}
*/
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
* If `module_or_path` is {RequestInfo} or {URL}, makes a request and
* for everything else, calls `WebAssembly.instantiate` directly.
*
* @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
*
* @returns {Promise<InitOutput>}
*/
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
