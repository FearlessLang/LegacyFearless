(* mapLight's skeleton with an expensive per-element function, combined with xor
   so the checksum does not depend on completion order.

   This one needs Int64 rather than the native int: xorshift64 is defined over a
   full 64-bit word and the checksum exceeds OCaml's 63-bit int. Int64 is a boxed
   type, which is a real characteristic of the language and not a handicap
   introduced here. *)

let n = 1_500_000
let rounds = 4000

let step x =
  let x = Int64.logxor x (Int64.shift_left x 13) in
  let x = Int64.logxor x (Int64.shift_right_logical x 7) in
  Int64.logxor x (Int64.shift_left x 17)

let rec mix x r = if r = 0 then x else mix (step x) (r - 1)

(* Offset by one so element 0 misses the xorshift fixed point at zero. *)
let leaf i = mix (Int64.of_int (i + 1)) rounds

let mid lo hi = lo + ((hi - lo) / 2)

let rec solve lo hi =
  if hi - lo <= 1 then leaf lo
  else Int64.logxor (solve lo (mid lo hi)) (solve (mid lo hi) hi)

let () = Printf.printf "%Lu\n" (solve 0 n)
