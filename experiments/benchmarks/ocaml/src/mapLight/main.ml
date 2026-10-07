(* Divide and conquer over [0, n), trivial work per element.

   Recursion is OCaml's natural form for this shape, so this is both the
   recursive variant and what an OCaml programmer writes. [mid] is recomputed at
   both use sites, as it is in the Fearless source. The checksum is n * n, which
   is 1.764e19 here: it exceeds OCaml's 63-bit native int but fits an unsigned
   64-bit word, which is what the Fearless [Nat] is. The sum is therefore an
   [Int64] printed as unsigned. *)

let n = 4_200_000_000

let leaf i = Int64.add (Int64.mul (Int64.of_int i) 2L) 1L

let mid lo hi = lo + ((hi - lo) / 2)

let rec solve lo hi =
  if hi - lo <= 1 then leaf lo
  else Int64.add (solve lo (mid lo hi)) (solve (mid lo hi) hi)

let () = Printf.printf "%Lu\n" (solve 0 n)
