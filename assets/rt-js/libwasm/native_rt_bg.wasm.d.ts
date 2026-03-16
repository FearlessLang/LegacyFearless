/* tslint:disable */
/* eslint-disable */
export const memory: WebAssembly.Memory;
export const float_to_str: (a: number) => [number, number];
export const int_to_str: (a: bigint) => [number, number];
export const nat_to_str: (a: bigint) => [number, number];
export const byte_to_str: (a: number) => [number, number];
export const hash_string: (a: number, b: number) => bigint;
export const normalise_string: (a: number, b: number) => [number, number];
export const index_string: (a: number, b: number) => any;
export const compile_regex_pattern: (a: number, b: number) => [number, number, number];
export const does_regex_match: (a: number, b: number, c: number, d: number) => number;
export const validate_string: (a: number, b: number) => [number, number];
export const __wbindgen_export_0: WebAssembly.Table;
export const __wbindgen_free: (a: number, b: number, c: number) => void;
export const __wbindgen_malloc: (a: number, b: number) => number;
export const __externref_table_dealloc: (a: number) => void;
export const __wbindgen_start: () => void;
